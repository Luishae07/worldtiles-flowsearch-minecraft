// mcgen turns a part of the world into a grid of blocks (1 block = 1 metre) for the page minecraft.flowsearch.org.
//
//	go run ./tools/mcgen -out data/vatican.mc -name "Vatican City" -iso VA
//
// It asks Postpass (Geofabrik's OpenStreetMap database) for the border, buildings, roads, water, parks and trees, reads the height of the ground from
// the elevation tiles (Terrarium on AWS), and writes one small gzip file: for every square metre the height of the ground, what is on the surface,
// and for a building the height of its walls and of its roof, with their materials. The page builds the 3D blocks from that file in the browser.
//
// Data: (c) OpenStreetMap contributors (ODbL); elevation: Mapzen Terrain Tiles on AWS (SRTM, NASA and others, public data).
package main

import (
	"bytes"
	"compress/gzip"
	"encoding/binary"
	"encoding/json"
	"flag"
	"fmt"
	"image/png"
	"io"
	"math"
	"net/http"
	"net/url"
	"os"
	"sort"
	"strconv"
	"strings"
	"time"
)

const postpass = "https://postpass.geofabrik.de/api/interpreter"

// surface materials
const (
	sVoid = iota
	sGrass
	sPlaza
	sAsphalt
	sGravel
	sWater
	sDirt
	sSand
)

// wall materials (10..) and roof materials (20..)
const (
	wSandstone = 10 + iota
	wBrick
	wStoneBrick
	wConcrete
	wPlaster
	wOchre
)
const (
	rTerracotta = 20 + iota
	rSlate
	rCopper
	rStone
)

type feat struct {
	Geometry struct {
		Type        string          `json:"type"`
		Coordinates json.RawMessage `json:"coordinates"`
	} `json:"geometry"`
	Properties map[string]any `json:"properties"`
}

func ask(sql string) []feat {
	for try := 0; try < 3; try++ {
		req, _ := http.NewRequest("POST", postpass, strings.NewReader("data="+url.QueryEscape(sql)))
		req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
		req.Header.Set("User-Agent", "flowsearch-mcgen/1.0")
		resp, err := (&http.Client{Timeout: 120 * time.Second}).Do(req)
		if err != nil {
			time.Sleep(2 * time.Second)
			continue
		}
		b, _ := io.ReadAll(resp.Body)
		resp.Body.Close()
		if resp.StatusCode != 200 {
			fmt.Fprintf(os.Stderr, "postpass %d: %.200s\n", resp.StatusCode, b)
			time.Sleep(2 * time.Second)
			continue
		}
		var fc struct {
			Features []feat `json:"features"`
		}
		if json.Unmarshal(b, &fc) == nil {
			return fc.Features
		}
	}
	fmt.Fprintln(os.Stderr, "giving up on a query")
	return nil
}

func str(p map[string]any, k string) string {
	if p == nil {
		return ""
	}
	if tags, ok := p["tags"].(map[string]any); ok {
		if v, ok := tags[k].(string); ok {
			return v
		}
	}
	if v, ok := p[k].(string); ok {
		return v
	}
	return ""
}

func num(s string) float64 {
	s = strings.ReplaceAll(s, ",", ".")
	i := 0
	for i < len(s) && (s[i] == '-' || s[i] == '.' || (s[i] >= '0' && s[i] <= '9')) {
		i++
	}
	f, err := strconv.ParseFloat(s[:i], 64)
	if err != nil {
		return math.NaN()
	}
	if strings.Contains(s, "ft") {
		f *= 0.3048
	}
	return f
}

type pt [2]float64

// polygons: a list of rings (the first of each polygon is the outer one) in lon/lat.
func polys(f feat) [][][]pt {
	var out [][][]pt
	switch f.Geometry.Type {
	case "Polygon":
		var c [][][2]float64
		json.Unmarshal(f.Geometry.Coordinates, &c)
		out = append(out, conv(c))
	case "MultiPolygon":
		var c [][][][2]float64
		json.Unmarshal(f.Geometry.Coordinates, &c)
		for _, p := range c {
			out = append(out, conv(p))
		}
	}
	return out
}

func conv(c [][][2]float64) [][]pt {
	var r [][]pt
	for _, ring := range c {
		rr := make([]pt, len(ring))
		for i, p := range ring {
			rr[i] = pt(p)
		}
		r = append(r, rr)
	}
	return r
}

func lines(f feat) [][]pt {
	var out [][]pt
	switch f.Geometry.Type {
	case "LineString":
		var c [][2]float64
		json.Unmarshal(f.Geometry.Coordinates, &c)
		l := make([]pt, len(c))
		for i, p := range c {
			l[i] = pt(p)
		}
		out = append(out, l)
	case "MultiLineString":
		var c [][][2]float64
		json.Unmarshal(f.Geometry.Coordinates, &c)
		for _, ll := range c {
			l := make([]pt, len(ll))
			for i, p := range ll {
				l[i] = pt(p)
			}
			out = append(out, l)
		}
	}
	return out
}

func point(f feat) (pt, bool) {
	if f.Geometry.Type != "Point" {
		return pt{}, false
	}
	var c [2]float64
	json.Unmarshal(f.Geometry.Coordinates, &c)
	return pt(c), true
}

// ---- the grid ----

type grid struct {
	W, H              int
	lon0, lat0        float64 // the north-west corner
	mLon, mLat        float64
	ground, surf      []uint8
	wallH, roofH      []uint8
	wallM, roofM      []uint8
	inside            []bool
	minElev, elevSpan float64
}

func (g *grid) xy(p pt) (float64, float64) {
	return (p[0] - g.lon0) * g.mLon, (g.lat0 - p[1]) * g.mLat
}

func (g *grid) idx(x, z int) int { return z*g.W + x }

func inRing(r [][2]float64, x, z float64) bool {
	in := false
	for i, j := 0, len(r)-1; i < len(r); i, j = i+1, i {
		if (r[i][1] > z) != (r[j][1] > z) && x < (r[j][0]-r[i][0])*(z-r[i][1])/(r[j][1]-r[i][1])+r[i][0] {
			in = !in
		}
	}
	return in
}

// local converts a polygon (rings in lon/lat) to metres in the grid.
func (g *grid) local(rings [][]pt) [][][2]float64 {
	out := make([][][2]float64, len(rings))
	for i, r := range rings {
		out[i] = make([][2]float64, len(r))
		for k, p := range r {
			x, z := g.xy(p)
			out[i][k] = [2]float64{x, z}
		}
	}
	return out
}

func inPoly(rings [][][2]float64, x, z float64) bool {
	in := false
	for _, r := range rings {
		if inRing(r, x, z) {
			in = !in // outer ring in, each hole out
		}
	}
	return in
}

func bounds(rings [][][2]float64) (x0, z0, x1, z1 float64) {
	x0, z0, x1, z1 = 1e18, 1e18, -1e18, -1e18
	for _, r := range rings {
		for _, p := range r {
			x0, x1 = math.Min(x0, p[0]), math.Max(x1, p[0])
			z0, z1 = math.Min(z0, p[1]), math.Max(z1, p[1])
		}
	}
	return
}

// fill calls fn for every cell whose middle is inside the polygon.
func (g *grid) fill(rings [][][2]float64, fn func(x, z int)) {
	x0, z0, x1, z1 := bounds(rings)
	for z := int(math.Max(0, math.Floor(z0))); z <= int(math.Min(float64(g.H-1), math.Ceil(z1))); z++ {
		for x := int(math.Max(0, math.Floor(x0))); x <= int(math.Min(float64(g.W-1), math.Ceil(x1))); x++ {
			if inPoly(rings, float64(x)+0.5, float64(z)+0.5) {
				fn(x, z)
			}
		}
	}
}

func segDist(px, pz, ax, az, bx, bz float64) float64 {
	dx, dz := bx-ax, bz-az
	l := dx*dx + dz*dz
	t := 0.0
	if l > 0 {
		t = math.Max(0, math.Min(1, ((px-ax)*dx+(pz-az)*dz)/l))
	}
	return math.Hypot(px-(ax+t*dx), pz-(az+t*dz))
}

// strokeLine paints the cells within half a width of a line.
func (g *grid) strokeLine(l []pt, width float64, fn func(x, z int)) {
	var pts [][2]float64
	for _, p := range l {
		x, z := g.xy(p)
		pts = append(pts, [2]float64{x, z})
	}
	h := width / 2
	for i := 0; i+1 < len(pts); i++ {
		a, b := pts[i], pts[i+1]
		x0, x1 := int(math.Floor(math.Min(a[0], b[0])-h-1)), int(math.Ceil(math.Max(a[0], b[0])+h+1))
		z0, z1 := int(math.Floor(math.Min(a[1], b[1])-h-1)), int(math.Ceil(math.Max(a[1], b[1])+h+1))
		for z := z0; z <= z1; z++ {
			for x := x0; x <= x1; x++ {
				if x < 0 || z < 0 || x >= g.W || z >= g.H {
					continue
				}
				if segDist(float64(x)+0.5, float64(z)+0.5, a[0], a[1], b[0], b[1]) <= h {
					fn(x, z)
				}
			}
		}
	}
}

// ---- elevation ----

type tileImg struct {
	w, h int
	v    []float64
}

func fetchTile(z, x, y int) *tileImg {
	u := fmt.Sprintf("https://s3.amazonaws.com/elevation-tiles-prod/terrarium/%d/%d/%d.png", z, x, y)
	resp, err := http.Get(u)
	if err != nil || resp.StatusCode != 200 {
		return nil
	}
	defer resp.Body.Close()
	im, err := png.Decode(resp.Body)
	if err != nil {
		return nil
	}
	b := im.Bounds()
	t := &tileImg{w: b.Dx(), h: b.Dy(), v: make([]float64, b.Dx()*b.Dy())}
	for yy := 0; yy < t.h; yy++ {
		for xx := 0; xx < t.w; xx++ {
			r, g, bl, _ := im.At(b.Min.X+xx, b.Min.Y+yy).RGBA()
			t.v[yy*t.w+xx] = float64(r>>8)*256 + float64(g>>8) + float64(bl>>8)/256 - 32768
		}
	}
	return t
}

func main() {
	out := flag.String("out", "data/area.mc", "output file")
	name := flag.String("name", "Vatican City", "name shown on the page")
	iso := flag.String("iso", "VA", "ISO 3166-1 code of the country whose border is the edge of the map")
	flag.Parse()

	// the border
	fmt.Println("border …")
	border := ask("SELECT osm_id, geom FROM postpass_polygon WHERE tags->>'boundary'='administrative' AND tags->>'admin_level'='2' AND tags->>'ISO3166-1'='" + strings.ReplaceAll(*iso, "'", "") + "'")
	var bring [][][]pt
	for _, f := range border {
		bring = append(bring, polys(f)...)
	}
	if len(bring) == 0 {
		fmt.Fprintln(os.Stderr, "no border found")
		os.Exit(1)
	}
	w, s, e, n := 1e9, 1e9, -1e9, -1e9
	for _, p := range bring {
		for _, r := range p {
			for _, c := range r {
				w, e = math.Min(w, c[0]), math.Max(e, c[0])
				s, n = math.Min(s, c[1]), math.Max(n, c[1])
			}
		}
	}
	mid := (s + n) / 2
	g := &grid{lon0: w, lat0: n, mLat: 110540, mLon: 111320 * math.Cos(mid*math.Pi/180)}
	g.W, g.H = int(math.Ceil((e-w)*g.mLon)), int(math.Ceil((n-s)*g.mLat))
	if g.W*g.H > 12_000_000 {
		fmt.Fprintln(os.Stderr, "the area is too big for this tool:", g.W, "x", g.H)
		os.Exit(1)
	}
	N := g.W * g.H
	g.ground, g.surf, g.wallH, g.roofH, g.wallM, g.roofM, g.inside = make([]uint8, N), make([]uint8, N), make([]uint8, N), make([]uint8, N), make([]uint8, N), make([]uint8, N), make([]bool, N)
	fmt.Printf("grid %d x %d (%.1f x %.1f km)\n", g.W, g.H, float64(g.W)/1000, float64(g.H)/1000)
	for _, p := range bring {
		g.fill(g.local(p), func(x, z int) { g.inside[g.idx(x, z)] = true; g.surf[g.idx(x, z)] = sGrass })
	}
	envelope := fmt.Sprintf("ST_MakeEnvelope(%.6f,%.6f,%.6f,%.6f,4326)", w, s, e, n)

	// the ground
	fmt.Println("ground height …")
	const zoom = 15
	tx := func(lon float64) float64 { return (lon + 180) / 360 * float64(int(1)<<zoom) }
	ty := func(lat float64) float64 {
		r := lat * math.Pi / 180
		return (1 - math.Asinh(math.Tan(r))/math.Pi) / 2 * float64(int(1)<<zoom)
	}
	tiles := map[[2]int]*tileImg{}
	for x := int(tx(w)); x <= int(tx(e)); x++ {
		for y := int(ty(n)); y <= int(ty(s)); y++ {
			tiles[[2]int{x, y}] = fetchTile(zoom, x, y)
		}
	}
	elev := make([]float64, N)
	minE := 1e9
	for z := 0; z < g.H; z++ {
		for x := 0; x < g.W; x++ {
			lon := g.lon0 + (float64(x)+0.5)/g.mLon
			lat := g.lat0 - (float64(z)+0.5)/g.mLat
			fx, fy := tx(lon), ty(lat)
			t := tiles[[2]int{int(fx), int(fy)}]
			v := 0.0
			if t != nil {
				px, py := (fx-math.Floor(fx))*float64(t.w), (fy-math.Floor(fy))*float64(t.h)
				ix, iy := int(math.Min(float64(t.w-2), math.Max(0, px-0.5))), int(math.Min(float64(t.h-2), math.Max(0, py-0.5)))
				ax, ay := math.Max(0, px-0.5)-float64(ix), math.Max(0, py-0.5)-float64(iy)
				v = t.v[iy*t.w+ix]*(1-ax)*(1-ay) + t.v[iy*t.w+ix+1]*ax*(1-ay) + t.v[(iy+1)*t.w+ix]*(1-ax)*ay + t.v[(iy+1)*t.w+ix+1]*ax*ay
			}
			elev[g.idx(x, z)] = v
			if g.inside[g.idx(x, z)] {
				minE = math.Min(minE, v)
			}
		}
	}
	g.minElev = minE
	for i := range elev {
		h := math.Round(elev[i] - minE)
		g.ground[i] = uint8(math.Max(0, math.Min(255, h)))
	}

	// parks, plazas, water
	fmt.Println("parks, plazas, water …")
	for _, f := range ask("SELECT osm_id, tags, geom FROM postpass_polygon WHERE geom && " + envelope + " AND (tags->>'natural'='water' OR tags->>'waterway'='riverbank' OR tags->>'amenity'='fountain' OR tags->>'highway'='pedestrian' OR tags->>'place'='square' OR tags->>'area:highway' IN ('pedestrian','footway') OR tags->>'landuse' IN ('grass','farmland','meadow','construction','brownfield') OR tags->>'natural' IN ('beach','sand','bare_rock','scrub','wood') OR tags->>'leisure' IN ('park','garden','pitch'))") {
		m := uint8(0)
		switch {
		case str(f.Properties, "natural") == "water" || str(f.Properties, "waterway") == "riverbank" || str(f.Properties, "amenity") == "fountain":
			m = sWater
		case str(f.Properties, "highway") == "pedestrian" || str(f.Properties, "place") == "square" || str(f.Properties, "area:highway") != "":
			m = sPlaza
		case str(f.Properties, "natural") == "beach" || str(f.Properties, "natural") == "sand":
			m = sSand
		case str(f.Properties, "landuse") == "construction" || str(f.Properties, "landuse") == "brownfield" || str(f.Properties, "natural") == "bare_rock":
			m = sDirt
		default:
			m = sGrass
		}
		for _, p := range polys(f) {
			g.fill(g.local(p), func(x, z int) {
				i := g.idx(x, z)
				if !g.inside[i] {
					return
				}
				if m == sWater {
					if g.ground[i] > 0 {
						g.ground[i]--
					}
				}
				g.surf[i] = m
			})
		}
	}

	// roads
	fmt.Println("roads …")
	widths := map[string]float64{"motorway": 10, "trunk": 9, "primary": 8, "secondary": 7, "tertiary": 6, "residential": 5, "unclassified": 5, "living_street": 4, "service": 3.5, "pedestrian": 4, "track": 3, "footway": 2, "path": 1.6, "steps": 2, "cycleway": 2}
	roads := ask("SELECT osm_id, tags, geom FROM postpass_line WHERE geom && " + envelope + " AND (tags ? 'highway' OR tags->>'railway' IN ('rail','tram','light_rail'))")
	sort.SliceStable(roads, func(i, j int) bool {
		return widths[str(roads[i].Properties, "highway")] > widths[str(roads[j].Properties, "highway")]
	}) // the narrow ones are painted last, on top
	for _, f := range roads {
		hw := str(f.Properties, "highway")
		w2, ok := widths[hw]
		m := uint8(sAsphalt)
		if hw == "" { // a railway
			w2, ok, m = 3, true, sGravel
		}
		if !ok || str(f.Properties, "tunnel") == "yes" {
			continue
		}
		if hw == "footway" || hw == "path" || hw == "steps" || hw == "cycleway" || hw == "track" || hw == "pedestrian" {
			m = sGravel
			if str(f.Properties, "surface") == "paving_stones" || str(f.Properties, "surface") == "sett" || str(f.Properties, "surface") == "cobblestone" {
				m = sPlaza
			}
		}
		for _, l := range lines(f) {
			g.strokeLine(l, w2, func(x, z int) {
				if i := g.idx(x, z); g.inside[i] && g.surf[i] != sWater {
					g.surf[i] = m
				}
			})
		}
	}

	// buildings
	fmt.Println("buildings …")
	type bld struct {
		rings [][][2]float64
		tags  func(string) string
		part  bool
		id    int
	}
	var blds []bld
	bf := ask("SELECT osm_id, tags, geom FROM postpass_polygon WHERE geom && " + envelope + " AND (tags ? 'building' OR tags ? 'building:part')")
	for _, f := range bf {
		ff := f
		for _, p := range polys(f) {
			blds = append(blds, bld{rings: g.local(p), tags: func(k string) string { return str(ff.Properties, k) }, part: str(f.Properties, "building:part") != "" && str(f.Properties, "building") == "", id: int(num(fmt.Sprint(f.Properties["osm_id"])))})
		}
	}
	sort.SliceStable(blds, func(i, j int) bool { return !blds[i].part && blds[j].part }) // outlines first, parts on top of them
	painted := 0
	for _, b := range blds {
		H := num(b.tags("height"))
		if math.IsNaN(H) {
			if lv := num(b.tags("building:levels")); !math.IsNaN(lv) {
				H = lv*3.2 + 1
			} else {
				H = 9
			}
		}
		H = math.Max(2, math.Min(250, H))
		shape := b.tags("roof:shape")
		rh := num(b.tags("roof:height"))
		// the middle of the building and how far it is from the nearest wall at the most (for the roof)
		x0, z0, x1, z1 := bounds(b.rings)
		var cells [][2]int
		g.fill(b.rings, func(x, z int) { cells = append(cells, [2]int{x, z}) })
		if len(cells) == 0 {
			continue
		}
		dist := func(x, z int) float64 {
			d := 1e9
			for _, r := range b.rings {
				for i, j := 0, len(r)-1; i < len(r); i, j = i+1, i {
					d = math.Min(d, segDist(float64(x)+0.5, float64(z)+0.5, r[j][0], r[j][1], r[i][0], r[i][1]))
				}
			}
			return d
		}
		maxD := 0.0
		ds := make([]float64, len(cells))
		if len(cells) < 4000 || shape != "" {
			for i, c := range cells {
				ds[i] = dist(c[0], c[1])
				maxD = math.Max(maxD, ds[i])
			}
		}
		roofH := 0.0
		switch shape {
		case "dome", "onion", "cone":
			roofH = rh
			if math.IsNaN(roofH) || roofH <= 0 {
				roofH = math.Min(maxD*0.9, H*0.5)
			}
		case "hipped", "pyramidal", "gabled", "half-hipped", "gambrel", "mansard", "round":
			roofH = rh
			if math.IsNaN(roofH) || roofH <= 0 {
				roofH = math.Min(maxD*0.8, 8)
			}
		default:
			if shape == "" && !b.part && len(cells) < 400 && maxD > 0 && H < 15 { // a small house without a mapped roof: a simple pitched roof
				roofH = math.Min(maxD*0.7, 4)
			}
		}
		roofH = math.Min(roofH, H-1)
		wallH := H - roofH
		wall, roof := wallMat(b.tags, b.id), roofMat(b.tags, b.id)
		for i, c := range cells {
			ix := g.idx(c[0], c[1])
			if !g.inside[ix] {
				continue
			}
			extra := 0.0
			if roofH > 0 && maxD > 0 {
				t := math.Min(1, ds[i]/maxD)
				switch shape {
				case "dome", "onion":
					extra = roofH * math.Sqrt(math.Max(0, 1-(1-t)*(1-t)))
				default:
					extra = roofH * t
				}
			}
			g.wallH[ix], g.roofH[ix] = uint8(math.Round(wallH)), uint8(math.Round(extra))
			g.wallM[ix], g.roofM[ix] = wall, roof
			g.surf[ix] = sPlaza
			painted++
		}
		_, _, _, _ = x0, z0, x1, z1
	}
	fmt.Println("building cells:", painted)

	// trees
	fmt.Println("trees …")
	type tree struct{ X, Z, H int }
	var trees []tree
	for _, f := range ask("SELECT osm_id, tags, geom FROM postpass_point WHERE geom && " + envelope + " AND tags->>'natural'='tree'") {
		p, ok := point(f)
		if !ok {
			continue
		}
		x, z := g.xy(p)
		if x < 0 || z < 0 || int(x) >= g.W || int(z) >= g.H {
			continue
		}
		i := g.idx(int(x), int(z))
		if !g.inside[i] || g.wallH[i] > 0 {
			continue
		}
		h := 5
		if v := num(str(f.Properties, "height")); !math.IsNaN(v) {
			h = int(math.Max(3, math.Min(20, v)))
		}
		trees = append(trees, tree{int(x), int(z), h})
	}
	fmt.Println("trees:", len(trees))

	// the file: "MCV1\n", a JSON header, then six arrays of W*H bytes, all in one gzip
	hdr := map[string]any{"name": *name, "w": g.W, "h": g.H, "lon0": g.lon0, "lat0": g.lat0, "mLon": g.mLon, "mLat": g.mLat, "minElev": g.minElev, "trees": trees, "made": time.Now().UTC().Format("2006-01-02")}
	hb, _ := json.Marshal(hdr)
	var buf bytes.Buffer
	gz, _ := gzip.NewWriterLevel(&buf, gzip.BestCompression)
	gz.Write([]byte("MCV1\n"))
	var l [4]byte
	binary.LittleEndian.PutUint32(l[:], uint32(len(hb)))
	gz.Write(l[:])
	gz.Write(hb)
	for _, a := range [][]uint8{g.ground, g.surf, g.wallH, g.roofH, g.wallM, g.roofM} {
		gz.Write(a)
	}
	gz.Close()
	if err := os.MkdirAll(dirOf(*out), 0o755); err != nil {
		fmt.Fprintln(os.Stderr, "error:", err)
		os.Exit(1)
	}
	if err := os.WriteFile(*out, buf.Bytes(), 0o644); err != nil {
		fmt.Fprintln(os.Stderr, "error:", err)
		os.Exit(1)
	}
	fmt.Printf("written %s: %.0f KB\n", *out, float64(buf.Len())/1024)
}

func dirOf(p string) string {
	if i := strings.LastIndex(p, "/"); i >= 0 {
		return p[:i]
	}
	return "."
}

func hashN(id, n int) int {
	x := uint32(id)*2654435761 + 12345
	x ^= x >> 15
	return int(x % uint32(n))
}

// what the walls are made of, from the tags; in Vatican City and Rome the default is the warm stone and plaster of the city
func wallMat(tags func(string) string, id int) uint8 {
	m := strings.ToLower(tags("building:material") + " " + tags("building:facade:material") + " " + tags("building:colour") + " " + tags("colour"))
	switch {
	case strings.Contains(m, "brick"):
		return wBrick
	case strings.Contains(m, "concrete") || strings.Contains(m, "glass") || strings.Contains(m, "grey") || strings.Contains(m, "gray"):
		return wConcrete
	case strings.Contains(m, "white"):
		return wPlaster
	case strings.Contains(m, "stone") || strings.Contains(m, "travertine") || strings.Contains(m, "sandstone"):
		return wSandstone
	case strings.Contains(m, "yellow") || strings.Contains(m, "orange") || strings.Contains(m, "ochre"):
		return wOchre
	}
	switch tags("building") {
	case "church", "cathedral", "chapel", "basilica", "public", "civic", "government", "museum":
		return wSandstone
	}
	return []uint8{wSandstone, wOchre, wPlaster, wSandstone, wStoneBrick}[hashN(id, 5)]
}

func roofMat(tags func(string) string, id int) uint8 {
	m := strings.ToLower(tags("roof:material") + " " + tags("roof:colour"))
	switch {
	case strings.Contains(m, "copper"):
		return rCopper
	case strings.Contains(m, "slate") || strings.Contains(m, "lead") || strings.Contains(m, "metal") || strings.Contains(m, "grey") || strings.Contains(m, "gray"):
		return rSlate
	case strings.Contains(m, "stone") || strings.Contains(m, "concrete") || strings.Contains(m, "gravel"):
		return rStone
	case strings.Contains(m, "tile") || strings.Contains(m, "red") || strings.Contains(m, "terracotta"):
		return rTerracotta
	}
	switch tags("roof:shape") {
	case "dome", "onion":
		return rCopper
	case "flat", "":
		if tags("roof:shape") == "flat" {
			return rStone
		}
	}
	return []uint8{rTerracotta, rTerracotta, rSlate, rTerracotta}[hashN(id, 4)]
}
