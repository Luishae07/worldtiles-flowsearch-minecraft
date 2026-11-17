package main

import (
	"bufio"
	"encoding/binary"
	"fmt"
	"math"
	"os"
	"strconv"
	"strings"
)

// ---- crops of the satellite picture, for training a neural network (see tools/roofml/train) ----
//
//	mctiles -roofdata 13.41,52.50,13.43,52.51 -crops -satzoom 18 -roofout crops.bin
//
// One record for every building of the box where the city model (Berlin, Brandenburg) says what it really is. A record is fixed in size:
//
//	cropSize x cropSize x 4 bytes   the picture north up, 0.5 m per pixel, around the middle of the building: red, green, blue, and 255 inside the outline of the building (else 0)
//	float32 height                  the real height of the building in metres (the city model)
//	float32 roofHeight              how far the roof rises above the eaves
//	uint8   roofClass               0 flat, 1 sloped (gabled, hipped, ...), 255 not known
//	float32 msHeight                Microsoft's own guess of the height (-1 when there is none)
//	float32 area                    square metres of the outline

const (
	cropSize = 96
	cropM    = 0.5 // metres per pixel
	cropRec  = cropSize*cropSize*4 + 4 + 4 + 1 + 4 + 4
)

func dumpCrops(box, out string) error {
	p := strings.Split(box, ",")
	if len(p) != 4 {
		return fmt.Errorf("-roofdata needs west,south,east,north")
	}
	var v [4]float64
	for i := range v {
		f, err := strconv.ParseFloat(strings.TrimSpace(p[i]), 64)
		if err != nil {
			return err
		}
		v[i] = f
	}
	w, s, e, n := v[0], v[1], v[2], v[3]
	l2 := newLod2(w, s, e, n)
	if l2 == nil {
		return fmt.Errorf("this box has no model of Berlin or Brandenburg")
	}
	mb, err := msBuildings(w, s, e, n)
	if err != nil {
		return err
	}
	fmt.Fprintf(os.Stderr, "%d outlines; getting the satellite pictures (zoom %d) ...\n", len(mb), satZoom)
	sat := newSat(w, s, e, n)
	fmt.Fprintf(os.Stderr, "%d pictures\n", len(sat.tiles))
	f, err := os.Create(out)
	if err != nil {
		return err
	}
	defer f.Close()
	bw := bufio.NewWriterSize(f, 1<<20)
	defer bw.Flush()
	rec := make([]byte, cropRec)
	kept := 0
	for _, m := range mb {
		if len(m.ring) < 4 || (m.conf > 0 && m.conf < 0.5) {
			continue
		}
		var cx, cy float64
		for _, q := range m.ring {
			cx, cy = cx+q[0], cy+q[1]
		}
		cx /= float64(len(m.ring))
		cy /= float64(len(m.ring))
		h, shape, rh, ok := l2.at(cx, cy)
		if !ok || h <= 0 || h > 80 {
			continue
		}
		mLon := 111320 * math.Cos(cy*math.Pi/180)
		pts := make([][2]float64, len(m.ring))
		for i, q := range m.ring {
			pts[i] = [2]float64{(q[0] - cx) * mLon, -(q[1] - cy) * 110540}
		}
		area := polyArea(pts)
		if area < 20 || area > 4000 {
			continue
		}
		haveAll := true
		for j := 0; j < cropSize && haveAll; j++ {
			for i := 0; i < cropSize; i++ {
				x, z := (float64(i)-cropSize/2+0.5)*cropM, (float64(j)-cropSize/2+0.5)*cropM
				r, g, b, ok := sat.color(cy-z/110540, cx+x/mLon)
				if !ok {
					haveAll = false
					break
				}
				o := (j*cropSize + i) * 4
				rec[o], rec[o+1], rec[o+2] = byte(r), byte(g), byte(b)
				rec[o+3] = 0
				if pointInPoly(x, z, pts) {
					rec[o+3] = 255
				}
			}
		}
		if !haveAll {
			continue
		}
		o := cropSize * cropSize * 4
		binary.LittleEndian.PutUint32(rec[o:], math.Float32bits(float32(h)))
		binary.LittleEndian.PutUint32(rec[o+4:], math.Float32bits(float32(rh)))
		class := byte(255)
		switch shape {
		case "flat":
			class = 0
		case "gabled", "hipped", "pyramidal", "mansard", "half-hipped", "gambrel":
			class = 1
		}
		rec[o+8] = class
		ms := float32(-1)
		if m.height > 0 {
			ms = float32(m.height)
		}
		binary.LittleEndian.PutUint32(rec[o+9:], math.Float32bits(ms))
		binary.LittleEndian.PutUint32(rec[o+13:], math.Float32bits(float32(area)))
		bw.Write(rec)
		kept++
	}
	fmt.Fprintf(os.Stderr, "%d buildings written to %s (%d bytes each)\n", kept, out, cropRec)
	return nil
}
