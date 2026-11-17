// mctiles builds the world of minecraft.flowsearch.org in squares of 500 m, when somebody walks there.
//
//	go build -o mctiles ./tools/mctiles/main.go && ./mctiles -dir /media/data/tiles-data -addr 127.0.0.1:8858
//
// POST /tiles/3d/load with {"x":0,"z":0} answers with the square (x, z count 500 m squares from St. Peter's, z grows to the south).
// A square that is not on the disk yet is made first: Postpass (OpenStreetMap by Geofabrik) gives the buildings, roads, water, parks and trees,
// the elevation tiles on AWS give the height of the ground. The finished square is kept in the folder, so every other visitor gets it from the disk.
//
// Data: (c) OpenStreetMap contributors (ODbL); elevation: Mapzen Terrain Tiles on AWS (SRTM, NASA and others, public data).
package main

import (
	"archive/zip"
	"syscall"
	"bufio"
	"bytes"
	"compress/gzip"
	"compress/zlib"
	"encoding/binary"
	"encoding/json"
	"flag"
	"fmt"
	"image"
	_ "image/jpeg"
	"image/png"
	"io"
	"log"
	"math"
	"net"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

const (
	postpass = "https://postpass.geofabrik.de/api/interpreter"
	tile     = 500 // metres (blocks) per square
	pad      = 1   // the square is stored with one block of its neighbours around it
	P        = tile + 2*pad
	work     = 64 // extra blocks around the square while it is made, so roofs and roads cross the borders properly
	G        = tile + 2*work
	refLon   = 12.4534 // the place at world (0, 0): St. Peter's Basilica
	refLat   = 41.9029
	mLat     = 110540.0
	maxRing  = 40000 // squares away from the middle in every direction (20 million blocks; the whole Earth and the limit of Minecraft's world)
)

var mLon = 111320 * math.Cos(refLat*math.Pi/180)

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

// ---- Postpass ----

type feat struct {
	Geometry struct {
		Type        string          `json:"type"`
		Coordinates json.RawMessage `json:"coordinates"`
	} `json:"geometry"`
	Properties map[string]any `json:"properties"`
}

var (
	ppMu   sync.Mutex
	ppLast time.Time
)

// one question at a time to Postpass, and a pause between two: it is a free service of Geofabrik
func ask(sql string) ([]feat, error) {
	var last error
	for try := 0; try < 3; try++ {
		ppMu.Lock()
		if d := 120*time.Millisecond - time.Since(ppLast); d > 0 { // a short pause between two questions
			time.Sleep(d)
		}
		ppLast = time.Now()
		ppMu.Unlock()
		req, _ := http.NewRequest("POST", postpass, strings.NewReader("data="+url.QueryEscape(sql)))
		req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
		req.Header.Set("User-Agent", "flowsearch-mctiles/1.0")
		resp, err := (&http.Client{Timeout: 100 * time.Second}).Do(req)
		if err != nil {
			last = err
			time.Sleep(time.Duration(try+1) * 2 * time.Second)
			continue
		}
		b, _ := io.ReadAll(io.LimitReader(resp.Body, 256<<20))
		resp.Body.Close()
		if resp.StatusCode != 200 {
			last = fmt.Errorf("postpass %d: %.160s", resp.StatusCode, b)
			time.Sleep(time.Duration(try+1) * 3 * time.Second)
			continue
		}
		var fc struct {
			Features []feat `json:"features"`
		}
		if err := json.Unmarshal(b, &fc); err != nil {
			last = err
			continue
		}
		return fc.Features, nil
	}
	return nil, last
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

// ---- the grid of one square (with its working margin) ----

type grid struct {
	lon0, lat0   float64 // the north-west corner
	ground       []uint16
	surf         []uint8
	wallH, roofH []uint8
	wallM, roofM []uint8
}

func (g *grid) xy(p pt) (float64, float64) { return (p[0] - g.lon0) * mLon, (g.lat0 - p[1]) * mLat }
func (g *grid) idx(x, z int) int           { return z*G + x }

func inRing(r [][2]float64, x, z float64) bool {
	in := false
	for i, j := 0, len(r)-1; i < len(r); i, j = i+1, i {
		if (r[i][1] > z) != (r[j][1] > z) && x < (r[j][0]-r[i][0])*(z-r[i][1])/(r[j][1]-r[i][1])+r[i][0] {
			in = !in
		}
	}
	return in
}

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
			in = !in
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

func (g *grid) fill(rings [][][2]float64, fn func(x, z int)) {
	x0, z0, x1, z1 := bounds(rings)
	for z := int(math.Max(0, math.Floor(z0))); z <= int(math.Min(G-1, math.Ceil(z1))); z++ {
		for x := int(math.Max(0, math.Floor(x0))); x <= int(math.Min(G-1, math.Ceil(x1))); x++ {
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
				if x < 0 || z < 0 || x >= G || z >= G {
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
	v    []float32 // half the memory of float64; a tenth of a metre is plenty
}

func fetchTile(z, x, y int) (*tileImg, error) {
	u := fmt.Sprintf("https://s3.amazonaws.com/elevation-tiles-prod/terrarium/%d/%d/%d.png", z, x, y)
	var last error
	for try := 0; try < 3; try++ {
		resp, err := (&http.Client{Timeout: 40 * time.Second}).Get(u)
		if err != nil {
			last = err
			time.Sleep(time.Second)
			continue
		}
		if resp.StatusCode == 404 { // no data here (the poles)
			resp.Body.Close()
			return nil, nil
		}
		if resp.StatusCode != 200 {
			resp.Body.Close()
			last = fmt.Errorf("elevation %d", resp.StatusCode)
			time.Sleep(time.Second)
			continue
		}
		im, err := png.Decode(resp.Body)
		resp.Body.Close()
		if err != nil {
			last = err
			continue
		}
		b := im.Bounds()
		t := &tileImg{w: b.Dx(), h: b.Dy(), v: make([]float32, b.Dx()*b.Dy())}
		for yy := 0; yy < t.h; yy++ {
			for xx := 0; xx < t.w; xx++ {
				r, g, bl, _ := im.At(b.Min.X+xx, b.Min.Y+yy).RGBA()
				t.v[yy*t.w+xx] = float32(float64(r>>8)*256 + float64(g>>8) + float64(bl>>8)/256 - 32768)
			}
		}
		return t, nil
	}
	return nil, last
}

// An elevation picture covers about 1.2 km, so the squares next to each other use the same ones: the last 48 stay in memory, and when
// two squares ask for the same picture at once it is loaded once.
var (
	elevMu    sync.Mutex
	elevCache = map[[2]int]*elevEntry{}
	elevOrder [][2]int
)

type elevEntry struct {
	done chan struct{}
	img  *tileImg
	err  error
}

func elevTile(z, x, y int) (*tileImg, error) {
	k := [2]int{x, y}
	elevMu.Lock()
	if e, ok := elevCache[k]; ok {
		elevMu.Unlock()
		<-e.done
		return e.img, e.err
	}
	e := &elevEntry{done: make(chan struct{})}
	elevCache[k] = e
	elevOrder = append(elevOrder, k)
	if len(elevOrder) > 8 { // a few only: the pictures are big, and a square needs two to four
		delete(elevCache, elevOrder[0])
		elevOrder = elevOrder[1:]
	}
	elevMu.Unlock()
	e.img, e.err = fetchTile(z, x, y)
	if e.err != nil { // a failed picture is not kept
		elevMu.Lock()
		delete(elevCache, k)
		elevMu.Unlock()
	}
	close(e.done)
	return e.img, e.err
}

// the height in Minecraft metres above the sea: as it is up to 180 m, then the mountains are pressed so that they fit in the world
func gameHeight(e float64) uint16 {
	if e <= 0 {
		return 0
	}
	if e > 180 {
		e = 180 + (e-180)/14
	}
	return uint16(math.Min(250, math.Round(e)))
}

// ---- Berlin: the terrain model of the city with a measurement every metre (ATKIS DGM1, Land Berlin, open data, no key) ----
//
// The city publishes it in files of 2 x 2 km (zipped text, "x y height" for every metre, ETRS89 / UTM 33N). A file is downloaded once, turned into a
// small binary file in dgmDir (2000 x 2000 heights in decimetres) and kept. Squares inside Berlin use it instead of the elevation pictures, which
// have a measurement only every 3 to 5 metres (and are partly SRTM, which sees the trees). Outside Berlin nothing changes.

const dgmDir = "/media/data/dgm-berlin"

// A file is not held in memory: it is mapped from the disk (the system keeps what it needs in its own cache and gives it back when memory is short),
// and let go of when the square is made. 2000 x 2000 heights in decimetres above sea level, row 0 = the southern edge, -32768 = no data.
type dgmTile struct{ data []byte }

func (t *dgmTile) at(i int) int16 { return int16(binary.LittleEndian.Uint16(t.data[i*2:])) }
func (t *dgmTile) close() {
	if t.data != nil {
		syscall.Munmap(t.data)
		t.data = nil
	}
}

var (
	dgmMu    sync.Mutex
	dgmLocks = map[[2]int]*sync.Mutex{} // one download of a file at a time
)

// ETRS89 / UTM zone 33N (metres) from longitude and latitude, with the series of Krueger (GRS80)
func utm33(lon, lat float64) (e, n float64) {
	const a, f, k0 = 6378137.0, 1 / 298.257222101, 0.9996
	n1 := f / (2 - f)
	A := a / (1 + n1) * (1 + n1*n1/4 + n1*n1*n1*n1/64)
	alpha := [3]float64{n1/2 - 2*n1*n1/3 + 5*n1*n1*n1/16, 13*n1*n1/48 - 3*n1*n1*n1/5, 61 * n1 * n1 * n1 / 240}
	ecc := math.Sqrt(f * (2 - f))
	phi, dl := lat*math.Pi/180, (lon-15)*math.Pi/180
	t := math.Sinh(math.Atanh(math.Sin(phi)) - ecc*math.Atanh(ecc*math.Sin(phi)))
	xi, eta := math.Atan(t/math.Cos(dl)), math.Atanh(math.Sin(dl)/math.Sqrt(1+t*t))
	xs, es := xi, eta
	for j := 1; j <= 3; j++ {
		xs += alpha[j-1] * math.Sin(2*float64(j)*xi) * math.Cosh(2*float64(j)*eta)
		es += alpha[j-1] * math.Cos(2*float64(j)*xi) * math.Sinh(2*float64(j)*eta)
	}
	return 500000 + k0*A*es, k0 * A * xs
}

func inBerlin(lon, lat float64) bool { return lon > 13.05 && lon < 13.80 && lat > 52.30 && lat < 52.70 }

func dgmKey(e, n float64) [2]int { // the file of a place: the south-west corner in km, always an even number
	return [2]int{int(math.Floor(e/2000)) * 2, int(math.Floor(n/2000)) * 2}
}

// the file of a part of Berlin, mapped from the disk (made first when it is not there yet); nil when the city has none for this place
func dgmLoad(k [2]int) (*dgmTile, error) {
	dgmMu.Lock()
	l, ok := dgmLocks[k]
	if !ok {
		l = &sync.Mutex{}
		dgmLocks[k] = l
	}
	dgmMu.Unlock()
	l.Lock() // two squares that need the same file: it is made once
	defer l.Unlock()
	path := filepath.Join(dgmDir, fmt.Sprintf("%d_%d.i16", k[0], k[1]))
	if _, err := os.Stat(path); err != nil {
		if _, err2 := os.Stat(path + ".none"); err2 == nil {
			return nil, nil // the city has no file here (outside of Berlin)
		}
		if err := dgmDownload(k, path); err != nil {
			return nil, err
		}
		if _, err := os.Stat(path); err != nil {
			return nil, nil
		}
	}
	f, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	fi, err := f.Stat()
	if err != nil || fi.Size() != 2000*2000*2 {
		return nil, fmt.Errorf("dgm file %s has the wrong size", path)
	}
	data, err := syscall.Mmap(int(f.Fd()), 0, int(fi.Size()), syscall.PROT_READ, syscall.MAP_SHARED)
	if err != nil {
		return nil, err
	}
	now := time.Now()
	os.Chtimes(path, now, now) // used now
	return &dgmTile{data: data}, nil
}

// reads a number like 371034.500 in a line from pos, moves pos behind it
func dgmNum(b []byte, pos *int) (float64, bool) {
	i := *pos
	for i < len(b) && (b[i] == ' ' || b[i] == '\t') {
		i++
	}
	start, neg := i, false
	if i < len(b) && b[i] == '-' {
		neg = true
		i++
	}
	var v float64
	digits := 0
	for i < len(b) && b[i] >= '0' && b[i] <= '9' {
		v = v*10 + float64(b[i]-'0')
		i++
		digits++
	}
	if i < len(b) && b[i] == '.' {
		i++
		scale := 0.1
		for i < len(b) && b[i] >= '0' && b[i] <= '9' {
			v += float64(b[i]-'0') * scale
			scale /= 10
			i++
			digits++
		}
	}
	*pos = i
	if digits == 0 || i == start {
		return 0, false
	}
	if neg {
		v = -v
	}
	return v, true
}

// downloads a file of the city to the disk (not into memory), and writes the small binary file straight into a mapped file on the disk
func dgmDownload(k [2]int, path string) error {
	u := fmt.Sprintf("https://gdi.berlin.de/data/dgm1/atom/DGM1_%d_%d.zip", k[0], k[1])
	resp, err := (&http.Client{Timeout: 10 * time.Minute}).Get(u)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	os.MkdirAll(dgmDir, 0o755)
	if resp.StatusCode == 404 {
		return os.WriteFile(path+".none", nil, 0o644)
	}
	if resp.StatusCode != 200 {
		return fmt.Errorf("dgm %d", resp.StatusCode)
	}
	zf, err := os.CreateTemp(dgmDir, "download-*.zip")
	if err != nil {
		return err
	}
	defer os.Remove(zf.Name())
	size, err := io.Copy(zf, resp.Body)
	zf.Close()
	if err != nil {
		return err
	}
	zr, err := zip.OpenReader(zf.Name())
	if err != nil || len(zr.File) == 0 {
		return fmt.Errorf("dgm zip: %v", err)
	}
	defer zr.Close()
	in, err := zr.File[0].Open()
	if err != nil {
		return err
	}
	defer in.Close()
	tmp := path + ".tmp"
	of, err := os.Create(tmp)
	if err != nil {
		return err
	}
	if err := of.Truncate(2000 * 2000 * 2); err != nil {
		of.Close()
		return err
	}
	out, err := syscall.Mmap(int(of.Fd()), 0, 2000*2000*2, syscall.PROT_READ|syscall.PROT_WRITE, syscall.MAP_SHARED)
	if err != nil {
		of.Close()
		return err
	}
	for i := 0; i < 2000*2000; i++ {
		out[i*2], out[i*2+1] = 0x00, 0x80 // -32768: no data
	}
	rd := bufio.NewReaderSize(in, 1<<20)
	for {
		line, rerr := rd.ReadSlice('\n')
		if len(line) > 8 {
			p := 0
			x, ok1 := dgmNum(line, &p)
			y, ok2 := dgmNum(line, &p)
			z, ok3 := dgmNum(line, &p)
			col, row := int(x)-k[0]*1000, int(y)-k[1]*1000
			if ok1 && ok2 && ok3 && col >= 0 && col < 2000 && row >= 0 && row < 2000 {
				binary.LittleEndian.PutUint16(out[(row*2000+col)*2:], uint16(int16(math.Round(z*10))))
			}
		}
		if rerr == bufio.ErrBufferFull {
			continue // a line longer than the buffer: cannot be one of ours
		}
		if rerr != nil {
			break
		}
	}
	syscall.Munmap(out)
	of.Close()
	if err := os.Rename(tmp, path); err != nil {
		return err
	}
	log.Printf("dgm: made %s (%d MB zip)", filepath.Base(path), size>>20)
	go dgmTrim()
	return nil
}

// the height at a place (metres), from the four measurements around it; false when there is no measurement
func dgmHeight(tiles map[[2]int]*dgmTile, e, n float64) (float64, bool) {
	fe, fn := e-0.5, n-0.5 // the measurements are in the middle of their metre
	e0, n0 := math.Floor(fe), math.Floor(fn)
	ae, an := fe-e0, fn-n0
	var v [4]float64
	for i := 0; i < 4; i++ {
		ee, nn := e0+float64(i&1), n0+float64(i>>1)
		t := tiles[dgmKey(ee, nn)]
		if t == nil {
			return 0, false
		}
		k := dgmKey(ee, nn)
		h := t.at((int(nn)-k[1]*1000)*2000 + (int(ee) - k[0]*1000))
		if h == -32768 {
			return 0, false
		}
		v[i] = float64(h) / 10
	}
	return v[0]*(1-ae)*(1-an) + v[1]*ae*(1-an) + v[2]*(1-ae)*an + v[3]*ae*an, true
}

// ---- ESA WorldCover: what covers the ground, every 10 m, worldwide, no key (grass, trees, water, bare ground, built-up...) ----
//
// The data is a big picture file per 3 x 3 degrees (a "cloud optimised" TIFF in blocks of 1024 x 1024, each block zipped). Only the blocks around a
// square are downloaded (a few hundred KB), kept on the disk in wcDir, and let go of from memory when the square is made.

const (
	wcURL = "https://esa-worldcover.s3.eu-central-1.amazonaws.com/v200/2021/map/ESA_WorldCover_10m_2021_v200_%s_Map.tif"
	wcDir = "/media/data/worldcover"
)

type wcHead struct {
	offs, cnts []uint32
	across     int
}

var (
	wcMu    sync.Mutex
	wcHeads = map[string]*wcHead{}
	wcLocks = map[string]*sync.Mutex{}
)

func wcName(lat, lon float64) (string, float64, float64) {
	lat0, lon0 := math.Floor(lat/3)*3, math.Floor(lon/3)*3
	ns, ew := 'N', 'E'
	la, lo := int(lat0), int(lon0)
	if la < 0 {
		ns, la = 'S', -la
	}
	if lo < 0 {
		ew, lo = 'W', -lo
	}
	return fmt.Sprintf("%c%02d%c%03d", ns, la, ew, lo), lat0, lon0
}

func rangeGet(u string, from, to int64) ([]byte, int, error) {
	req, _ := http.NewRequest("GET", u, nil)
	req.Header.Set("Range", fmt.Sprintf("bytes=%d-%d", from, to))
	req.Header.Set("User-Agent", "flowsearch-mctiles/1.0")
	resp, err := (&http.Client{Timeout: 90 * time.Second}).Do(req)
	if err != nil {
		return nil, 0, err
	}
	defer resp.Body.Close()
	b, err := io.ReadAll(io.LimitReader(resp.Body, 64<<20))
	return b, resp.StatusCode, err
}

// the list of the blocks of a file (where each one is and how long); nil when there is no file (the open sea)
func wcLoadHead(name string) (*wcHead, error) {
	wcMu.Lock()
	l, ok := wcLocks[name]
	if !ok {
		l = &sync.Mutex{}
		wcLocks[name] = l
	}
	wcMu.Unlock()
	l.Lock()
	defer l.Unlock()
	wcMu.Lock()
	if h, ok := wcHeads[name]; ok {
		wcMu.Unlock()
		return h, nil
	}
	wcMu.Unlock()
	os.MkdirAll(filepath.Join(wcDir, name), 0o755)
	hp := filepath.Join(wcDir, name+".hdr")
	raw, err := os.ReadFile(hp)
	if err != nil {
		if _, e2 := os.Stat(hp + ".none"); e2 == nil {
			return nil, nil
		}
		b, code, err := rangeGet(fmt.Sprintf(wcURL, name), 0, 98303)
		if err != nil {
			return nil, err
		}
		if code == 404 || code == 403 {
			os.WriteFile(hp+".none", nil, 0o644)
			return nil, nil
		}
		if code != 206 && code != 200 {
			return nil, fmt.Errorf("worldcover %d", code)
		}
		raw = b
		os.WriteFile(hp, raw, 0o644)
	}
	if len(raw) < 16 || string(raw[:2]) != "II" {
		return nil, fmt.Errorf("worldcover: not a TIFF")
	}
	ifd := int(binary.LittleEndian.Uint32(raw[4:]))
	n := int(binary.LittleEndian.Uint16(raw[ifd:]))
	h := &wcHead{}
	width, tw := 0, 1024
	for i := 0; i < n; i++ {
		e := raw[ifd+2+i*12:]
		tag, typ, cnt, val := binary.LittleEndian.Uint16(e), binary.LittleEndian.Uint16(e[2:]), int(binary.LittleEndian.Uint32(e[4:])), binary.LittleEndian.Uint32(e[8:])
		switch tag {
		case 256:
			width = int(val & 0xFFFF)
			if typ == 4 {
				width = int(val)
			}
		case 322:
			tw = int(val & 0xFFFF)
		case 324, 325:
			if int(val)+cnt*4 > len(raw) {
				return nil, fmt.Errorf("worldcover: block list outside of the header")
			}
			a := make([]uint32, cnt)
			for k := range a {
				a[k] = binary.LittleEndian.Uint32(raw[int(val)+k*4:])
			}
			if tag == 324 {
				h.offs = a
			} else {
				h.cnts = a
			}
		}
	}
	if len(h.offs) == 0 || len(h.offs) != len(h.cnts) || width == 0 {
		return nil, fmt.Errorf("worldcover: the header is not what was expected")
	}
	h.across = (width + tw - 1) / tw
	wcMu.Lock()
	wcHeads[name] = h
	wcMu.Unlock()
	return h, nil
}

// one block of a file, unzipped (1024 x 1024 classes)
func wcBlock(name string, h *wcHead, idx int) ([]byte, error) {
	p := filepath.Join(wcDir, name, fmt.Sprintf("%d.z", idx))
	z, err := os.ReadFile(p)
	if err != nil {
		b, code, err := rangeGet(fmt.Sprintf(wcURL, name), int64(h.offs[idx]), int64(h.offs[idx])+int64(h.cnts[idx])-1)
		if err != nil {
			return nil, err
		}
		if code != 206 && code != 200 {
			return nil, fmt.Errorf("worldcover block %d", code)
		}
		z = b
		os.WriteFile(p, z, 0o644)
	}
	r, err := zlib.NewReader(bytes.NewReader(z))
	if err != nil {
		os.Remove(p)
		return nil, err
	}
	defer r.Close()
	out := make([]byte, 1024*1024)
	if _, err := io.ReadFull(r, out); err != nil && err != io.ErrUnexpectedEOF {
		os.Remove(p)
		return nil, err
	}
	return out, nil
}

// what covers the ground at a place: 10 trees, 20 shrubs, 30 grass, 40 fields, 50 built-up, 60 bare, 70 snow and ice, 80 water, 90 and 95 wetland, 0 unknown
type wcSampler struct {
	heads  map[string]*wcHead
	blocks map[string][]byte
}

func newWC() *wcSampler { return &wcSampler{heads: map[string]*wcHead{}, blocks: map[string][]byte{}} }

func (s *wcSampler) class(lat, lon float64) uint8 {
	name, lat0, lon0 := wcName(lat, lon)
	h, ok := s.heads[name]
	if !ok {
		var err error
		h, err = wcLoadHead(name)
		if err != nil {
			log.Printf("worldcover %s: %v", name, err)
		}
		s.heads[name] = h
	}
	if h == nil {
		return 0
	}
	col, row := int((lon-lon0)*12000), int((lat0+3-lat)*12000)
	if col < 0 || row < 0 || col >= 36000 || row >= 36000 {
		return 0
	}
	idx := (row/1024)*h.across + col/1024
	if idx >= len(h.offs) {
		return 0
	}
	k := fmt.Sprintf("%s/%d", name, idx)
	b, ok := s.blocks[k]
	if !ok {
		var err error
		b, err = wcBlock(name, h, idx)
		if err != nil {
			log.Printf("worldcover %s block %d: %v", name, idx, err)
			b = nil
		}
		s.blocks[k] = b
	}
	if b == nil {
		return 0
	}
	return b[(row%1024)*1024+col%1024]
}

// ---- Microsoft Building Footprints: the outlines of the buildings of the whole world, found in pictures by a computer (open data, no key) ----
//
// Microsoft publishes them as one big file per area of about 76 km (a "quadkey" of zoom 9). A file is downloaded once and cut into small files per
// 5 km square on the disk (msDir), so a square of the game only reads the few small ones around it.

const (
	msDir   = "/media/data/ms-buildings"
	msLinks = "https://minedbuildings.z5.web.core.windows.net/global-buildings/dataset-links.csv"
)

type msB struct {
	ring         []pt
	height, conf float64
}

var (
	msMu    sync.Mutex
	msLocks = map[string]*sync.Mutex{}
)

func tileXY(lat, lon float64, z int) (int, int) {
	n := float64(int(1) << z)
	x := int((lon + 180) / 360 * n)
	y := int((1 - math.Asinh(math.Tan(lat*math.Pi/180))/math.Pi) / 2 * n)
	return x, y
}

func tileCenter(x, y, z int) (lat, lon float64) {
	n := float64(int(1) << z)
	lon = (float64(x)+0.5)/n*360 - 180
	lat = math.Atan(math.Sinh(math.Pi*(1-2*(float64(y)+0.5)/n))) * 180 / math.Pi
	return
}

func quadkey(x, y, z int) string {
	var b []byte
	for i := z; i > 0; i-- {
		d := byte('0')
		m := 1 << (i - 1)
		if x&m != 0 {
			d++
		}
		if y&m != 0 {
			d += 2
		}
		b = append(b, d)
	}
	return string(b)
}

// the first, slow way (one download, a general JSON reader); msEnsure below replaced it
func msEnsureSlow(qk string) error {
	msMu.Lock()
	l, ok := msLocks[qk]
	if !ok {
		l = &sync.Mutex{}
		msLocks[qk] = l
	}
	msMu.Unlock()
	l.Lock()
	defer l.Unlock()
	dir := filepath.Join(msDir, "z9v2", qk)
	if _, err := os.Stat(dir + ".done"); err == nil {
		return nil
	}
	os.MkdirAll(msDir, 0o755)
	lp := filepath.Join(msDir, "links.csv")
	if fi, err := os.Stat(lp); err != nil || time.Since(fi.ModTime()) > 60*24*time.Hour {
		resp, err := (&http.Client{Timeout: 3 * time.Minute}).Get(msLinks)
		if err != nil {
			return err
		}
		f, err := os.Create(lp + ".tmp")
		if err != nil {
			resp.Body.Close()
			return err
		}
		_, err = io.Copy(f, resp.Body)
		resp.Body.Close()
		f.Close()
		if err != nil {
			return err
		}
		os.Rename(lp+".tmp", lp)
	}
	var urls []string
	lf, err := os.Open(lp)
	if err != nil {
		return err
	}
	sc := bufio.NewScanner(lf)
	sc.Buffer(make([]byte, 1<<20), 1<<20)
	for sc.Scan() {
		f := strings.Split(sc.Text(), ",")
		if len(f) >= 3 && f[1] == qk {
			urls = append(urls, f[2])
		}
	}
	lf.Close()
	os.RemoveAll(dir) // a run that was cut off is made again from the start
	os.MkdirAll(dir, 0o755)
	files := map[[2]int]*bufio.Writer{}
	handles := map[[2]int]*os.File{}
	count := 0
	for _, u := range urls {
		resp, err := (&http.Client{Timeout: 20 * time.Minute}).Get(u)
		if err != nil {
			return err
		}
		if resp.StatusCode != 200 {
			resp.Body.Close()
			return fmt.Errorf("buildings %d", resp.StatusCode)
		}
		gz, err := gzip.NewReader(resp.Body)
		if err != nil {
			resp.Body.Close()
			return err
		}
		rd := bufio.NewReaderSize(gz, 1<<20)
		for {
			line, rerr := rd.ReadBytes('\n')
			if len(line) > 20 {
				var rec struct {
					Properties struct {
						Height     float64 `json:"height"`
						Confidence float64 `json:"confidence"`
					} `json:"properties"`
					Geometry struct {
						Type        string          `json:"type"`
						Coordinates json.RawMessage `json:"coordinates"`
					} `json:"geometry"`
				}
				if json.Unmarshal(line, &rec) == nil {
					var rings [][][2]float64
					switch rec.Geometry.Type {
					case "Polygon":
						var c [][][2]float64
						if json.Unmarshal(rec.Geometry.Coordinates, &c) == nil && len(c) > 0 {
							rings = append(rings, c[0])
						}
					case "MultiPolygon":
						var c [][][][2]float64
						if json.Unmarshal(rec.Geometry.Coordinates, &c) == nil {
							for _, p := range c {
								if len(p) > 0 {
									rings = append(rings, p[0])
								}
							}
						}
					}
					for _, r := range rings {
						if len(r) < 4 || len(r) > 60000 {
							continue
						}
						x, y := tileXY(r[0][1], r[0][0], 13)
						k := [2]int{x, y}
						w := files[k]
						if w == nil {
							f, err := os.OpenFile(filepath.Join(dir, fmt.Sprintf("%d_%d.bin", x, y)), os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o644)
							if err != nil {
								resp.Body.Close()
								return err
							}
							handles[k] = f
							w = bufio.NewWriterSize(f, 32<<10)
							files[k] = w
						}
						var hdr [10]byte
						binary.LittleEndian.PutUint16(hdr[0:], uint16(len(r)))
						binary.LittleEndian.PutUint32(hdr[2:], math.Float32bits(float32(rec.Properties.Height)))
						binary.LittleEndian.PutUint32(hdr[6:], math.Float32bits(float32(rec.Properties.Confidence)))
						w.Write(hdr[:])
						var pb [16]byte
						for _, p := range r {
							binary.LittleEndian.PutUint64(pb[0:], math.Float64bits(p[0]))
							binary.LittleEndian.PutUint64(pb[8:], math.Float64bits(p[1]))
							w.Write(pb[:])
						}
						count++
					}
				}
			}
			if rerr != nil {
				break
			}
		}
		resp.Body.Close()
	}
	for k, w := range files {
		w.Flush()
		handles[k].Close()
	}
	log.Printf("buildings: made the area %s (%d buildings)", qk, count)
	return os.WriteFile(dir+".done", nil, 0o644)
}

// ---- the fast way: the file comes in 8 pieces at the same time, and a small reader made for exactly these lines ----

type offsetWriter struct {
	f   *os.File
	off int64
}

func (w *offsetWriter) Write(p []byte) (int, error) {
	n, err := w.f.WriteAt(p, w.off)
	w.off += int64(n)
	return n, err
}

// downloads a file in parallel pieces (the server gives about 1 MB/s for each connection, so eight of them are eight times faster)
func msDownload(u, dst string) error {
	hr, err := (&http.Client{Timeout: 60 * time.Second}).Head(u)
	if err != nil {
		return err
	}
	hr.Body.Close()
	size := hr.ContentLength
	f, err := os.Create(dst)
	if err != nil {
		return err
	}
	defer f.Close()
	if size < 4<<20 { // small: one piece
		resp, err := (&http.Client{Timeout: 5 * time.Minute}).Get(u)
		if err != nil {
			return err
		}
		defer resp.Body.Close()
		_, err = io.Copy(f, resp.Body)
		return err
	}
	if err := f.Truncate(size); err != nil {
		return err
	}
	const parts = 8
	step := (size + parts - 1) / parts
	errs := make(chan error, parts)
	for i := int64(0); i < parts; i++ {
		go func(from int64) {
			to := from + step - 1
			if to >= size {
				to = size - 1
			}
			var last error
			for try := 0; try < 4; try++ {
				req, _ := http.NewRequest("GET", u, nil)
				req.Header.Set("Range", fmt.Sprintf("bytes=%d-%d", from, to))
				resp, err := (&http.Client{Timeout: 10 * time.Minute}).Do(req)
				if err != nil {
					last = err
					continue
				}
				if resp.StatusCode != 206 {
					resp.Body.Close()
					last = fmt.Errorf("buildings piece %d", resp.StatusCode)
					continue
				}
				_, err = io.Copy(&offsetWriter{f: f, off: from}, resp.Body)
				resp.Body.Close()
				if err == nil {
					errs <- nil
					return
				}
				last = err
			}
			errs <- last
		}(i * step)
	}
	var first error
	for i := 0; i < parts; i++ {
		if e := <-errs; e != nil && first == nil {
			first = e
		}
	}
	return first
}

// a number in a line from position p: skips what is before it, moves p behind it
func fastNum(b []byte, p *int) (float64, bool) {
	i := *p
	for i < len(b) && !(b[i] == '-' || (b[i] >= '0' && b[i] <= '9')) {
		i++
	}
	start := i
	if i < len(b) && b[i] == '-' {
		i++
	}
	var v float64
	for i < len(b) && b[i] >= '0' && b[i] <= '9' {
		v = v*10 + float64(b[i]-'0')
		i++
	}
	if i < len(b) && b[i] == '.' {
		i++
		sc := 0.1
		for i < len(b) && b[i] >= '0' && b[i] <= '9' {
			v += float64(b[i]-'0') * sc
			sc /= 10
			i++
		}
	}
	if i < len(b) && (b[i] == 'e' || b[i] == 'E') { // rare: a number like 1e-05
		j := i + 1
		for j < len(b) && (b[j] == '-' || b[j] == '+' || (b[j] >= '0' && b[j] <= '9')) {
			j++
		}
		f, err := strconv.ParseFloat(string(b[start:j]), 64)
		*p = j
		return f, err == nil
	}
	if start < len(b) && b[start] == '-' {
		v = -v
	}
	*p = i
	return v, i > start
}

// one line: {"type": "Feature", "properties": { "height": 3.9, "confidence": 0.98},"geometry": {"type": "Polygon","coordinates": [[[lon, lat], ...]]}}
func msParseLine(b []byte) (h, c float64, ring []pt, ok bool) {
	hi := bytes.Index(b, []byte(`"height":`))
	ci := bytes.Index(b, []byte(`"confidence":`))
	co := bytes.Index(b, []byte(`"coordinates":`))
	if hi < 0 || ci < 0 || co < 0 {
		return 0, 0, nil, false
	}
	p := hi + 9
	h, _ = fastNum(b, &p)
	p = ci + 13
	c, _ = fastNum(b, &p)
	p = co + 14
	for {
		// the next pair: [lon, lat]; the first ring ends where a "]" follows the pair and no "," + "[" comes after it
		for p < len(b) && b[p] != '-' && !(b[p] >= '0' && b[p] <= '9') {
			if b[p] == '}' {
				return h, c, ring, len(ring) >= 4
			}
			p++
		}
		if p >= len(b) {
			break
		}
		lo, ok1 := fastNum(b, &p)
		la, ok2 := fastNum(b, &p)
		if !ok1 || !ok2 {
			break
		}
		ring = append(ring, pt{lo, la})
		// after the pair: "]" and then either "," (the next pair), or "]" (the ring ends)
		q := p
		for q < len(b) && (b[q] == ' ' || b[q] == ']') {
			q++
		}
		if q-p >= 2 && b[p] == ']' && b[p+1] == ']' { // "]]": the end of the ring
			return h, c, ring, len(ring) >= 4
		}
	}
	return h, c, ring, len(ring) >= 4
}

type msWriter struct {
	dir     string
	files   map[[2]int]*bufio.Writer
	handles map[[2]int]*os.File
	count   int
}

func (m *msWriter) add(r []pt, h, c float64) error {
	x, y := tileXY(r[0][1], r[0][0], 13)
	k := [2]int{x, y}
	w := m.files[k]
	if w == nil {
		f, err := os.OpenFile(filepath.Join(m.dir, fmt.Sprintf("%d_%d.bin", x, y)), os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o644)
		if err != nil {
			return err
		}
		m.handles[k] = f
		w = bufio.NewWriterSize(f, 32<<10)
		m.files[k] = w
	}
	// small: 12 bytes, then 4 bytes for every other corner (the distance from the corner before, in 1e-7 degrees = 1 cm, up to 360 m)
	lon0, lat0 := int32(math.Round(r[0][0]*1e7)), int32(math.Round(r[0][1]*1e7))
	deltas := make([]int16, 0, (len(r)-1)*2)
	pl, pa := lon0, lat0
	for _, p := range r[1:] {
		lo, la := int32(math.Round(p[0]*1e7)), int32(math.Round(p[1]*1e7))
		dl, da := lo-pl, la-pa
		if dl > 32767 || dl < -32768 || da > 32767 || da < -32768 {
			return nil // a corner more than 360 m from the one before: not a house
		}
		deltas = append(deltas, int16(dl), int16(da))
		pl, pa = lo, la
	}
	var hdr [12]byte
	binary.LittleEndian.PutUint16(hdr[0:], uint16(len(r)))
	if h > 0 {
		hdr[2] = byte(math.Min(255, math.Round(h)))
	}
	hdr[3] = 255 // not known
	if c >= 0 {
		hdr[3] = byte(math.Min(100, math.Round(c*100)))
	}
	binary.LittleEndian.PutUint32(hdr[4:], uint32(lon0))
	binary.LittleEndian.PutUint32(hdr[8:], uint32(lat0))
	w.Write(hdr[:])
	var pb [2]byte
	for _, d := range deltas {
		binary.LittleEndian.PutUint16(pb[:], uint16(d))
		w.Write(pb[:])
	}
	m.count++
	return nil
}

func (m *msWriter) close() {
	for k, w := range m.files {
		w.Flush()
		m.handles[k].Close()
	}
}

// the file of one area (zoom 9), cut into the small files of zoom 13
func msEnsure(qk string) error {
	msMu.Lock()
	l, ok := msLocks[qk]
	if !ok {
		l = &sync.Mutex{}
		msLocks[qk] = l
	}
	msMu.Unlock()
	l.Lock()
	defer l.Unlock()
	dir := filepath.Join(msDir, "z9v2", qk)
	if _, err := os.Stat(dir + ".done"); err == nil {
		return nil
	}
	started := time.Now()
	os.MkdirAll(msDir, 0o755)
	lp := filepath.Join(msDir, "links.csv")
	if fi, err := os.Stat(lp); err != nil || time.Since(fi.ModTime()) > 60*24*time.Hour {
		resp, err := (&http.Client{Timeout: 3 * time.Minute}).Get(msLinks)
		if err != nil {
			return err
		}
		f, err := os.Create(lp + ".tmp")
		if err != nil {
			resp.Body.Close()
			return err
		}
		_, err = io.Copy(f, resp.Body)
		resp.Body.Close()
		f.Close()
		if err != nil {
			return err
		}
		os.Rename(lp+".tmp", lp)
	}
	var urls []string
	lf, err := os.Open(lp)
	if err != nil {
		return err
	}
	sc := bufio.NewScanner(lf)
	sc.Buffer(make([]byte, 1<<20), 1<<20)
	for sc.Scan() {
		f := strings.Split(sc.Text(), ",")
		if len(f) >= 3 && f[1] == qk {
			urls = append(urls, f[2])
		}
	}
	lf.Close()
	os.RemoveAll(dir) // a run that was cut off is made again from the start
	os.MkdirAll(dir, 0o755)
	mw := &msWriter{dir: dir, files: map[[2]int]*bufio.Writer{}, handles: map[[2]int]*os.File{}}
	for _, u := range urls {
		tmp := filepath.Join(msDir, qk+".download")
		if err := msDownload(u, tmp); err != nil {
			mw.close()
			return err
		}
		f, err := os.Open(tmp)
		if err != nil {
			mw.close()
			return err
		}
		gz, err := gzip.NewReader(f)
		if err != nil {
			f.Close()
			mw.close()
			return err
		}
		rd := bufio.NewReaderSize(gz, 1<<20)
		for {
			line, rerr := rd.ReadSlice('\n')
			if len(line) > 20 {
				if h, c, ring, ok := msParseLine(line); ok && len(ring) <= 60000 {
					if err := mw.add(ring, h, c); err != nil {
						f.Close()
						mw.close()
						return err
					}
				}
			}
			if rerr == bufio.ErrBufferFull {
				continue
			}
			if rerr != nil {
				break
			}
		}
		f.Close()
		os.Remove(tmp)
	}
	mw.close()
	log.Printf("buildings: made the area %s (%d buildings, %.0f s)", qk, mw.count, time.Since(started).Seconds())
	defer func() { go msTrim() }() // after the marker is written: stay under the disk limit
	return os.WriteFile(dir+".done", nil, 0o644)
}

// ---- the disk limit: when the files of an area or a terrain file together are bigger than the limit, the ones not used for the longest time are deleted
// (they are made again from the internet when somebody comes back) ----

const (
	msLimit  = 1 << 30   // 1 GB for the building areas
	dgmLimit = 600 << 20 // 600 MB for the Berlin terrain files
)

func dirSize(dir string) int64 {
	var n int64
	filepath.WalkDir(dir, func(p string, d os.DirEntry, err error) error {
		if err == nil && !d.IsDir() {
			if fi, e := d.Info(); e == nil {
				n += fi.Size()
			}
		}
		return nil
	})
	return n
}

func msTouch(qk string) {
	now := time.Now()
	os.Chtimes(filepath.Join(msDir, "z9v2", qk+".done"), now, now)
}

var trimMu sync.Mutex

// the areas: the oldest ones go first (the time of the marker file is the time of the last use)
func msTrim() {
	trimMu.Lock()
	defer trimMu.Unlock()
	root := filepath.Join(msDir, "z9v2")
	ents, err := os.ReadDir(root)
	if err != nil {
		return
	}
	type area struct {
		qk   string
		size int64
		at   time.Time
	}
	var as []area
	var total int64
	for _, e := range ents {
		if e.IsDir() || !strings.HasSuffix(e.Name(), ".done") {
			continue
		}
		qk := strings.TrimSuffix(e.Name(), ".done")
		fi, err := e.Info()
		if err != nil {
			continue
		}
		sz := dirSize(filepath.Join(root, qk))
		as = append(as, area{qk, sz, fi.ModTime()})
		total += sz
	}
	if total <= msLimit {
		return
	}
	sort.Slice(as, func(i, j int) bool { return as[i].at.Before(as[j].at) })
	for _, a := range as {
		if total <= msLimit*9/10 {
			break
		}
		msMu.Lock()
		l := msLocks[a.qk]
		msMu.Unlock()
		if l != nil && !l.TryLock() { // being made right now
			continue
		}
		os.Remove(filepath.Join(root, a.qk+".done"))
		os.RemoveAll(filepath.Join(root, a.qk))
		if l != nil {
			l.Unlock()
		}
		total -= a.size
		log.Printf("buildings: the area %s (%d MB) was deleted: over the disk limit", a.qk, a.size>>20)
	}
}

// the terrain files of Berlin: the oldest go first
func dgmTrim() {
	trimMu.Lock()
	defer trimMu.Unlock()
	ents, err := os.ReadDir(dgmDir)
	if err != nil {
		return
	}
	type file struct {
		name string
		size int64
		at   time.Time
	}
	var fs []file
	var total int64
	for _, e := range ents {
		if e.IsDir() || !strings.HasSuffix(e.Name(), ".i16") {
			continue
		}
		fi, err := e.Info()
		if err != nil {
			continue
		}
		fs = append(fs, file{e.Name(), fi.Size(), fi.ModTime()})
		total += fi.Size()
	}
	if total <= dgmLimit {
		return
	}
	sort.Slice(fs, func(i, j int) bool { return fs[i].at.Before(fs[j].at) })
	for _, f := range fs {
		if total <= dgmLimit*9/10 {
			break
		}
		os.Remove(filepath.Join(dgmDir, f.name)) // a square that has it mapped keeps its copy until it is done
		total -= f.size
		log.Printf("dgm: %s was deleted: over the disk limit", f.name)
	}
}

// ---- being ready before somebody comes ----

var msQueued sync.Map

// makes an area in the background (once), without anybody waiting for it
func msAsync(qk string) {
	if _, err := os.Stat(filepath.Join(msDir, "z9v2", qk+".done")); err == nil {
		return
	}
	if _, loaded := msQueued.LoadOrStore(qk, true); loaded {
		return
	}
	go func() {
		if err := msEnsure(qk); err != nil {
			log.Printf("buildings: area %s: %v", qk, err)
		}
		msQueued.Delete(qk)
	}()
}

// when the service starts, one after the other (not to fill the line to the internet): the big cities
func msPrewarm() {
	time.Sleep(30 * time.Second)
	cities := [][2]float64{{52.52, 13.40}, {41.90, 12.50}, {51.51, -0.12}, {48.86, 2.35}, {40.71, -74.00}, {34.05, -118.24}, {41.88, -87.63}, {40.42, -3.70}, {52.37, 4.90},
		{48.21, 16.37}, {48.14, 11.58}, {53.55, 9.99}, {50.11, 8.68}, {41.01, 28.98}, {55.75, 37.62}, {25.20, 55.27}, {35.68, 139.69}, {37.57, 126.98}, {1.35, 103.82},
		{-33.87, 151.21}, {-23.55, -46.63}, {19.43, -99.13}, {30.04, 31.24}, {19.08, 72.88}}
	for _, c := range cities {
		x, y := tileXY(c[0], c[1], 9)
		qk := quadkey(x, y, 9)
		if r, cc := ghslCell(c[0], c[1]); true { // the heights of the place (65 MB for 10 x 10 degrees)
			if _, err := ghslEnsure(r, cc); err != nil {
				log.Printf("ghsl R%d_C%d: %v", r, cc, err)
			}
		}
		if _, err := os.Stat(filepath.Join(msDir, "z9v2", qk+".done")); err == nil {
			continue
		}
		if err := msEnsure(qk); err != nil {
			log.Printf("buildings: area %s: %v", qk, err)
		}
	}
}

// the buildings in a box (degrees)
func msBuildings(w, s, e, n float64) ([]msB, error) {
	const m = 0.001 // a building that begins in the next small file can reach into this box
	x0, y1 := tileXY(s-m, w-m, 13)
	x1, y0 := tileXY(n+m, e+m, 13)
	var out []msB
	for x := x0; x <= x1; x++ {
		for y := y0; y <= y1; y++ {
			lat, lon := tileCenter(x, y, 13)
			qx, qy := tileXY(lat, lon, 9)
			qk := quadkey(qx, qy, 9)
			if err := msEnsure(qk); err != nil {
				return out, err
			}
			msTouch(qk) // used now: it is the last one to be deleted
			// near the edge of the area: the next one is made now, so that nobody waits when they walk over
			if lx, ly := x&15, y&15; lx <= 1 || lx >= 14 || ly <= 1 || ly >= 14 {
				for dx := -1; dx <= 1; dx++ {
					for dy := -1; dy <= 1; dy++ {
						if (dx == 0 && dy == 0) || (dx == -1 && lx > 1) || (dx == 1 && lx < 14) || (dy == -1 && ly > 1) || (dy == 1 && ly < 14) {
							continue
						}
						msAsync(quadkey(x>>4+dx, y>>4+dy, 9))
					}
				}
			}
			b, err := os.ReadFile(filepath.Join(msDir, "z9v2", qk, fmt.Sprintf("%d_%d.bin", x, y)))
			if err != nil {
				continue // no buildings in this small file
			}
			for p := 0; p+12 <= len(b); {
				cnt := int(binary.LittleEndian.Uint16(b[p:]))
				h := float64(b[p+2])
				c := -1.0
				if b[p+3] != 255 {
					c = float64(b[p+3]) / 100
				}
				lon, lat := int32(binary.LittleEndian.Uint32(b[p+4:])), int32(binary.LittleEndian.Uint32(b[p+8:]))
				p += 12
				if cnt < 1 || p+(cnt-1)*4 > len(b) {
					break
				}
				ring := make([]pt, cnt)
				ring[0] = pt{float64(lon) / 1e7, float64(lat) / 1e7}
				inside := false
				for i := 1; i < cnt; i++ {
					lon += int32(int16(binary.LittleEndian.Uint16(b[p:])))
					lat += int32(int16(binary.LittleEndian.Uint16(b[p+2:])))
					p += 4
					ring[i] = pt{float64(lon) / 1e7, float64(lat) / 1e7}
				}
				for _, q := range ring {
					if q[0] >= w-m && q[0] <= e+m && q[1] >= s-m && q[1] <= n+m {
						inside = true
						break
					}
				}
				if inside {
					out = append(out, msB{ring: ring, height: h, conf: c})
				}
			}
		}
	}
	return out, nil
}

// ---- colours from satellite pictures (Esri World Imagery): the roofs get the colour of the real roofs ----

const satZoom = 17

type satSampler struct{ tiles map[[2]int]image.Image }

func satXY(lat, lon float64) (float64, float64) {
	n := float64(int(1) << satZoom)
	return (lon + 180) / 360 * n, (1 - math.Asinh(math.Tan(lat*math.Pi/180))/math.Pi) / 2 * n
}

// gets the pictures of a box (degrees) at the same time; a picture that cannot be had is simply left out
func newSat(w, s, e, n float64) *satSampler {
	s0 := &satSampler{tiles: map[[2]int]image.Image{}}
	fx0, fy0 := satXY(n, w)
	fx1, fy1 := satXY(s, e)
	var mu sync.Mutex
	var wg sync.WaitGroup
	sem := make(chan struct{}, 10)
	for x := int(fx0); x <= int(fx1); x++ {
		for y := int(fy0); y <= int(fy1); y++ {
			wg.Add(1)
			go func(x, y int) {
				defer wg.Done()
				sem <- struct{}{}
				defer func() { <-sem }()
				u := fmt.Sprintf("https://services.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/%d/%d/%d", satZoom, y, x)
				for try := 0; try < 2; try++ {
					req, _ := http.NewRequest("GET", u, nil)
					req.Header.Set("User-Agent", "flowsearch-mctiles/1.0")
					resp, err := (&http.Client{Timeout: 25 * time.Second}).Do(req)
					if err != nil {
						continue
					}
					if resp.StatusCode != 200 {
						resp.Body.Close()
						return
					}
					im, _, err := image.Decode(resp.Body)
					resp.Body.Close()
					if err == nil {
						mu.Lock()
						s0.tiles[[2]int{x, y}] = im
						mu.Unlock()
						return
					}
				}
			}(x, y)
		}
	}
	wg.Wait()
	return s0
}

func (s *satSampler) color(lat, lon float64) (r, g, b float64, ok bool) {
	fx, fy := satXY(lat, lon)
	t := s.tiles[[2]int{int(fx), int(fy)}]
	if t == nil {
		return 0, 0, 0, false
	}
	bd := t.Bounds()
	px, py := bd.Min.X+int((fx-math.Floor(fx))*float64(bd.Dx())), bd.Min.Y+int((fy-math.Floor(fy))*float64(bd.Dy()))
	cr, cg, cb, _ := t.At(px, py).RGBA()
	return float64(cr >> 8), float64(cg >> 8), float64(cb >> 8), true
}

// the roof materials of the game (the codes are the ones of the plugin) with the colour of their blocks
var roofPalette = []struct {
	code    uint8
	r, g, b float64
}{
	{20, 161, 83, 37}, {24, 143, 61, 46}, {25, 77, 51, 36}, {31, 152, 94, 67}, {30, 186, 133, 35}, {32, 209, 177, 161},
	{26, 125, 125, 115}, {27, 54, 57, 61}, {23, 125, 125, 125}, {29, 207, 213, 214}, {22, 82, 162, 132}, {33, 73, 91, 36},
}

func roofFromColor(r, g, b float64) uint8 {
	best, bd := uint8(20), 1e18
	for _, p := range roofPalette {
		d := (r-p.r)*(r-p.r) + (g-p.g)*(g-p.g) + (b-p.b)*(b-p.b)
		if d < bd {
			bd, best = d, p.code
		}
	}
	return best
}

// ---- Berlin: the 3D city model (LoD2, Land Berlin, open data, no key): the real height and roof of every building ----
//
// The city publishes one zipped CityGML file (about 4 MB, 40 MB unzipped) for every square kilometre. A file is downloaded once and turned into a small
// file in lod2Dir (the outline, the height, the height of the eaves and the roof type of each building). The outlines of the game stay Microsoft's
// (so that what a player bought stays where it was); each of them takes the height and the roof of the Berlin building it stands in.

const lod2Dir = "/media/data/lod2-berlin"

type l2b struct {
	minE, maxE, minN, maxN float64
	ring                   [][2]float64 // easting, northing (UTM 33)
	h, eaves               float64      // the height of the building, and of the eaves, above the ground
	roofType               int
}

var (
	lod2Mu    sync.Mutex
	lod2Locks = map[[2]int]*sync.Mutex{}
)

// the numbers of a list like <gml:posList>1 2 3 4 5 6</gml:posList>
func posNumbers(b []byte) []float64 {
	var out []float64
	p := 0
	for p < len(b) {
		v, ok := fastNum(b, &p)
		if !ok {
			break
		}
		out = append(out, v)
	}
	return out
}

func xmlTagValue(b []byte, tag string) (float64, bool) {
	i := bytes.Index(b, []byte("<"+tag))
	if i < 0 {
		return 0, false
	}
	j := bytes.IndexByte(b[i:], '>')
	if j < 0 {
		return 0, false
	}
	p := i + j + 1
	return fastNum(b, &p)
}

func posListAfter(b []byte, from int) ([]float64, int) {
	i := bytes.Index(b[from:], []byte("<gml:posList"))
	if i < 0 {
		return nil, -1
	}
	i += from
	j := bytes.IndexByte(b[i:], '>')
	k := bytes.Index(b[i:], []byte("</gml:posList>"))
	if j < 0 || k < 0 {
		return nil, -1
	}
	return posNumbers(b[i+j+1 : i+k]), i + k
}

// the buildings of one file as small records: n (uint16), roof type (uint16), height, eaves (float32), then n points (float32, float32) in metres from the corner of the file
func lod2Convert(xml []byte, kx, ky int) []byte {
	var out bytes.Buffer
	pos := 0
	ox, oy := float64(kx*1000), float64(ky*1000)
	for {
		i := bytes.Index(xml[pos:], []byte("<bldg:Building "))
		if i < 0 {
			break
		}
		i += pos
		j := bytes.Index(xml[i:], []byte("</bldg:Building>"))
		if j < 0 {
			break
		}
		blk := xml[i : i+j]
		pos = i + j + 16
		g := bytes.Index(blk, []byte("<bldg:GroundSurface"))
		if g < 0 {
			continue
		}
		ring, _ := posListAfter(blk, g)
		if len(ring) < 9 {
			continue
		}
		n := len(ring) / 3
		if ring[0] == ring[(n-1)*3] && ring[1] == ring[(n-1)*3+1] {
			n-- // the last point is the first one again
		}
		gz := 1e18
		for k := 0; k < n; k++ {
			gz = math.Min(gz, ring[k*3+2])
		}
		rmin, rmax := 1e18, -1e18
		for at := 0; ; {
			r := bytes.Index(blk[at:], []byte("<bldg:RoofSurface"))
			if r < 0 {
				break
			}
			r += at
			end := bytes.Index(blk[r:], []byte("</bldg:RoofSurface>"))
			if end < 0 {
				break
			}
			for from := r; ; {
				pl, e := posListAfter(blk[:r+end], from)
				if e < 0 {
					break
				}
				for k := 2; k < len(pl); k += 3 {
					rmin, rmax = math.Min(rmin, pl[k]), math.Max(rmax, pl[k])
				}
				from = e
			}
			at = r + end
		}
		h, ok := xmlTagValue(blk, "bldg:measuredHeight")
		if !ok || h <= 0 {
			if rmax < -1e17 {
				continue
			}
			h = rmax - gz
		}
		eaves := h
		if rmax > -1e17 {
			eaves = math.Max(0, math.Min(h, rmin-gz))
		}
		rt := 9999
		if v, ok := xmlTagValue(blk, "bldg:roofType"); ok {
			rt = int(v)
		}
		var hdr [12]byte
		binary.LittleEndian.PutUint16(hdr[0:], uint16(n))
		binary.LittleEndian.PutUint16(hdr[2:], uint16(rt))
		binary.LittleEndian.PutUint32(hdr[4:], math.Float32bits(float32(h)))
		binary.LittleEndian.PutUint32(hdr[8:], math.Float32bits(float32(eaves)))
		out.Write(hdr[:])
		var pb [8]byte
		for k := 0; k < n; k++ {
			binary.LittleEndian.PutUint32(pb[0:], math.Float32bits(float32(ring[k*3]-ox)))
			binary.LittleEndian.PutUint32(pb[4:], math.Float32bits(float32(ring[k*3+1]-oy)))
			out.Write(pb[:])
		}
	}
	return out.Bytes()
}

// the buildings of one square kilometre (made first when the file is not on the disk); nil, false when the city has none for this place
func lod2Load(kx, ky int) ([]l2b, bool) {
	k := [2]int{kx, ky}
	lod2Mu.Lock()
	l, ok := lod2Locks[k]
	if !ok {
		l = &sync.Mutex{}
		lod2Locks[k] = l
	}
	lod2Mu.Unlock()
	l.Lock()
	defer l.Unlock()
	path := filepath.Join(lod2Dir, fmt.Sprintf("%d_%d.bin", kx, ky))
	b, err := os.ReadFile(path)
	if err != nil {
		if _, e2 := os.Stat(path + ".none"); e2 == nil {
			return nil, false
		}
		os.MkdirAll(lod2Dir, 0o755)
		resp, err := (&http.Client{Timeout: 5 * time.Minute}).Get(fmt.Sprintf("https://gdi.berlin.de/data/a_lod2/atom/LoD2_%d_%d.zip", kx, ky))
		if err != nil {
			log.Printf("lod2 %d,%d: %v", kx, ky, err)
			return nil, false
		}
		defer resp.Body.Close()
		if resp.StatusCode == 404 {
			os.WriteFile(path+".none", nil, 0o644)
			return nil, false
		}
		if resp.StatusCode != 200 {
			log.Printf("lod2 %d,%d: status %d", kx, ky, resp.StatusCode)
			return nil, false
		}
		zf, err := os.CreateTemp(lod2Dir, "download-*.zip")
		if err != nil {
			return nil, false
		}
		defer os.Remove(zf.Name())
		_, err = io.Copy(zf, resp.Body)
		zf.Close()
		if err != nil {
			return nil, false
		}
		zr, err := zip.OpenReader(zf.Name())
		if err != nil || len(zr.File) == 0 {
			return nil, false
		}
		in, err := zr.File[0].Open()
		if err != nil {
			zr.Close()
			return nil, false
		}
		xml, err := io.ReadAll(in)
		in.Close()
		zr.Close()
		if err != nil {
			return nil, false
		}
		b = lod2Convert(xml, kx, ky)
		os.WriteFile(path+".tmp", b, 0o644)
		os.Rename(path+".tmp", path)
		log.Printf("lod2: made %d_%d (%d KB)", kx, ky, len(b)>>10)
	}
	var out []l2b
	ox, oy := float64(kx*1000), float64(ky*1000)
	for p := 0; p+12 <= len(b); {
		n := int(binary.LittleEndian.Uint16(b[p:]))
		x := l2b{roofType: int(binary.LittleEndian.Uint16(b[p+2:])), h: float64(math.Float32frombits(binary.LittleEndian.Uint32(b[p+4:]))), eaves: float64(math.Float32frombits(binary.LittleEndian.Uint32(b[p+8:])))}
		p += 12
		if p+n*8 > len(b) {
			break
		}
		x.minE, x.minN, x.maxE, x.maxN = 1e18, 1e18, -1e18, -1e18
		x.ring = make([][2]float64, n)
		for k := 0; k < n; k++ {
			e := ox + float64(math.Float32frombits(binary.LittleEndian.Uint32(b[p+k*8:])))
			nn := oy + float64(math.Float32frombits(binary.LittleEndian.Uint32(b[p+k*8+4:])))
			x.ring[k] = [2]float64{e, nn}
			x.minE, x.maxE, x.minN, x.maxN = math.Min(x.minE, e), math.Max(x.maxE, e), math.Min(x.minN, nn), math.Max(x.maxN, nn)
		}
		p += n * 8
		out = append(out, x)
	}
	return out, true
}

type lod2Index struct{ b []l2b }

// the buildings of Berlin around a box (degrees); nil when the box is not in Berlin or the city has no files for it
func newLod2(w, s, e, n float64) *lod2Index {
	if !(inBerlin(w, n) || inBerlin(e, n) || inBerlin(w, s) || inBerlin(e, s)) {
		return nil
	}
	e0, n0 := utm33(w, s)
	e1, n1 := utm33(e, n)
	idx := &lod2Index{}
	for kx := int(math.Floor(math.Min(e0, e1) / 1000)); kx <= int(math.Floor(math.Max(e0, e1)/1000)); kx++ {
		for ky := int(math.Floor(math.Min(n0, n1) / 1000)); ky <= int(math.Floor(math.Max(n0, n1)/1000)); ky++ {
			bs, ok := lod2Load(kx, ky)
			if !ok {
				return nil // a part of the box has no model: the whole square keeps the plain buildings
			}
			idx.b = append(idx.b, bs...)
		}
	}
	return idx
}

func roofShapeOf(code int) string {
	switch code {
	case 3100, 2100, 3800:
		return "gabled"
	case 3200, 3300:
		return "hipped"
	case 3400:
		return "mansard"
	case 3500:
		return "pyramidal"
	case 3600:
		return "cone"
	case 3700:
		return "dome"
	case 3900:
		return "round"
	case 1000:
		return "flat"
	}
	return ""
}

// the Berlin building a place is in: its height, the shape of its roof and the height of it
func (x *lod2Index) at(lon, lat float64) (h float64, shape string, roofH float64, ok bool) {
	e, nn := utm33(lon, lat)
	for i := range x.b {
		b := &x.b[i]
		if e < b.minE || e > b.maxE || nn < b.minN || nn > b.maxN {
			continue
		}
		in := false
		for a, c := 0, len(b.ring)-1; a < len(b.ring); a, c = a+1, a {
			if (b.ring[a][1] > nn) != (b.ring[c][1] > nn) && e < (b.ring[c][0]-b.ring[a][0])*(nn-b.ring[a][1])/(b.ring[c][1]-b.ring[a][1])+b.ring[a][0] {
				in = !in
			}
		}
		if !in {
			continue
		}
		shape = roofShapeOf(b.roofType)
		roofH = b.h - b.eaves
		if shape == "" && roofH > 1.2 {
			shape = "gabled"
		}
		if shape == "flat" || roofH < 0.5 {
			roofH = 0
		}
		return b.h, shape, roofH, true
	}
	return 0, "", 0, false
}

// ---- the Netherlands: 3DBAG, every building of the country with the real height and roof (open data, CC BY, no key) ----
//
// The service answers for any box: the roof parts of the buildings in it, with their heights above the ground and the kind of roof. Nothing is stored:
// a square asks once when it is made (and a made square is kept).

const bag3dWFS = "https://data.3dbag.nl/api/BAG3D/wfs"

var (
	nlMu   sync.Mutex
	nlLast time.Time
)

type nlPart struct {
	minLon, maxLon, minLat, maxLat float64
	ring                           [][2]float64
	h, eaves                       float64
	flat                           bool
}

type nlIndex struct{ parts []nlPart }

func inNetherlands(lon, lat float64) bool { return lon > 3.2 && lon < 7.3 && lat > 50.7 && lat < 53.7 }

// the roof parts of the buildings in a box (degrees); nil when the box is not in the Netherlands, or the service does not answer
func newNL(w, s, e, n float64) *nlIndex {
	if !(inNetherlands(w, n) || inNetherlands(e, n) || inNetherlands(w, s) || inNetherlands(e, s)) {
		return nil
	}
	idx := &nlIndex{}
	for start := 0; start < 20000; start += 4000 {
		nlMu.Lock()
		if d := 300*time.Millisecond - time.Since(nlLast); d > 0 { // a short pause between two questions: it is a free service
			time.Sleep(d)
		}
		nlLast = time.Now()
		nlMu.Unlock()
		u := fmt.Sprintf("%s?service=WFS&version=2.0.0&request=GetFeature&typeNames=BAG3D:lod22&outputFormat=json&count=4000&startIndex=%d&srsName=urn:ogc:def:crs:EPSG::4326&bbox=%.6f,%.6f,%.6f,%.6f,urn:ogc:def:crs:EPSG::4326",
			bag3dWFS, start, s, w, n, e)
		req, _ := http.NewRequest("GET", u, nil)
		req.Header.Set("User-Agent", "flowsearch-mctiles/1.0")
		resp, err := (&http.Client{Timeout: 90 * time.Second}).Do(req)
		if err != nil {
			log.Printf("3dbag: %v", err)
			return nil
		}
		body, err := io.ReadAll(io.LimitReader(resp.Body, 128<<20))
		resp.Body.Close()
		if err != nil || resp.StatusCode != 200 {
			log.Printf("3dbag: status %d %v", resp.StatusCode, err)
			return nil
		}
		var fc struct {
			Features []struct {
				Geometry struct {
					Type        string          `json:"type"`
					Coordinates json.RawMessage `json:"coordinates"`
				} `json:"geometry"`
				Properties struct {
					HMax *float64 `json:"b3_h_max"`
					HMin *float64 `json:"b3_h_min"`
					HGnd *float64 `json:"b3_h_maaiveld"`
					Dak  string   `json:"b3_dak_type"`
				} `json:"properties"`
			} `json:"features"`
		}
		if err := json.Unmarshal(body, &fc); err != nil {
			log.Printf("3dbag: %v", err)
			return nil
		}
		for _, f := range fc.Features {
			if f.Properties.HMax == nil || f.Properties.HGnd == nil {
				continue
			}
			var rings [][][2]float64
			switch f.Geometry.Type {
			case "Polygon":
				var c [][][2]float64
				if json.Unmarshal(f.Geometry.Coordinates, &c) == nil && len(c) > 0 {
					rings = append(rings, c[0])
				}
			case "MultiPolygon":
				var c [][][][2]float64
				if json.Unmarshal(f.Geometry.Coordinates, &c) == nil {
					for _, p := range c {
						if len(p) > 0 {
							rings = append(rings, p[0])
						}
					}
				}
			}
			h := *f.Properties.HMax - *f.Properties.HGnd
			eaves := h
			if f.Properties.HMin != nil {
				eaves = math.Max(0, math.Min(h, *f.Properties.HMin-*f.Properties.HGnd))
			}
			for _, r := range rings {
				p := nlPart{ring: r, h: h, eaves: eaves, flat: f.Properties.Dak != "slanted", minLon: 1e18, minLat: 1e18, maxLon: -1e18, maxLat: -1e18}
				for _, q := range r {
					p.minLon, p.maxLon, p.minLat, p.maxLat = math.Min(p.minLon, q[0]), math.Max(p.maxLon, q[0]), math.Min(p.minLat, q[1]), math.Max(p.maxLat, q[1])
				}
				idx.parts = append(idx.parts, p)
			}
		}
		if len(fc.Features) < 4000 {
			break
		}
	}
	log.Printf("3dbag: %d roof parts for a square", len(idx.parts))
	return idx
}

// the roof part of the Dutch building a place is in
func (x *nlIndex) at(lon, lat float64) (h float64, shape string, roofH float64, ok bool) {
	for i := range x.parts {
		p := &x.parts[i]
		if lon < p.minLon || lon > p.maxLon || lat < p.minLat || lat > p.maxLat {
			continue
		}
		in := false
		for a, c := 0, len(p.ring)-1; a < len(p.ring); a, c = a+1, a {
			if (p.ring[a][1] > lat) != (p.ring[c][1] > lat) && lon < (p.ring[c][0]-p.ring[a][0])*(lat-p.ring[a][1])/(p.ring[c][1]-p.ring[a][1])+p.ring[a][0] {
				in = !in
			}
		}
		if !in {
			continue
		}
		if p.flat {
			return p.h, "flat", 0, true
		}
		return p.h, "gabled", math.Max(0, p.h-p.eaves), true
	}
	return 0, "", 0, false
}

// ---- France: BD TOPO of IGN, the height of every building (to the gutter) from the open web service of the Géoplateforme (Licence Ouverte, no key) ----
//
// Mainland France only. A square asks for the buildings in its area (about 0.4 MB for 600 buildings); the answer is kept in frDir (capped at frLimit,
// the oldest first). It gives a height to the buildings that Microsoft's outlines have no real height for.

const (
	frDir   = "/media/data/bdtopo"
	frLimit = 400 << 20
	frURL   = "https://data.geopf.fr/wfs/ows?service=WFS&version=2.0.0&request=GetFeature&typeName=BDTOPO_V3:batiment&outputFormat=application/json&srsName=EPSG:4326&bbox=%.6f,%.6f,%.6f,%.6f,EPSG:4326&count=2000&startIndex=%d"
)

type frB struct {
	ring                   [][2]float64
	minX, minY, maxX, maxY float64
	h                      float64
}

type frIndex struct{ b []frB }

var frSem = make(chan struct{}, 3)

func newFR(w, s, e, n float64) heightSource {
	if e < -5.2 || w > 9.7 || n < 41.2 || s > 51.2 { // not mainland France
		return nil
	}
	key := filepath.Join(frDir, fmt.Sprintf("%.4f_%.4f_%.4f_%.4f.json", w, s, e, n))
	type raw struct {
		H float64      `json:"h"`
		R [][2]float64 `json:"r"`
	}
	var rs []raw
	if b, err := os.ReadFile(key); err == nil && json.Unmarshal(b, &rs) == nil {
		now := time.Now()
		os.Chtimes(key, now, now)
	} else {
		rs = nil
		frSem <- struct{}{}
		defer func() { <-frSem }()
		for start := 0; start < 12000; {
			resp, err := (&http.Client{Timeout: 60 * time.Second}).Get(fmt.Sprintf(frURL, w, s, e, n, start))
			if err != nil {
				log.Printf("bdtopo: %v (the square gets no heights from France)", err)
				return nil
			}
			var fc struct {
				Features []struct {
					Properties struct {
						Hauteur *float64 `json:"hauteur"`
						Etages  *float64 `json:"nombre_d_etages"`
					} `json:"properties"`
					Geometry struct {
						Type        string          `json:"type"`
						Coordinates json.RawMessage `json:"coordinates"`
					} `json:"geometry"`
				} `json:"features"`
				Matched int `json:"numberMatched"`
			}
			err = json.NewDecoder(resp.Body).Decode(&fc)
			resp.Body.Close()
			if err != nil || resp.StatusCode != 200 {
				log.Printf("bdtopo: %v / %d (the square gets no heights from France)", err, resp.StatusCode)
				return nil
			}
			for _, f := range fc.Features {
				h := 0.0
				if f.Properties.Hauteur != nil && *f.Properties.Hauteur > 0 {
					h = *f.Properties.Hauteur
				} else if f.Properties.Etages != nil && *f.Properties.Etages > 0 {
					h = *f.Properties.Etages * 3
				}
				if h <= 0 {
					continue
				}
				var polys [][][][]float64
				switch f.Geometry.Type {
				case "MultiPolygon":
					json.Unmarshal(f.Geometry.Coordinates, &polys)
				case "Polygon":
					var p [][][]float64
					if json.Unmarshal(f.Geometry.Coordinates, &p) == nil {
						polys = [][][][]float64{p}
					}
				}
				for _, p := range polys {
					if len(p) == 0 || len(p[0]) < 4 {
						continue
					}
					r := make([][2]float64, len(p[0]))
					for i, q := range p[0] {
						r[i] = [2]float64{q[0], q[1]}
					}
					rs = append(rs, raw{h, r})
				}
			}
			start += len(fc.Features)
			if len(fc.Features) == 0 || start >= fc.Matched {
				break
			}
		}
		os.MkdirAll(frDir, 0o755)
		if b, err := json.Marshal(rs); err == nil {
			os.WriteFile(key, b, 0o644)
			go frTrim()
		}
	}
	ix := &frIndex{}
	for _, r := range rs {
		b := frB{ring: r.R, h: r.H, minX: 1e9, minY: 1e9, maxX: -1e9, maxY: -1e9}
		for _, q := range r.R {
			b.minX, b.maxX, b.minY, b.maxY = math.Min(b.minX, q[0]), math.Max(b.maxX, q[0]), math.Min(b.minY, q[1]), math.Max(b.maxY, q[1])
		}
		ix.b = append(ix.b, b)
	}
	return ix
}

// the building of BD TOPO that a place is in: its height (to the gutter; a house gets 3 m more for its roof, the roof itself is made by the square)
func (x *frIndex) at(lon, lat float64) (float64, string, float64, bool) {
	var best *frB
	for i := range x.b {
		b := &x.b[i]
		if lon < b.minX || lon > b.maxX || lat < b.minY || lat > b.maxY {
			continue
		}
		in := false
		r := b.ring
		for i, j := 0, len(r)-1; i < len(r); j, i = i, i+1 {
			if (r[i][1] > lat) != (r[j][1] > lat) && lon < (r[j][0]-r[i][0])*(lat-r[i][1])/(r[j][1]-r[i][1])+r[i][0] {
				in = !in
			}
		}
		if in && (best == nil || b.h > best.h) {
			best = b
		}
	}
	if best == nil {
		return 0, "", 0, false
	}
	h := best.h
	if h < 12 {
		h += 3
	}
	return h, "", 0, true
}

func frTrim() {
	ents, err := os.ReadDir(frDir)
	if err != nil {
		return
	}
	type f struct {
		name string
		size int64
		at   time.Time
	}
	var fs []f
	var total int64
	for _, e := range ents {
		if fi, err := e.Info(); err == nil && !fi.IsDir() {
			fs = append(fs, f{e.Name(), fi.Size(), fi.ModTime()})
			total += fi.Size()
		}
	}
	sort.Slice(fs, func(i, j int) bool { return fs[i].at.Before(fs[j].at) })
	for _, x := range fs {
		if total <= frLimit {
			break
		}
		os.Remove(filepath.Join(frDir, x.name))
		total -= x.size
	}
}

// a source of real building heights and roofs (Berlin's city model, the Dutch 3DBAG)
type heightSource interface {
	at(lon, lat float64) (h float64, shape string, roofH float64, ok bool)
}

// ---- GHSL (EU, Joint Research Centre): the average height of the buildings, worldwide, every 100 m (open data, no key) ----
//
// One file per 10 x 10 degrees (about 65 MB, a BigTIFF with LZW-packed pieces of 256 x 256 heights as 32-bit numbers). A file is downloaded once and kept in
// ghslDir; a square only reads the few small pieces around it. The height is used for the buildings that Microsoft gives no height for, instead of one
// height for all of them.

const (
	ghslDir   = "/media/data/ghsl"
	ghslURL   = "https://jeodpp.jrc.ec.europa.eu/ftp/jrc-opendata/GHSL/GHS_BUILT_H_GLOBE_R2023A/GHS_BUILT_H_ANBH_E2018_GLOBE_R2023A_4326_3ss/V1-0/tiles/GHS_BUILT_H_ANBH_E2018_GLOBE_R2023A_4326_3ss_V1_0_R%d_C%d.zip"
	ghslLimit = 1500 << 20 // 1.5 GB for these files
)

type ghslFile struct {
	f                  *os.File
	offs               []uint64
	cnts               []uint32
	lon0, lat0, scale  float64
	w, h, tw, th, tacr int
}

var (
	ghslMu    sync.Mutex
	ghslOpenF = map[[2]int]*ghslFile{}
	ghslLocks = map[[2]int]*sync.Mutex{}
	ghslBusy  sync.Map
)

func ghslCell(lat, lon float64) (int, int) {
	return int(math.Floor((90-lat)/10)) + 1, int(math.Floor((lon+180)/10)) + 1
}

// the pieces of a TIFF are packed with LZW (the most significant bit first, the code gets longer one code earlier than in GIF)
func tiffLZW(src []byte, want int) []byte {
	out := make([]byte, 0, want)
	var table [4096][]byte
	bits, next := 9, 258
	var prev []byte
	var buf uint32
	nb, p := 0, 0
	for len(out) < want {
		for nb < bits {
			if p >= len(src) {
				return out
			}
			buf = buf<<8 | uint32(src[p])
			p++
			nb += 8
		}
		code := int(buf>>uint(nb-bits)) & (1<<uint(bits) - 1)
		nb -= bits
		if code == 257 {
			break
		}
		if code == 256 {
			bits, next, prev = 9, 258, nil
			continue
		}
		var entry []byte
		switch {
		case code < 256:
			entry = []byte{byte(code)}
		case code < next:
			entry = table[code]
		case prev != nil && code == next:
			entry = append(append([]byte{}, prev...), prev[0])
		default:
			return out
		}
		out = append(out, entry...)
		if prev != nil && next < 4096 {
			table[next] = append(append([]byte{}, prev...), entry[0])
			next++
		}
		prev = entry
		if next+1 >= 1<<uint(bits) && bits < 12 {
			bits++
		}
	}
	return out
}

func ghslPath(r, c int) string { return filepath.Join(ghslDir, fmt.Sprintf("R%d_C%d.tif", r, c)) }

// makes sure the file is on the disk (downloads it first when it is not); false when there is none for this place (the sea)
func ghslEnsure(r, c int) (bool, error) {
	k := [2]int{r, c}
	ghslMu.Lock()
	l, ok := ghslLocks[k]
	if !ok {
		l = &sync.Mutex{}
		ghslLocks[k] = l
	}
	ghslMu.Unlock()
	l.Lock()
	defer l.Unlock()
	path := ghslPath(r, c)
	if _, err := os.Stat(path); err == nil {
		now := time.Now()
		os.Chtimes(path, now, now)
		return true, nil
	}
	if _, err := os.Stat(path + ".none"); err == nil {
		return false, nil
	}
	os.MkdirAll(ghslDir, 0o755)
	resp, err := (&http.Client{Timeout: 15 * time.Minute}).Get(fmt.Sprintf(ghslURL, r, c))
	if err != nil {
		return false, err
	}
	defer resp.Body.Close()
	if resp.StatusCode == 404 {
		os.WriteFile(path+".none", nil, 0o644)
		return false, nil
	}
	if resp.StatusCode != 200 {
		return false, fmt.Errorf("ghsl %d", resp.StatusCode)
	}
	zf, err := os.CreateTemp(ghslDir, "download-*.zip")
	if err != nil {
		return false, err
	}
	defer os.Remove(zf.Name())
	if _, err := io.Copy(zf, resp.Body); err != nil {
		zf.Close()
		return false, err
	}
	zf.Close()
	zr, err := zip.OpenReader(zf.Name())
	if err != nil {
		return false, err
	}
	defer zr.Close()
	for _, e := range zr.File {
		if !strings.HasSuffix(e.Name, ".tif") {
			continue
		}
		in, err := e.Open()
		if err != nil {
			return false, err
		}
		out, err := os.Create(path + ".tmp")
		if err != nil {
			in.Close()
			return false, err
		}
		_, err = io.Copy(out, in)
		in.Close()
		out.Close()
		if err != nil {
			return false, err
		}
		os.Rename(path+".tmp", path)
		log.Printf("ghsl: made R%d_C%d", r, c)
		go ghslTrim()
		return true, nil
	}
	return false, fmt.Errorf("ghsl: no picture in the zip")
}

func ghslTrim() {
	ents, err := os.ReadDir(ghslDir)
	if err != nil {
		return
	}
	type f struct {
		name string
		size int64
		at   time.Time
	}
	var fs []f
	var total int64
	for _, e := range ents {
		if !strings.HasSuffix(e.Name(), ".tif") {
			continue
		}
		if fi, err := e.Info(); err == nil {
			fs = append(fs, f{e.Name(), fi.Size(), fi.ModTime()})
			total += fi.Size()
		}
	}
	sort.Slice(fs, func(i, j int) bool { return fs[i].at.Before(fs[j].at) })
	for _, x := range fs {
		if total <= ghslLimit {
			break
		}
		os.Remove(filepath.Join(ghslDir, x.name))
		total -= x.size
		log.Printf("ghsl: %s was deleted: over the disk limit", x.name)
	}
}

// opens a file that is on the disk and reads where its pieces are
func ghslOpen(r, c int) *ghslFile {
	k := [2]int{r, c}
	ghslMu.Lock()
	if g, ok := ghslOpenF[k]; ok {
		ghslMu.Unlock()
		return g
	}
	ghslMu.Unlock()
	f, err := os.Open(ghslPath(r, c))
	if err != nil {
		return nil
	}
	g := &ghslFile{f: f}
	rd := func(off int64, n int) []byte {
		b := make([]byte, n)
		if _, err := f.ReadAt(b, off); err != nil && err != io.EOF {
			return nil
		}
		return b
	}
	h := rd(0, 16)
	if h == nil || string(h[:2]) != "II" || binary.LittleEndian.Uint16(h[2:]) != 43 {
		f.Close()
		return nil
	}
	ifd := int64(binary.LittleEndian.Uint64(h[8:]))
	cb := rd(ifd, 8)
	if cb == nil {
		f.Close()
		return nil
	}
	n := int(binary.LittleEndian.Uint64(cb))
	ent := rd(ifd+8, n*20)
	if ent == nil {
		f.Close()
		return nil
	}
	comp := 0
	var offTag, cntTag, offTyp, cntTyp int
	var offN, cntN int
	var offAt, cntAt, scaleAt, tieAt uint64
	for i := 0; i < n; i++ {
		e := ent[i*20:]
		tag, typ := int(binary.LittleEndian.Uint16(e)), int(binary.LittleEndian.Uint16(e[2:]))
		cnt, val := int(binary.LittleEndian.Uint64(e[4:])), binary.LittleEndian.Uint64(e[12:])
		switch tag {
		case 256:
			g.w = int(val & 0xFFFFFFFF)
		case 257:
			g.h = int(val & 0xFFFFFFFF)
		case 259:
			comp = int(val & 0xFFFF)
		case 322:
			g.tw = int(val & 0xFFFFFFFF)
		case 323:
			g.th = int(val & 0xFFFFFFFF)
		case 324:
			offTag, offTyp, offN, offAt = tag, typ, cnt, val
		case 325:
			cntTag, cntTyp, cntN, cntAt = tag, typ, cnt, val
		case 33550:
			scaleAt = val
		case 33922:
			tieAt = val
		}
	}
	if comp != 5 || g.tw == 0 || offTag == 0 || cntTag == 0 || offN != cntN || scaleAt == 0 || tieAt == 0 || offTyp != 16 || cntTyp != 4 {
		f.Close()
		return nil
	}
	ob, cbts := rd(int64(offAt), offN*8), rd(int64(cntAt), cntN*4)
	sc, tp := rd(int64(scaleAt), 24), rd(int64(tieAt), 48)
	if ob == nil || cbts == nil || sc == nil || tp == nil {
		f.Close()
		return nil
	}
	g.offs, g.cnts = make([]uint64, offN), make([]uint32, cntN)
	for i := range g.offs {
		g.offs[i] = binary.LittleEndian.Uint64(ob[i*8:])
		g.cnts[i] = binary.LittleEndian.Uint32(cbts[i*4:])
	}
	g.scale = math.Float64frombits(binary.LittleEndian.Uint64(sc))
	g.lon0, g.lat0 = math.Float64frombits(binary.LittleEndian.Uint64(tp[24:])), math.Float64frombits(binary.LittleEndian.Uint64(tp[32:]))
	g.tacr = (g.w + g.tw - 1) / g.tw
	ghslMu.Lock()
	ghslOpenF[k] = g
	ghslMu.Unlock()
	return g
}

// the average building height at a place, from the pieces that this square reads
type ghslSampler struct{ blocks map[string][]float32 }

func newGhsl() *ghslSampler { return &ghslSampler{blocks: map[string][]float32{}} }

func (s *ghslSampler) at(lat, lon float64) (float64, bool) {
	r, c := ghslCell(lat, lon)
	if _, err := os.Stat(ghslPath(r, c)); err != nil { // not on the disk: it is fetched in the background, and the squares made from now on have it
		if _, loaded := ghslBusy.LoadOrStore([2]int{r, c}, true); !loaded {
			go func() {
				if _, err := ghslEnsure(r, c); err != nil {
					log.Printf("ghsl R%d_C%d: %v", r, c, err)
				}
				ghslBusy.Delete([2]int{r, c})
			}()
		}
		return 0, false
	}
	g := ghslOpen(r, c)
	if g == nil {
		return 0, false
	}
	col, row := int((lon-g.lon0)/g.scale), int((g.lat0-lat)/g.scale)
	if col < 0 || row < 0 || col >= g.w || row >= g.h {
		return 0, false
	}
	idx := (row/g.th)*g.tacr + col/g.tw
	if idx >= len(g.offs) {
		return 0, false
	}
	key := fmt.Sprintf("%d_%d_%d", r, c, idx)
	blk, ok := s.blocks[key]
	if !ok {
		raw := make([]byte, g.cnts[idx])
		if _, err := g.f.ReadAt(raw, int64(g.offs[idx])); err == nil || err == io.EOF {
			px := tiffLZW(raw, g.tw*g.th*4)
			if len(px) >= g.tw*g.th*4 {
				blk = make([]float32, g.tw*g.th)
				for i := range blk {
					blk[i] = math.Float32frombits(binary.LittleEndian.Uint32(px[i*4:]))
				}
			}
		}
		s.blocks[key] = blk
	}
	if blk == nil {
		return 0, false
	}
	v := float64(blk[(row%g.th)*g.tw+col%g.tw])
	if v <= 0 || v > 400 || math.IsNaN(v) {
		return 0, false
	}
	return v, true
}

// ---- Overture Maps building parts, worldwide: a tower on a base, a spire, a roof of its own (open data, no key) ----
//
// A helper (overture_parts.py, DuckDB) stays running and answers "west south east north" with the parts of that area. It reads only the pieces of
// Overture's one file that cover the area (about 0.1 MB for a square). The answers are kept in ovDir (small files, capped at ovLimit, oldest first).

const (
	ovDir   = "/media/data/overture"
	ovLimit = 300 << 20
)

// the program that runs the helper (it needs the Python package duckdb: MCTILES_PYTHON=/path/to/venv/bin/python) and the helper itself (next to this program)
func ovPython() string {
	if v := os.Getenv("MCTILES_PYTHON"); v != "" {
		return v
	}
	return "python3"
}

func ovScript() string {
	if v := os.Getenv("MCTILES_OVERTURE_SCRIPT"); v != "" {
		return v
	}
	exe, _ := os.Executable()
	return filepath.Join(filepath.Dir(exe), "overture_parts.py")
}

type ovPart struct {
	H  float64      `json:"h"`
	S  string       `json:"s"`
	RH float64      `json:"rh"`
	R  [][2]float64 `json:"r"`
}

var (
	ovMu    sync.Mutex
	ovCmd   *exec.Cmd
	ovIn    io.WriteCloser
	ovOut   *bufio.Reader
	ovFails int
	ovOffTo time.Time
)

func ovStart() error {
	cmd := exec.Command(ovPython(), ovScript())
	in, err := cmd.StdinPipe()
	if err != nil {
		return err
	}
	out, err := cmd.StdoutPipe()
	if err != nil {
		return err
	}
	if err := cmd.Start(); err != nil {
		return err
	}
	ovCmd, ovIn, ovOut = cmd, in, bufio.NewReaderSize(out, 1<<20)
	go cmd.Wait()
	return nil
}

func ovStop() {
	if ovCmd != nil && ovCmd.Process != nil {
		ovCmd.Process.Kill()
	}
	ovCmd, ovIn, ovOut = nil, nil, nil
}

// the parts in an area; the answer is kept on the disk
func ovParts(w, s, e, n float64) ([]ovPart, error) {
	key := filepath.Join(ovDir, fmt.Sprintf("%.4f_%.4f_%.4f_%.4f.json", w, s, e, n))
	if b, err := os.ReadFile(key); err == nil {
		var ps []ovPart
		if json.Unmarshal(b, &ps) == nil {
			now := time.Now()
			os.Chtimes(key, now, now)
			return ps, nil
		}
	}
	ovMu.Lock()
	defer ovMu.Unlock()
	if time.Now().Before(ovOffTo) {
		return nil, fmt.Errorf("overture: paused after errors")
	}
	if ovCmd == nil {
		if err := ovStart(); err != nil {
			ovOffTo = time.Now().Add(2 * time.Minute)
			return nil, err
		}
	}
	if _, err := fmt.Fprintf(ovIn, "%.6f %.6f %.6f %.6f\n", w, s, e, n); err != nil {
		ovStop()
		return nil, err
	}
	type res struct {
		line []byte
		err  error
	}
	ch := make(chan res, 1)
	go func(rd *bufio.Reader) {
		l, err := rd.ReadBytes('\n')
		ch <- res{l, err}
	}(ovOut)
	var r res
	select {
	case r = <-ch:
	case <-time.After(90 * time.Second):
		r = res{nil, fmt.Errorf("overture: too slow")}
	}
	if r.err != nil {
		ovStop()
		if ovFails++; ovFails >= 3 {
			ovFails, ovOffTo = 0, time.Now().Add(5*time.Minute)
		}
		return nil, r.err
	}
	var ps []ovPart
	if err := json.Unmarshal(r.line, &ps); err != nil { // an {"error": ...} line: this square gets none
		msg := strings.TrimSpace(string(r.line))
		return nil, fmt.Errorf("overture: %s", msg[:min(120, len(msg))])
	}
	ovFails = 0
	os.MkdirAll(ovDir, 0o755)
	os.WriteFile(key, r.line, 0o644)
	go ovTrim()
	return ps, nil
}

func ovTrim() {
	ents, err := os.ReadDir(ovDir)
	if err != nil {
		return
	}
	type f struct {
		name string
		size int64
		at   time.Time
	}
	var fs []f
	var total int64
	for _, e := range ents {
		if fi, err := e.Info(); err == nil && !fi.IsDir() {
			fs = append(fs, f{e.Name(), fi.Size(), fi.ModTime()})
			total += fi.Size()
		}
	}
	sort.Slice(fs, func(i, j int) bool { return fs[i].at.Before(fs[j].at) })
	for _, x := range fs {
		if total <= ovLimit {
			break
		}
		os.Remove(filepath.Join(ovDir, x.name))
		total -= x.size
	}
}

// ---- making a square ----

type tree struct{ X, Z, H int }

func buildTile(tx, tz int) ([]byte, error) {
	ox, oz := tx*tile-work, tz*tile-work // the grid's north-west corner in world blocks
	g := &grid{lon0: refLon + float64(ox)/mLon, lat0: refLat - float64(oz)/mLat}
	N := G * G
	g.ground, g.surf = make([]uint16, N), make([]uint8, N)
	g.wallH, g.roofH, g.wallM, g.roofM = make([]uint8, N), make([]uint8, N), make([]uint8, N), make([]uint8, N)
	w, n := g.lon0, g.lat0
	e, s := w+float64(G)/mLon, n-float64(G)/mLat
	if n > 84 || s < -84 {
		return nil, fmt.Errorf("too far north or south")
	}
	envelope := fmt.Sprintf("ST_MakeEnvelope(%.6f,%.6f,%.6f,%.6f,4326)", w, s, e, n)

	// the ground
	const zoom = 15
	fz := float64(int(1) << zoom)
	txf := func(lon float64) float64 { return (lon + 180) / 360 * fz }
	tyf := func(lat float64) float64 {
		r := lat * math.Pi / 180
		return (1 - math.Asinh(math.Tan(r))/math.Pi) / 2 * fz
	}
	// the four questions to Postpass are asked at once, while the elevation pictures are loading
	askLater := func(sql string) func() ([]feat, error) {
		type res struct {
			f   []feat
			err error
		}
		c := make(chan res, 1)
		go func() { f, e := ask(sql); c <- res{f, e} }()
		var r res
		got := false
		return func() ([]feat, error) {
			if !got {
				r, got = <-c, true
			}
			return r.f, r.err
		}
	}
	qRoads := askLater("SELECT osm_id, tags, geom FROM postpass_line WHERE geom && " + envelope + " AND (tags ? 'highway' OR tags->>'railway' IN ('rail','tram','light_rail'))")

	tiles := map[[2]int]*tileImg{}
	var tmu sync.Mutex
	var twg sync.WaitGroup
	var terr error
	for x := int(txf(w)); x <= int(txf(e)); x++ {
		for y := int(tyf(n)); y <= int(tyf(s)); y++ {
			twg.Add(1)
			go func(x, y int) {
				defer twg.Done()
				t, err := elevTile(zoom, x, y)
				tmu.Lock()
				defer tmu.Unlock()
				if err != nil {
					terr = err
					return
				}
				tiles[[2]int{x, y}] = t
			}(x, y)
		}
	}
	twg.Wait()
	if terr != nil {
		return nil, terr
	}
	// inside Berlin: the measurements of the city (every metre), when they can be had; otherwise the elevation pictures stay
	var dgm map[[2]int]*dgmTile
	defer func() { // the mapped files are let go of as soon as the square is made
		for _, t := range dgm {
			t.close()
		}
	}()
	if inBerlin(w, n) || inBerlin(e, n) || inBerlin(w, s) || inBerlin(e, s) {
		keys := map[[2]int]bool{}
		for _, c := range [][2]float64{{w, n}, {e, n}, {w, s}, {e, s}} {
			ee, nn := utm33(c[0], c[1])
			keys[dgmKey(ee, nn)] = true
		}
		dgm = map[[2]int]*dgmTile{}
		var dmu sync.Mutex
		var dwg sync.WaitGroup
		for k := range keys {
			dwg.Add(1)
			go func(k [2]int) {
				defer dwg.Done()
				t, err := dgmLoad(k)
				if err != nil {
					log.Printf("dgm %v: %v (the elevation pictures are used)", k, err)
					return
				}
				if t != nil {
					dmu.Lock()
					dgm[k] = t
					dmu.Unlock()
				}
			}(k)
		}
		dwg.Wait()
	}
	for z := 0; z < G; z++ {
		for x := 0; x < G; x++ {
			lon := g.lon0 + (float64(x)+0.5)/mLon
			lat := g.lat0 - (float64(z)+0.5)/mLat
			fx, fy := txf(lon), tyf(lat)
			t := tiles[[2]int{int(fx), int(fy)}]
			v := 0.0
			if t != nil {
				px, py := (fx-math.Floor(fx))*float64(t.w), (fy-math.Floor(fy))*float64(t.h)
				ix, iy := int(math.Min(float64(t.w-2), math.Max(0, px-0.5))), int(math.Min(float64(t.h-2), math.Max(0, py-0.5)))
				ax, ay := math.Max(0, px-0.5)-float64(ix), math.Max(0, py-0.5)-float64(iy)
				v = float64(t.v[iy*t.w+ix])*(1-ax)*(1-ay) + float64(t.v[iy*t.w+ix+1])*ax*(1-ay) + float64(t.v[(iy+1)*t.w+ix])*(1-ax)*ay + float64(t.v[(iy+1)*t.w+ix+1])*ax*ay
			}
			if len(dgm) > 0 && inBerlin(lon, lat) {
				ee, nn := utm33(lon, lat)
				if hv, ok := dgmHeight(dgm, ee, nn); ok {
					v = hv
				}
			}
			i := g.idx(x, z)
			g.ground[i] = gameHeight(v)
			g.surf[i] = sGrass
			if v < -8 { // deep below the sea level: the sea
				g.surf[i] = sWater
			}
		}
	}

	// what covers the ground (ESA WorldCover, every 10 m): water, bare ground, paving of the towns, snow; grass and trees are the rest
	wc := newWC()
	for z := 0; z < G; z++ {
		for x := 0; x < G; x++ {
			lon := g.lon0 + (float64(x)+0.5)/mLon
			lat := g.lat0 - (float64(z)+0.5)/mLat
			i := g.idx(x, z)
			switch wc.class(lat, lon) {
			case 80: // water: the river bed is a metre lower
				if g.surf[i] != sWater && g.ground[i] > 0 {
					g.ground[i]--
				}
				g.surf[i] = sWater
			case 60:
				g.surf[i] = sDirt
			case 50:
				g.surf[i] = sPlaza
			case 70:
				g.surf[i] = sGravel
			}
		}
	}

	// roads
	widths := map[string]float64{"motorway": 10, "trunk": 9, "primary": 8, "secondary": 7, "tertiary": 6, "residential": 5, "unclassified": 5, "living_street": 4, "service": 3.5, "pedestrian": 4, "track": 3, "footway": 2, "path": 1.6, "steps": 2, "cycleway": 2}
	roads, err := qRoads()
	if err != nil {
		return nil, err
	}
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
			if sf := str(f.Properties, "surface"); sf == "paving_stones" || sf == "sett" || sf == "cobblestone" {
				m = sPlaza
			}
		}
		for _, l := range lines(f) {
			g.strokeLine(l, w2, func(x, z int) {
				if i := g.idx(x, z); g.surf[i] != sWater {
					g.surf[i] = m
				}
			})
		}
	}

	// buildings
	type bld struct {
		rings [][][2]float64
		tags  func(string) string
		part  bool
		id    int
	}
	var blds []bld
	sat := newSat(w, s, e, n)
	gh := newGhsl() // the typical height of the buildings around, for those that have no known height
	var l2 heightSource // the real heights and roofs: Berlin and the Netherlands have them
	if x := newLod2(w, s, e, n); x != nil {
		l2 = x
	} else if y := newNL(w, s, e, n); y != nil {
		l2 = y
	}
	fr := newFR(w, s, e, n) // France: the real height of the buildings (BD TOPO); nil elsewhere
	// the outlines come from Microsoft's building footprints (found in satellite pictures; the heights are guessed by their computer, -1 when unknown)
	mb, merr := msBuildings(w, s, e, n)
	if merr != nil {
		log.Printf("buildings: %v (the square gets the buildings that could be read)", merr)
	}
	for _, m := range mb {
		if m.conf > 0 && m.conf < 0.5 {
			continue // not sure that it is a building
		}
		hs, shape, rhs := "", "", ""
		if m.height > 0 {
			hs = strconv.FormatFloat(m.height, 'f', 1, 64)
		}
		if l2 != nil { // in Berlin: the real height and roof of the building that this one stands in
			var cx, cy float64
			for _, q := range m.ring {
				cx, cy = cx+q[0], cy+q[1]
			}
			if h, sh, rh, ok := l2.at(cx/float64(len(m.ring)), cy/float64(len(m.ring))); ok {
				hs, shape = strconv.FormatFloat(h, 'f', 1, 64), sh
				if rh > 0 {
					rhs = strconv.FormatFloat(rh, 'f', 1, 64)
				}
			}
		}
		id := int(int64(m.ring[0][0]*1e6) ^ int64(m.ring[0][1]*1e6))
		if id < 0 {
			id = -id
		}
		if fr != nil && l2 == nil { // in France: the real height of the building that this one stands in
			var cx, cy float64
			for _, q := range m.ring {
				cx, cy = cx+q[0], cy+q[1]
			}
			if h, _, _, ok := fr.at(cx/float64(len(m.ring)), cy/float64(len(m.ring))); ok {
				hs = strconv.FormatFloat(h, 'f', 1, 64)
			}
		}
		if hs == "" { // no height known: the typical one of the place (GHSL), a little different for every building
			var cx, cy float64
			for _, q := range m.ring {
				cx, cy = cx+q[0], cy+q[1]
			}
			if v, ok := gh.at(cy/float64(len(m.ring)), cx/float64(len(m.ring))); ok {
				hs = strconv.FormatFloat(math.Max(3, math.Min(80, v*(0.7+0.6*float64(hashN(id, 1000))/1000))), 'f', 1, 64)
			}
		}
		blds = append(blds, bld{rings: g.local([][]pt{m.ring}), tags: func(k string) string {
			switch k {
			case "height":
				return hs
			case "roof:shape":
				return shape
			case "roof:height":
				return rhs
			}
			return ""
		}, id: id})
	}
	if l2 == nil { // outside Berlin and the Netherlands: the building parts of Overture (towers on bases, spires, roofs of their own), on top of the outlines
		if ps, err := ovParts(w, s, e, n); err != nil {
			log.Printf("overture: %v (the square gets no building parts)", err)
		} else {
			// the parts of a tower are tiers: every one is a column from the ground, so the tall ones go last (a lower tier must not cut down the spire)
			sort.SliceStable(ps, func(i, j int) bool { return ps[i].H < ps[j].H })
			for _, p := range ps {
				if len(p.R) < 4 {
					continue
				}
				ring := make([]pt, len(p.R))
				for i, q := range p.R {
					ring[i] = pt{q[0], q[1]}
				}
				id := int(int64(p.R[0][0]*1e6) ^ int64(p.R[0][1]*1e6))
				if id < 0 {
					id = -id
				}
				hs, shape, rhs := strconv.FormatFloat(p.H, 'f', 1, 64), p.S, ""
				if p.RH > 0 {
					rhs = strconv.FormatFloat(p.RH, 'f', 1, 64)
				}
				blds = append(blds, bld{rings: g.local([][]pt{ring}), part: true, id: id, tags: func(k string) string {
					switch k {
					case "height":
						return hs
					case "roof:shape":
						return shape
					case "roof:height":
						return rhs
					}
					return ""
				}})
			}
		}
	}
	sort.SliceStable(blds, func(i, j int) bool { return !blds[i].part && blds[j].part }) // outlines first, parts on top of them
	for _, b := range blds {
		H := num(b.tags("height"))
		if math.IsNaN(H) {
			if lv := num(b.tags("building:levels")); !math.IsNaN(lv) {
				H = lv*3.2 + 1
			} else {
				H = 9
			}
		}
		// a square stores heights up to 255, and the game world is not higher than about 300 blocks: above 150 m the height is squeezed, so that the tiers of a
		// skyscraper stay different (a plain cut at 250 made every upper tier of the Burj Khalifa the same height, a box): 300 m -> 189, 500 m -> 219, 828 m -> 240
		if H > 150 {
			H = 150 + 100*(1-math.Exp(-(H-150)/300))
		}
		H = math.Max(2, math.Min(250, H))
		shape := b.tags("roof:shape")
		rh := num(b.tags("roof:height"))
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
		if !b.part && len(b.rings) > 0 && ringArea(b.rings[0]) >= 500 && hashN(b.id, 25) == 0 { // the same rule as in findDataCenter
			wall = 40 // a data center: one big building in 25 (the game knows it by this wall code)
		}
		// the roof has the colour that the satellite sees (the average of the roof, a little brighter because of the shadows)
		if sat != nil && len(cells) > 0 {
			var sr, sg, sb float64
			cnt := 0
			step := len(cells)/30 + 1
			for i := 0; i < len(cells); i += step {
				lon := g.lon0 + (float64(cells[i][0])+0.5)/mLon
				lat := g.lat0 - (float64(cells[i][1])+0.5)/mLat
				if r, gg, bb, ok := sat.color(lat, lon); ok {
					sr, sg, sb = sr+r, sg+gg, sb+bb
					cnt++
				}
			}
			if cnt > 0 {
				k := 1.2 / float64(cnt)
				roof = roofFromColor(math.Min(255, sr*k), math.Min(255, sg*k), math.Min(255, sb*k))
			}
		}
		for i, c := range cells {
			ix := g.idx(c[0], c[1])
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
		}
	}

	// trees (only the ones inside the square)
	// the trees stand where ESA WorldCover says "trees": about one in 28 blocks, not on streets, squares, water or buildings
	var trees []tree
	for lz := 0; lz < tile; lz++ {
		for lx := 0; lx < tile; lx++ {
			x, z := lx+work, lz+work
			seed := (tx*tile+lx)*7919 + (tz*tile+lz)*104729
			if hashN(seed, 28) != 0 {
				continue
			}
			ix := g.idx(x, z)
			if g.wallH[ix] > 0 || g.surf[ix] == sWater || g.surf[ix] == sAsphalt || g.surf[ix] == sPlaza || g.surf[ix] == sGravel {
				continue
			}
			lon := g.lon0 + (float64(x)+0.5)/mLon
			lat := g.lat0 - (float64(z)+0.5)/mLat
			if wc.class(lat, lon) != 10 {
				continue
			}
			trees = append(trees, tree{lx, lz, 6 + hashN(seed+17, 10)})
		}
	}

	// the file: "MCT2\n", a JSON header, then the arrays of P*P cells (the square and one block around it), all in one gzip
	hdr := map[string]any{"x": tx, "z": tz, "size": tile, "pad": pad, "p": P, "trees": trees, "made": time.Now().UTC().Format("2006-01-02")}
	hb, _ := json.Marshal(hdr)
	var buf bytes.Buffer
	gz, _ := gzip.NewWriterLevel(&buf, gzip.BestCompression)
	gz.Write([]byte("MCT2\n"))
	var l [4]byte
	binary.LittleEndian.PutUint32(l[:], uint32(len(hb)))
	gz.Write(l[:])
	gz.Write(hb)
	crop := func(a []uint8) []byte {
		out := make([]byte, 0, P*P)
		for z := 0; z < P; z++ {
			row := (work-pad+z)*G + (work - pad)
			out = append(out, a[row:row+P]...)
		}
		return out
	}
	gb := make([]byte, 0, P*P*2)
	for z := 0; z < P; z++ {
		for x := 0; x < P; x++ {
			v := g.ground[(work-pad+z)*G+(work-pad+x)]
			gb = append(gb, byte(v), byte(v>>8))
		}
	}
	gz.Write(gb)
	for _, a := range [][]uint8{g.surf, g.wallH, g.roofH, g.wallM, g.roofM} {
		gz.Write(crop(a))
	}
	gz.Close()
	return buf.Bytes(), nil
}

func hashN(id, n int) int {
	x := uint32(id)*2654435761 + 12345
	x ^= x >> 15
	return int(x % uint32(n))
}

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
	case "industrial", "warehouse", "commercial", "retail", "office":
		return wConcrete
	}
	return []uint8{wSandstone, wOchre, wPlaster, wBrick, wStoneBrick}[hashN(id, 5)]
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
	case "flat":
		return rStone
	}
	return []uint8{rTerracotta, rTerracotta, rSlate, rTerracotta}[hashN(id, 4)]
}

// ---- the service ----

type server struct {
	dir      string
	maxBytes int64
	buildSem chan struct{}
	mu       sync.Mutex
	inflight map[[2]int]*call
	limits   map[string]*bucket
	used     int64
	usedAt   time.Time
}

type call struct {
	done chan struct{}
	data []byte
	err  error
}

type bucket struct {
	tokens float64
	last   time.Time
}

// a visitor may start this many new squares in a minute (squares that are already on the disk do not count)
const buildsPerMinute = 24
const readsPerMinute = 600

func (s *server) allow(ip string, cost, perMin float64, kind string) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	k := kind + ip
	b := s.limits[k]
	now := time.Now()
	if b == nil {
		b = &bucket{tokens: perMin, last: now}
		s.limits[k] = b
		if len(s.limits) > 20000 {
			for kk, bb := range s.limits {
				if now.Sub(bb.last) > 10*time.Minute {
					delete(s.limits, kk)
				}
			}
		}
	}
	b.tokens = math.Min(perMin, b.tokens+now.Sub(b.last).Seconds()*perMin/60)
	b.last = now
	if b.tokens < cost {
		return false
	}
	b.tokens -= cost
	return true
}

func (s *server) path(x, z int) string {
	return filepath.Join(s.dir, strconv.Itoa(x), strconv.Itoa(z)+".mct")
}

func clientIP(r *http.Request) (string, bool) {
	host, _, _ := net.SplitHostPort(r.RemoteAddr)
	local := host == "127.0.0.1" || host == "::1"
	if v := r.Header.Get("CF-Connecting-IP"); v != "" {
		return v, false
	}
	return host, local
}

func (s *server) diskUsed() int64 {
	s.mu.Lock()
	if time.Since(s.usedAt) < time.Minute {
		u := s.used
		s.mu.Unlock()
		return u
	}
	s.mu.Unlock()
	var total int64
	filepath.Walk(s.dir, func(p string, info os.FileInfo, err error) error {
		if err == nil && !info.IsDir() {
			total += info.Size()
		}
		return nil
	})
	s.mu.Lock()
	s.used, s.usedAt = total, time.Now()
	s.mu.Unlock()
	return total
}

func (s *server) load(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Cache-Control", "no-store")
	if r.Method != http.MethodPost {
		http.Error(w, "POST {\"x\":0,\"z\":0}", 405)
		return
	}
	var q struct{ X, Z int }
	r.Body = http.MaxBytesReader(w, r.Body, 1024)
	var raw map[string]int
	if err := json.NewDecoder(r.Body).Decode(&raw); err != nil {
		http.Error(w, "bad request", 400)
		return
	}
	q.X, q.Z = raw["x"], raw["z"]
	if q.X < -maxRing || q.X > maxRing || q.Z < -maxRing || q.Z > maxRing {
		http.Error(w, "out of the world", 400)
		return
	}
	ip, local := clientIP(r)
	if !local && !s.allow(ip, 1, readsPerMinute, "r") {
		http.Error(w, "too many requests", 429)
		return
	}
	p := s.path(q.X, q.Z)
	if b, err := os.ReadFile(p); err == nil {
		w.Header().Set("Content-Type", "application/octet-stream")
		w.Header().Set("X-Tile", "disk")
		w.Write(b)
		return
	}
	if !local && !s.allow(ip, 1, buildsPerMinute, "b") {
		http.Error(w, "too many new squares, slow down", 429)
		return
	}
	if s.diskUsed() > s.maxBytes {
		http.Error(w, "the disk for squares is full", 507)
		return
	}
	data, err := s.build(q.X, q.Z)
	if err != nil {
		log.Println("tile", q.X, q.Z, "failed:", err)
		http.Error(w, "could not make this square: "+err.Error(), 502)
		return
	}
	w.Header().Set("Content-Type", "application/octet-stream")
	w.Header().Set("X-Tile", "new")
	w.Write(data)
}

// one maker per square: when ten people ask for the same new square it is made once
func (s *server) build(x, z int) ([]byte, error) {
	key := [2]int{x, z}
	s.mu.Lock()
	if c, ok := s.inflight[key]; ok {
		s.mu.Unlock()
		<-c.done
		return c.data, c.err
	}
	c := &call{done: make(chan struct{})}
	s.inflight[key] = c
	s.mu.Unlock()
	go func() {
		s.buildSem <- struct{}{}
		defer func() { <-s.buildSem }()
		t0 := time.Now()
		data, err := buildTile(x, z)
		if err == nil {
			p := s.path(x, z)
			if e := os.MkdirAll(filepath.Dir(p), 0o755); e == nil {
				tmp := p + ".tmp"
				if e := os.WriteFile(tmp, data, 0o644); e == nil {
					os.Rename(tmp, p)
				}
			}
			log.Printf("tile %d,%d made in %.1fs, %d KB", x, z, time.Since(t0).Seconds(), len(data)/1024)
		}
		c.data, c.err = data, err
		s.mu.Lock()
		delete(s.inflight, key)
		s.mu.Unlock()
		close(c.done)
	}()
	<-c.done
	return c.data, c.err
}

// ---- the data centers: one big building in 25 (the rule is used when a square is made, and here to find them) ----

func ringArea(r [][2]float64) float64 { // square metres of a ring in blocks (the shoelace formula)
	a := 0.0
	for i, j := 0, len(r)-1; i < len(r); i, j = i+1, i {
		a += r[j][0]*r[i][1] - r[i][0]*r[j][1]
	}
	return math.Abs(a) / 2
}

// the nearest data center to a place, in the building files that are on the disk already (no download is started); false when there is none within about 15 km
func findDataCenter(lat, lon float64) (dlat, dlon, area float64, ok bool) {
	cx, cy := tileXY(lat, lon, 13)
	best := 1e18
	for x := cx - 3; x <= cx+3; x++ {
		for y := cy - 3; y <= cy+3; y++ {
			tlat, tlon := tileCenter(x, y, 13)
			qx, qy := tileXY(tlat, tlon, 9)
			qk := quadkey(qx, qy, 9)
			if _, err := os.Stat(filepath.Join(msDir, "z9v2", qk+".done")); err != nil {
				continue
			}
			b, err := os.ReadFile(filepath.Join(msDir, "z9v2", qk, fmt.Sprintf("%d_%d.bin", x, y)))
			if err != nil {
				continue
			}
			for p := 0; p+12 <= len(b); {
				cnt := int(binary.LittleEndian.Uint16(b[p:]))
				c := -1.0
				if b[p+3] != 255 {
					c = float64(b[p+3]) / 100
				}
				ln, la := int32(binary.LittleEndian.Uint32(b[p+4:])), int32(binary.LittleEndian.Uint32(b[p+8:]))
				p += 12
				if cnt < 1 || p+(cnt-1)*4 > len(b) {
					break
				}
				lon0, lat0 := float64(ln)/1e7, float64(la)/1e7
				ring := make([][2]float64, cnt)
				ring[0] = [2]float64{0, 0}
				var sx, sz float64
				for i := 1; i < cnt; i++ {
					ln += int32(int16(binary.LittleEndian.Uint16(b[p:])))
					la += int32(int16(binary.LittleEndian.Uint16(b[p+2:])))
					p += 4
					ring[i] = [2]float64{(float64(ln)/1e7 - lon0) * mLon, (lat0 - float64(la)/1e7) * mLat}
					sx += ring[i][0]
					sz += ring[i][1]
				}
				if c > 0 && c < 0.5 {
					continue // the same buildings are left out as when a square is made
				}
				id := int(int64(lon0*1e6) ^ int64(lat0*1e6))
				if id < 0 {
					id = -id
				}
				if a := ringArea(ring); a >= 500 && hashN(id, 25) == 0 {
					mlon, mlat := lon0+sx/float64(cnt)/mLon, lat0-sz/float64(cnt)/mLat
					d := math.Hypot((mlon-lon)*mLon, (mlat-lat)*mLat)
					if d < best {
						best, dlat, dlon, area, ok = d, mlat, mlon, a, true
					}
				}
			}
		}
	}
	return
}

func (s *server) datacenter(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Cache-Control", "no-store")
	if _, local := clientIP(r); !local || r.Method != http.MethodPost {
		http.Error(w, "not here", 403)
		return
	}
	var q struct{ Lat, Lon float64 }
	r.Body = http.MaxBytesReader(w, r.Body, 1024)
	if err := json.NewDecoder(r.Body).Decode(&q); err != nil {
		http.Error(w, "bad request", 400)
		return
	}
	lat, lon, area, ok := findDataCenter(q.Lat, q.Lon)
	if !ok {
		json.NewEncoder(w).Encode(map[string]any{"found": false})
		return
	}
	json.NewEncoder(w).Encode(map[string]any{"found": true, "lat": lat, "lon": lon, "area": area})
}

func (s *server) status(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Cache-Control", "no-store")
	json.NewEncoder(w).Encode(map[string]any{"tileSize": tile, "pad": pad, "refLon": refLon, "refLat": refLat, "mLon": mLon, "mLat": mLat, "bytes": s.diskUsed(), "format": "MCT2"})
}

func main() {
	dir := flag.String("dir", "/media/data/tiles-data", "where the squares are kept")
	addr := flag.String("addr", "127.0.0.1:8858", "address to listen on")
	maxGB := flag.Float64("max-gb", 60, "stop making new squares when the folder is this big")
	builders := flag.Int("builders", 2, "squares made at the same time")
	go msPrewarm()
	flag.Parse()
	if err := os.MkdirAll(*dir, 0o755); err != nil {
		log.Fatal(err)
	}
	s := &server{dir: *dir, maxBytes: int64(*maxGB * 1e9), buildSem: make(chan struct{}, *builders), inflight: map[[2]int]*call{}, limits: map[string]*bucket{}}
	mux := http.NewServeMux()
	mux.HandleFunc("/tiles/3d/load", s.load)
	mux.HandleFunc("/tiles/3d/info", s.status)
	mux.HandleFunc("/tiles/3d/datacenter", s.datacenter)
	log.Println("mctiles listening on", *addr, "folder", *dir)
	srv := &http.Server{Addr: *addr, Handler: mux, ReadHeaderTimeout: 10 * time.Second, ReadTimeout: 20 * time.Second, WriteTimeout: 4 * time.Minute}
	log.Fatal(srv.ListenAndServe())
}
