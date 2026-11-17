package main

import (
	"encoding/csv"
	"fmt"
	"math"
	"math/rand"
	"os"
	"strconv"
	"strings"
)

func loadH(files string) (x [][]float64, y, msh []float64) {
	for _, fn := range strings.Split(files, ",") {
		f, err := os.Open(fn)
		if err != nil {
			fmt.Fprintln(os.Stderr, err)
			os.Exit(1)
		}
		rows, err := csv.NewReader(f).ReadAll()
		f.Close()
		if err != nil || len(rows) < 2 {
			fmt.Fprintln(os.Stderr, fn, "could not be read")
			os.Exit(1)
		}
		col := map[string]int{}
		for i, h := range rows[0] {
			col[h] = i
		}
		last := col["msh"] + 1
		for _, r := range rows[1:] {
			h, err := strconv.ParseFloat(r[col["h"]], 64)
			if err != nil || h <= 0 || h > 60 {
				continue
			}
			v := make([]float64, last-3)
			for i := range v {
				v[i], _ = strconv.ParseFloat(r[3+i], 64)
			}
			x, y, msh = append(x, v), append(y, h), append(msh, v[len(v)-1])
		}
	}
	return
}

// the forest learns from one part of town and is tested on another (the buildings next to each other are alike: a random test is too easy)
func runHeightHoldout(train, test string, trees, depth, minLeaf int, seed int64) {
	tx, ty, _ := loadH(train)
	ex, ey, em := loadH(test)
	rng := rand.New(rand.NewSource(seed))
	p := params{maxDepth: depth, minLeaf: minLeaf, mtry: int(math.Max(1, math.Round(math.Sqrt(float64(len(tx[0]))))))}
	idx := make([]int, len(tx))
	for i := range idx {
		idx[i] = i
	}
	imp := make([]float64, len(tx[0]))
	fs := make([]*rnode, trees)
	for t := range fs {
		boot := make([]int, len(idx))
		for i := range boot {
			boot[i] = idx[rng.Intn(len(idx))]
		}
		fs[t] = buildReg(tx, ty, boot, 0, p, rng, imp)
	}
	var mean float64
	for _, v := range ty {
		mean += v
	}
	mean /= float64(len(ty))
	var aM, aS, aF, wM, wS, wF float64
	for i := range ey {
		s := 0.0
		for _, t := range fs {
			s += t.predict(ex[i])
		}
		s /= float64(trees)
		dm, ds, df := math.Abs(mean-ey[i]), math.Abs(em[i]-ey[i]), math.Abs(s-ey[i])
		aM, aS, aF = aM+dm, aS+ds, aF+df
		if dm <= 2 {
			wM++
		}
		if ds <= 2 {
			wS++
		}
		if df <= 2 {
			wF++
		}
	}
	n := float64(len(ey))
	fmt.Printf("learned from %d buildings (%s), tested on %d buildings of another part of town (%s)\n\n", len(ty), train, len(ey), test)
	fmt.Printf("%-44s %8s %12s\n", "guess", "error", "within 2 m")
	fmt.Printf("%-44s %6.2f m %11.0f%%\n", "the same height for every building", aM/n, 100*wM/n)
	fmt.Printf("%-44s %6.2f m %11.0f%%\n", "Microsoft's guess alone", aS/n, 100*wS/n)
	fmt.Printf("%-44s %6.2f m %11.0f%%\n", "forest: measurements + Microsoft's guess", aF/n, 100*wF/n)
}
