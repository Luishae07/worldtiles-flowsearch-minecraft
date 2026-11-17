package main

import (
	"bufio"
	"fmt"
	"math"
	"os"
	"strconv"
	"strings"
)

// ---- measurements of roofs, for learning how to guess a roof from the satellite picture ----
//
//	mctiles -roofdata 13.41,52.50,13.423,52.509 -satzoom 18 -roofout roofs.csv
//
// For every building of a box in Berlin (Microsoft's outline) where the city model says what the roof really is, one line: measurements of the satellite
// picture inside the outline (brightness of the halves and sides of the roof, how long and thin it is, the texture, the colour), then the truth (the shape
// of the roof, the height, the height of the roof itself). roofml learns from this file which measurements mean which roof.

func polyArea(p [][2]float64) float64 {
	a := 0.0
	for i, j := 0, len(p)-1; i < len(p); j, i = i, i+1 {
		a += p[j][0]*p[i][1] - p[i][0]*p[j][1]
	}
	return math.Abs(a) / 2
}

func pointInPoly(x, z float64, p [][2]float64) bool {
	in := false
	for i, j := 0, len(p)-1; i < len(p); j, i = i, i+1 {
		if (p[i][1] > z) != (p[j][1] > z) && x < (p[j][0]-p[i][0])*(z-p[i][1])/(p[j][1]-p[i][1])+p[i][0] {
			in = !in
		}
	}
	return in
}

var roofFeatureNames = []string{"area", "n", "elong", "lmean", "lstd", "rmean", "gmean", "bmean", "sat", "cminor", "cmajor", "cquad", "csect", "grad", "nv", "rect", "dns", "dew", "c2", "s2", "ratio"}

// the measurements of the roof inside an outline (lon, lat points); ok is false when there is too little to measure
func roofFeatures(sat *satSampler, ring []pt) ([]float64, bool) {
	var lon0, lat0 float64
	for _, q := range ring {
		lon0, lat0 = lon0+q[0], lat0+q[1]
	}
	lon0 /= float64(len(ring))
	lat0 /= float64(len(ring))
	mLat := 110540.0
	mLon := 111320 * math.Cos(lat0*math.Pi/180)
	pts := make([][2]float64, len(ring))
	minx, maxx, minz, maxz := 1e9, -1e9, 1e9, -1e9
	for i, q := range ring {
		x, z := (q[0]-lon0)*mLon, -(q[1]-lat0)*mLat
		pts[i] = [2]float64{x, z}
		minx, maxx, minz, maxz = math.Min(minx, x), math.Max(maxx, x), math.Min(minz, z), math.Max(maxz, z)
	}
	area := polyArea(pts)
	if area < 20 || area > 4000 {
		return nil, false
	}
	step := math.Max(0.4, math.Sqrt(area/1500))
	nx, nz := int((maxx-minx)/step)+1, int((maxz-minz)/step)+1
	grid := make([]float64, nx*nz)
	for i := range grid {
		grid[i] = -1
	}
	type smp struct{ x, z, L, r, g, b float64 }
	var ss []smp
	for j := 0; j < nz; j++ {
		for i := 0; i < nx; i++ {
			x, z := minx+(float64(i)+0.5)*step, minz+(float64(j)+0.5)*step
			if !pointInPoly(x, z, pts) {
				continue
			}
			near := 1e9 // the edge of the roof is left out: the pixels there are walls, shadows and the ground
			for a, b := 0, len(pts)-1; a < len(pts); b, a = a, a+1 {
				near = math.Min(near, segDist(x, z, pts[b][0], pts[b][1], pts[a][0], pts[a][1]))
			}
			if near < 0.7 {
				continue
			}
			r, g, b, ok := sat.color(lat0-z/mLat, lon0+x/mLon)
			if !ok {
				continue
			}
			L := 0.299*r + 0.587*g + 0.114*b
			grid[j*nx+i] = L
			ss = append(ss, smp{x, z, L, r, g, b})
		}
	}
	if len(ss) < 30 {
		return nil, false
	}
	n := float64(len(ss))
	var cx, cz, lm, rm, gm, bm float64
	for _, s := range ss {
		cx, cz, lm, rm, gm, bm = cx+s.x, cz+s.z, lm+s.L, rm+s.r, gm+s.g, bm+s.b
	}
	cx, cz, lm, rm, gm, bm = cx/n, cz/n, lm/n, rm/n, gm/n, bm/n
	var sxx, szz, sxz, ls float64
	for _, s := range ss {
		dx, dz := s.x-cx, s.z-cz
		sxx, szz, sxz, ls = sxx+dx*dx, szz+dz*dz, sxz+dx*dz, ls+(s.L-lm)*(s.L-lm)
	}
	sxx, szz, sxz = sxx/n, szz/n, sxz/n
	th := 0.5 * math.Atan2(2*sxz, sxx-szz) // the long direction of the roof
	c, sn := math.Cos(th), math.Sin(th)
	tr, det := sxx+szz, sxx*szz-sxz*sxz
	disc := math.Sqrt(math.Max(0, tr*tr/4-det))
	l1, l2 := tr/2+disc, math.Max(1e-6, tr/2-disc)
	elong := math.Sqrt(l1 / l2)
	// the brightness on both sides of the middle line (along and across), in the four quarters, and in the four triangles (as a hipped roof has)
	var sumV, cntV [2]float64
	var sumU, cntU [2]float64
	var sumQ, cntQ [4]float64
	var sumS, cntS [4]float64
	var sumNS, cntNS, sumEW, cntEW [2]float64 // south and north halves, east and west halves (compass, not the axis of the roof): the sun lights the slopes differently
	for _, s := range ss {
		dx, dz := s.x-cx, s.z-cz
		u, v := dx*c+dz*sn, -dx*sn+dz*c
		in, ie := 0, 0
		if dz > 0 { // z grows to the south
			in = 1
		}
		if dx > 0 {
			ie = 1
		}
		sumNS[in] += s.L
		cntNS[in]++
		sumEW[ie] += s.L
		cntEW[ie]++
		iv, iu := 0, 0
		if v >= 0 {
			iv = 1
		}
		if u >= 0 {
			iu = 1
		}
		sumV[iv] += s.L
		cntV[iv]++
		sumU[iu] += s.L
		cntU[iu]++
		sumQ[iu*2+iv] += s.L
		cntQ[iu*2+iv]++
		sec := int(math.Floor((math.Atan2(v, u)+math.Pi+math.Pi/4)/(math.Pi/2))) % 4
		sumS[sec] += s.L
		cntS[sec]++
	}
	contrast := func(a, b, ca, cb float64) float64 {
		if ca < 8 || cb < 8 {
			return 0
		}
		ma, mb := a/ca, b/cb
		return math.Abs(ma-mb) / (0.5*(ma+mb) + 1e-9)
	}
	spread := func(sum, cnt []float64) float64 {
		lo, hi := 1e18, -1e18
		for i := range sum {
			if cnt[i] < 5 {
				return 0
			}
			m := sum[i] / cnt[i]
			lo, hi = math.Min(lo, m), math.Max(hi, m)
		}
		return (hi - lo) / (0.5*(hi+lo) + 1e-9)
	}
	var gsum, gcnt float64
	for j := 0; j < nz; j++ {
		for i := 0; i < nx; i++ {
			v := grid[j*nx+i]
			if v < 0 {
				continue
			}
			if i+1 < nx && grid[j*nx+i+1] >= 0 {
				gsum += math.Abs(grid[j*nx+i+1] - v)
				gcnt++
			}
			if j+1 < nz && grid[(j+1)*nx+i] >= 0 {
				gsum += math.Abs(grid[(j+1)*nx+i] - v)
				gcnt++
			}
		}
	}
	grad := 0.0
	if gcnt > 0 {
		grad = gsum / gcnt / (lm + 1e-9)
	}
	mx, mn := math.Max(rm, math.Max(gm, bm)), math.Min(rm, math.Min(gm, bm))
	signed := func(a, b, ca, cb float64) float64 { // (second half - first half) / mean: which side is lighter
		if ca < 8 || cb < 8 {
			return 0
		}
		ma, mb := a/ca, b/cb
		return (mb - ma) / (0.5*(ma+mb) + 1e-9)
	}
	// how much of the smallest box around the outline (along the roof's axes) the outline fills: a plain house is near 1
	umin, umax, vmin, vmax := 1e9, -1e9, 1e9, -1e9
	for _, q := range pts {
		dx, dz := q[0]-cx, q[1]-cz
		u, v := dx*c+dz*sn, -dx*sn+dz*c
		umin, umax, vmin, vmax = math.Min(umin, u), math.Max(umax, u), math.Min(vmin, v), math.Max(vmax, v)
	}
	rect := area / math.Max(1, (umax-umin)*(vmax-vmin))
	cMinor, cMajor := contrast(sumV[0], sumV[1], cntV[0], cntV[1]), contrast(sumU[0], sumU[1], cntU[0], cntU[1])
	return []float64{area, n, elong, lm, math.Sqrt(ls/n) / (lm + 1e-9), rm, gm, bm, (mx - mn) / (mx + 1),
		cMinor, cMajor, spread(sumQ[:], cntQ[:]), spread(sumS[:], cntS[:]), grad,
		float64(len(pts)), rect, signed(sumNS[0], sumNS[1], cntNS[0], cntNS[1]), signed(sumEW[0], sumEW[1], cntEW[0], cntEW[1]), math.Cos(2 * th), math.Sin(2 * th), cMinor / (cMajor + 0.02)}, true
}

// the box "w,s,e,n" (degrees): one line for every building of Berlin that has a real roof in the city model
func dumpRoofData(box, out string) error {
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
		return fmt.Errorf("this box has no model of Berlin")
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
	bw := bufio.NewWriter(f)
	defer bw.Flush()
	fmt.Fprintf(bw, "id,lon,lat,%s,msh,shape,h,roofh\n", strings.Join(roofFeatureNames, ","))
	kept := 0
	for i, m := range mb {
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
		if !ok || shape == "" {
			continue
		}
		fv, ok := roofFeatures(sat, m.ring)
		if !ok {
			continue
		}
		fmt.Fprintf(bw, "%d,%.6f,%.6f", i, cx, cy)
		for _, x := range fv {
			fmt.Fprintf(bw, ",%.4f", x)
		}
		fmt.Fprintf(bw, ",%.1f,%s,%.1f,%.1f\n", m.height, shape, h, rh)
		kept++
	}
	fmt.Fprintf(os.Stderr, "%d buildings written to %s\n", kept, out)
	return nil
}
