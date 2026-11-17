// landmarkvox turns 3D landmark models (GLB, in metres, Y up, X east, Z south) into blocks, one block per metre.
//
//	single model:   landmarkvox -in model.glb -lon 2.2945 -lat 48.8583 -name "Tour Eiffel" -out eiffel.lm.gz
//	Open Landmarks: landmarkvox -mode ol  -src data/ol -out ../../landmarks         (data/ol/<id>/detail.glb + asset.json)
//	CC0 library:    landmarkvox -mode awl -catalog data/awl-catalog.json -src data/awl -out ../../landmarks   (data/awl/<model>.glb)
//
// Only the surface of a model becomes blocks (a lattice stays a lattice). The colour of every block is the colour of the material it was cut from; the plugin
// and the web page pick the nearest block of their palette. A model is one gzip file of text: the first line is the number of blocks, then one line per block,
// "x y z rrggbb" (x east, y up, z south, counted from the anchor point), sorted by x, z, y (so that it packs well).
// index-<mode>.txt has one line per landmark, separated by tabs: id, name, lon, lat, x0, x1, z0, z1, height, elevation, file, licence.
package main

import (
	"bytes"
	"compress/gzip"
	"encoding/binary"
	"encoding/json"
	"flag"
	"fmt"
	"log"
	"math"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
)

type gltf struct {
	Scene  int `json:"scene"`
	Scenes []struct {
		Nodes []int `json:"nodes"`
	} `json:"scenes"`
	Nodes []struct {
		Mesh        *int      `json:"mesh"`
		Children    []int     `json:"children"`
		Matrix      []float64 `json:"matrix"`
		Translation []float64 `json:"translation"`
		Rotation    []float64 `json:"rotation"`
		Scale       []float64 `json:"scale"`
	} `json:"nodes"`
	Meshes []struct {
		Primitives []struct {
			Attributes map[string]int `json:"attributes"`
			Indices    *int           `json:"indices"`
			Material   *int           `json:"material"`
			Mode       *int           `json:"mode"`
		} `json:"primitives"`
	} `json:"meshes"`
	Materials []struct {
		PBR struct {
			Base []float64 `json:"baseColorFactor"`
		} `json:"pbrMetallicRoughness"`
	} `json:"materials"`
	Accessors []struct {
		BufferView *int   `json:"bufferView"`
		ByteOffset int    `json:"byteOffset"`
		Component  int    `json:"componentType"`
		Count      int    `json:"count"`
		Type       string `json:"type"`
	} `json:"accessors"`
	BufferViews []struct {
		ByteOffset int `json:"byteOffset"`
		ByteLength int `json:"byteLength"`
		ByteStride int `json:"byteStride"`
	} `json:"bufferViews"`
}

type mat [16]float64 // column-major, like glTF

func ident() mat { return mat{1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1} }

func mul(a, b mat) mat { // a * b
	var o mat
	for c := 0; c < 4; c++ {
		for r := 0; r < 4; r++ {
			var s float64
			for k := 0; k < 4; k++ {
				s += a[k*4+r] * b[c*4+k]
			}
			o[c*4+r] = s
		}
	}
	return o
}

func trs(t, q, s []float64) mat {
	m := ident()
	x, y, z, w := 0.0, 0.0, 0.0, 1.0
	if len(q) == 4 {
		x, y, z, w = q[0], q[1], q[2], q[3]
	}
	sx, sy, sz := 1.0, 1.0, 1.0
	if len(s) == 3 {
		sx, sy, sz = s[0], s[1], s[2]
	}
	m[0], m[1], m[2] = (1-2*(y*y+z*z))*sx, (2*(x*y+z*w))*sx, (2*(x*z-y*w))*sx
	m[4], m[5], m[6] = (2*(x*y-z*w))*sy, (1-2*(x*x+z*z))*sy, (2*(y*z+x*w))*sy
	m[8], m[9], m[10] = (2*(x*z+y*w))*sz, (2*(y*z-x*w))*sz, (1-2*(x*x+y*y))*sz
	if len(t) == 3 {
		m[12], m[13], m[14] = t[0], t[1], t[2]
	}
	return m
}

func apply(m mat, p [3]float64) [3]float64 {
	return [3]float64{m[0]*p[0] + m[4]*p[1] + m[8]*p[2] + m[12], m[1]*p[0] + m[5]*p[1] + m[9]*p[2] + m[13], m[2]*p[0] + m[6]*p[1] + m[10]*p[2] + m[14]}
}

func dist(a, b [3]float64) float64 {
	return math.Sqrt(math.Pow(a[0]-b[0], 2) + math.Pow(a[1]-b[1], 2) + math.Pow(a[2]-b[2], 2))
}

// glTF colours are linear; the plugin compares them with the colours of blocks as they look (sRGB)
func lin(v float64) int {
	v = math.Max(0, math.Min(1, v))
	if v <= 0.0031308 {
		v *= 12.92
	} else {
		v = 1.055*math.Pow(v, 1/2.4) - 0.055
	}
	return int(math.Round(v * 255))
}

type result struct {
	keys   [][3]int32
	colors map[[3]int32]uint32
	tris   int
}

// voxelize: the model, turned clockwise by bearing degrees (as on a compass, seen from above) and made scale times bigger, as blocks of 1 m
func voxelize(raw []byte, scale, bearing float64) (*result, error) {
	if len(raw) < 20 || string(raw[:4]) != "glTF" {
		return nil, fmt.Errorf("not a GLB file")
	}
	var js, bin []byte
	for p := 12; p+8 <= len(raw); {
		n := int(binary.LittleEndian.Uint32(raw[p:]))
		typ := binary.LittleEndian.Uint32(raw[p+4:])
		if p+8+n > len(raw) {
			break
		}
		switch typ {
		case 0x4E4F534A:
			js = raw[p+8 : p+8+n]
		case 0x004E4942:
			bin = raw[p+8 : p+8+n]
		}
		p += 8 + n + (4-n%4)%4
	}
	var g gltf
	if err := json.Unmarshal(js, &g); err != nil {
		return nil, err
	}
	if len(g.Scenes) == 0 {
		return nil, fmt.Errorf("no scene")
	}
	acc := func(i int) (data []byte, count, comp, stride int) {
		a := g.Accessors[i]
		bv := g.BufferViews[*a.BufferView]
		ncomp := map[string]int{"SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4}[a.Type]
		size := map[int]int{5120: 1, 5121: 1, 5122: 2, 5123: 2, 5125: 4, 5126: 4}[a.Component]
		stride = bv.ByteStride
		if stride == 0 {
			stride = size * ncomp
		}
		return bin[bv.ByteOffset+a.ByteOffset:], a.Count, a.Component, stride
	}
	res := &result{colors: map[[3]int32]uint32{}}
	sinB, cosB := math.Sin(bearing*math.Pi/180), math.Cos(bearing*math.Pi/180)
	// puts the points of a triangle into blocks: one sample at least every 0.35 of a block along the longest edge
	rast := func(a, b, c [3]float64, col uint32) {
		long := math.Max(dist(a, b), math.Max(dist(b, c), dist(c, a)))
		n := int(math.Ceil(long/0.35)) + 1
		for i := 0; i <= n; i++ {
			for j := 0; j <= n-i; j++ {
				u, v := float64(i)/float64(n), float64(j)/float64(n)
				w := 1 - u - v
				p := [3]float64{a[0]*w + b[0]*u + c[0]*v, a[1]*w + b[1]*u + c[1]*v, a[2]*w + b[2]*u + c[2]*v}
				res.colors[[3]int32{int32(math.Floor(p[0])), int32(math.Floor(p[1])), int32(math.Floor(p[2]))}] = col
			}
		}
		res.tris++
	}
	var walk func(ni int, parent mat) error
	walk = func(ni int, parent mat) error {
		nd := g.Nodes[ni]
		local := ident()
		if len(nd.Matrix) == 16 {
			copy(local[:], nd.Matrix)
		} else {
			local = trs(nd.Translation, nd.Rotation, nd.Scale)
		}
		world := mul(parent, local)
		if nd.Mesh != nil {
			for _, pr := range g.Meshes[*nd.Mesh].Primitives {
				if pr.Mode != nil && *pr.Mode != 4 {
					continue
				}
				pa, ok := pr.Attributes["POSITION"]
				if !ok || g.Accessors[pa].BufferView == nil {
					continue
				}
				pd, pcount, _, pstride := acc(pa)
				pos := make([][3]float64, pcount)
				for i := range pos {
					o := i * pstride
					q := apply(world, [3]float64{
						float64(math.Float32frombits(binary.LittleEndian.Uint32(pd[o:]))),
						float64(math.Float32frombits(binary.LittleEndian.Uint32(pd[o+4:]))),
						float64(math.Float32frombits(binary.LittleEndian.Uint32(pd[o+8:])))})
					// the scale, then the turn (north goes to the east when the bearing is 90)
					x, y, z := q[0]*scale, q[1]*scale, q[2]*scale
					pos[i] = [3]float64{x*cosB - z*sinB, y, x*sinB + z*cosB}
				}
				col := uint32(0xB0B0B0)
				if pr.Material != nil && *pr.Material < len(g.Materials) {
					if b := g.Materials[*pr.Material].PBR.Base; len(b) >= 3 {
						col = uint32(lin(b[0]))<<16 | uint32(lin(b[1]))<<8 | uint32(lin(b[2]))
					}
				}
				var idx []int
				if pr.Indices != nil {
					id, icount, comp, istride := acc(*pr.Indices)
					for i := 0; i < icount; i++ {
						o := i * istride
						switch comp {
						case 5121:
							idx = append(idx, int(id[o]))
						case 5123:
							idx = append(idx, int(binary.LittleEndian.Uint16(id[o:])))
						default:
							idx = append(idx, int(binary.LittleEndian.Uint32(id[o:])))
						}
					}
				} else {
					for i := 0; i < pcount; i++ {
						idx = append(idx, i)
					}
				}
				for i := 0; i+2 < len(idx); i += 3 {
					if idx[i] >= pcount || idx[i+1] >= pcount || idx[i+2] >= pcount {
						return fmt.Errorf("index out of range")
					}
					rast(pos[idx[i]], pos[idx[i+1]], pos[idx[i+2]], col)
				}
			}
		}
		for _, c := range nd.Children {
			if err := walk(c, world); err != nil {
				return err
			}
		}
		return nil
	}
	sc := g.Scene
	if sc >= len(g.Scenes) {
		sc = 0
	}
	for _, n := range g.Scenes[sc].Nodes {
		if err := walk(n, ident()); err != nil {
			return nil, err
		}
	}
	res.keys = make([][3]int32, 0, len(res.colors))
	for k := range res.colors {
		if k[1] >= 0 { // below the ground: left out
			res.keys = append(res.keys, k)
		}
	}
	sort.Slice(res.keys, func(i, j int) bool {
		a, b := res.keys[i], res.keys[j]
		if a[0] != b[0] {
			return a[0] < b[0]
		}
		if a[2] != b[2] {
			return a[2] < b[2]
		}
		return a[1] < b[1]
	})
	return res, nil
}

// the file of a model (gzip of text) and its line of the index
func write(res *result, id, name string, lon, lat float64, elev int, licence, outDir string) (string, error) {
	if len(res.keys) == 0 {
		return "", fmt.Errorf("no blocks")
	}
	var sb strings.Builder
	fmt.Fprintf(&sb, "%d\n", len(res.keys))
	x0, x1, z0, z1, h := int32(1<<30), int32(-1<<30), int32(1<<30), int32(-1<<30), int32(0)
	for _, k := range res.keys {
		fmt.Fprintf(&sb, "%d %d %d %06x\n", k[0], k[1], k[2], res.colors[k])
		x0, x1, z0, z1 = min(x0, k[0]), max(x1, k[0]), min(z0, k[2]), max(z1, k[2])
		h = max(h, k[1]+1)
	}
	var buf bytes.Buffer
	gz, _ := gzip.NewWriterLevel(&buf, gzip.BestCompression)
	gz.Write([]byte(sb.String()))
	gz.Close()
	file := id + ".lm.gz"
	if err := os.WriteFile(filepath.Join(outDir, file), buf.Bytes(), 0o644); err != nil {
		return "", err
	}
	name = strings.NewReplacer("\t", " ", "\n", " ").Replace(name)
	return fmt.Sprintf("%s\t%s\t%.7f\t%.7f\t%d\t%d\t%d\t%d\t%d\t%d\t%s\t%s", id, name, lon, lat, x0, x1, z0, z1, h, elev, file, licence), nil
}

func main() {
	mode := flag.String("mode", "", "ol, awl, or empty for one model")
	src := flag.String("src", "", "the folder of the models (modes ol and awl)")
	catalog := flag.String("catalog", "", "catalog.json of the CC0 library (mode awl)")
	out := flag.String("out", "", "the file (one model) or the folder (modes ol and awl)")
	in := flag.String("in", "", "one model: the GLB file")
	lon := flag.Float64("lon", 0, "one model: longitude of the point 0, 0, 0")
	lat := flag.Float64("lat", 0, "one model: latitude of the point 0, 0, 0")
	name := flag.String("name", "", "one model: the name")
	flag.Parse()

	switch *mode {
	case "":
		raw, err := os.ReadFile(*in)
		if err != nil {
			log.Fatal(err)
		}
		res, err := voxelize(raw, 1, 0)
		if err != nil {
			log.Fatal(err)
		}
		dir := filepath.Dir(*out)
		id := strings.TrimSuffix(filepath.Base(*out), ".lm.gz")
		line, err := write(res, id, *name, *lon, *lat, 0, "", dir)
		if err != nil {
			log.Fatal(err)
		}
		fmt.Println(line)
	case "ol":
		ents, _ := os.ReadDir(*src)
		var lines []string
		var mu sync.Mutex
		var wg sync.WaitGroup
		sem := make(chan struct{}, 4)
		for _, e := range ents {
			if !e.IsDir() {
				continue
			}
			wg.Add(1)
			sem <- struct{}{}
			go func(id string) {
				defer wg.Done()
				defer func() { <-sem }()
				raw, err1 := os.ReadFile(filepath.Join(*src, id, "detail.glb"))
				aj, err2 := os.ReadFile(filepath.Join(*src, id, "asset.json"))
				if err1 != nil || err2 != nil {
					log.Printf("%s: files missing", id)
					return
				}
				var a struct {
					Name   string    `json:"name"`
					Anchor []float64 `json:"anchor"`
				}
				if json.Unmarshal(aj, &a) != nil || len(a.Anchor) != 2 {
					log.Printf("%s: asset.json not understood", id)
					return
				}
				res, err := voxelize(raw, 1, 0) // the heading is baked into the model
				if err != nil {
					log.Printf("%s: %v", id, err)
					return
				}
				line, err := write(res, "ol-"+id, a.Name, a.Anchor[0], a.Anchor[1], 0, "CC BY 4.0 Open Landmarks", *out)
				if err != nil {
					log.Printf("%s: %v", id, err)
					return
				}
				mu.Lock()
				lines = append(lines, line)
				mu.Unlock()
			}(e.Name())
		}
		wg.Wait()
		sort.Strings(lines)
		os.WriteFile(filepath.Join(*out, "index-ol.txt"), []byte(strings.Join(lines, "\n")+"\n"), 0o644)
		fmt.Println("open landmarks:", len(lines), "landmarks")
	case "awl":
		cb, err := os.ReadFile(*catalog)
		if err != nil {
			log.Fatal(err)
		}
		var c struct {
			Landmarks []struct {
				ID        string   `json:"id"`
				Name      string   `json:"name"`
				Model     string   `json:"model"`
				Lng       float64  `json:"lng"`
				Lat       float64  `json:"lat"`
				Bearing   float64  `json:"bearing"`
				Elevation float64  `json:"elevation"`
				Scale     *float64 `json:"scale"`
			} `json:"landmarks"`
		}
		if err := json.Unmarshal(cb, &c); err != nil {
			log.Fatal(err)
		}
		var lines []string
		var mu sync.Mutex
		var wg sync.WaitGroup
		sem := make(chan struct{}, 4)
		for _, l := range c.Landmarks {
			wg.Add(1)
			sem <- struct{}{}
			go func() {
				defer wg.Done()
				defer func() { <-sem }()
				raw, err := os.ReadFile(filepath.Join(*src, l.Model+".glb"))
				if err != nil {
					log.Printf("%s: model missing", l.ID)
					return
				}
				sc := 1.0
				if l.Scale != nil && *l.Scale > 0 {
					sc = *l.Scale
				}
				res, err := voxelize(raw, sc, l.Bearing)
				if err != nil {
					log.Printf("%s: %v", l.ID, err)
					return
				}
				line, err := write(res, "awl-"+l.ID, l.Name, l.Lng, l.Lat, int(math.Round(l.Elevation)), "CC0 Alex Wohlbruck", *out)
				if err != nil {
					log.Printf("%s: %v", l.ID, err)
					return
				}
				mu.Lock()
				lines = append(lines, line)
				mu.Unlock()
			}()
		}
		wg.Wait()
		sort.Strings(lines)
		os.WriteFile(filepath.Join(*out, "index-awl.txt"), []byte(strings.Join(lines, "\n")+"\n"), 0o644)
		fmt.Println("cc0 library:", len(lines), "landmarks")
	default:
		log.Fatalf("unknown mode %q", *mode)
	}
}
