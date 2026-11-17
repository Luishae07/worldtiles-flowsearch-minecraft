package main

import (
	"encoding/csv"
	"fmt"
	"math"
	"math/rand"
	"os"
	"sort"
	"strconv"
	"strings"
)

// -height: how well can the height of a building be guessed from the satellite measurements (and Microsoft's own guess)? A forest of regression trees,
// tested like the roofs: five times it learns from four fifths of the buildings and is asked about the fifth that it has not seen.
// The truth is the height in the city model (column h of the file).

type rnode struct {
	f    int
	t    float64
	l, r *rnode
	mean float64
}

func buildReg(x [][]float64, y []float64, idx []int, depth int, p params, rng *rand.Rand, imp []float64) *rnode {
	n := float64(len(idx))
	var sum, sq float64
	for _, i := range idx {
		sum += y[i]
		sq += y[i] * y[i]
	}
	nd := &rnode{f: -1, mean: sum / n}
	parentVar := sq/n - (sum/n)*(sum/n)
	if depth >= p.maxDepth || len(idx) < 2*p.minLeaf || parentVar < 1e-9 {
		return nd
	}
	feats := rng.Perm(len(x[0]))[:p.mtry]
	bestGain, bf, bt := 0.0, -1, 0.0
	ord := make([]int, len(idx))
	for _, f := range feats {
		copy(ord, idx)
		sort.Slice(ord, func(a, b int) bool { return x[ord[a]][f] < x[ord[b]][f] })
		var ls, lq float64
		for k := 0; k < len(ord)-1; k++ {
			v := y[ord[k]]
			ls, lq = ls+v, lq+v*v
			if x[ord[k]][f] == x[ord[k+1]][f] {
				continue
			}
			nl := float64(k + 1)
			nr := n - nl
			if int(nl) < p.minLeaf || int(nr) < p.minLeaf {
				continue
			}
			rs, rq := sum-ls, sq-lq
			// the sum of squares left over after the split (smaller is better)
			after := (lq - ls*ls/nl) + (rq - rs*rs/nr)
			gain := (sq - sum*sum/n) - after
			if gain > bestGain {
				bestGain, bf, bt = gain, f, (x[ord[k]][f]+x[ord[k+1]][f])/2
			}
		}
	}
	if bf < 0 {
		return nd
	}
	imp[bf] += bestGain
	var li, ri []int
	for _, i := range idx {
		if x[i][bf] <= bt {
			li = append(li, i)
		} else {
			ri = append(ri, i)
		}
	}
	nd.f, nd.t = bf, bt
	nd.l = buildReg(x, y, li, depth+1, p, rng, imp)
	nd.r = buildReg(x, y, ri, depth+1, p, rng, imp)
	return nd
}

func (nd *rnode) predict(x []float64) float64 {
	for nd.f >= 0 {
		if x[nd.f] <= nd.t {
			nd = nd.l
		} else {
			nd = nd.r
		}
	}
	return nd.mean
}

func runHeight(files string, trees, depth, minLeaf, folds int, seed int64) {
	var x [][]float64
	var y, msh []float64
	var names []string
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
		last := col["msh"] + 1 // the measurements and Microsoft's own guess of the height
		names = rows[0][3:last]
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
	n := len(y)
	var mean float64
	for _, v := range y {
		mean += v
	}
	mean /= float64(n)
	rng := rand.New(rand.NewSource(seed))
	perm := rng.Perm(n)
	fold := make([]int, n)
	for k, i := range perm {
		fold[i] = k % folds
	}
	p := params{maxDepth: depth, minLeaf: minLeaf, mtry: int(math.Max(1, math.Round(math.Sqrt(float64(len(x[0]))))))}
	imp := make([]float64, len(x[0]))
	pred := make([]float64, n)
	for k := 0; k < folds; k++ {
		var train, test []int
		for i := 0; i < n; i++ {
			if fold[i] == k {
				test = append(test, i)
			} else {
				train = append(train, i)
			}
		}
		fs := make([]*rnode, trees)
		for t := range fs {
			boot := make([]int, len(train))
			for i := range boot {
				boot[i] = train[rng.Intn(len(train))]
			}
			fs[t] = buildReg(x, y, boot, 0, p, rng, imp)
		}
		for _, i := range test {
			s := 0.0
			for _, t := range fs {
				s += t.predict(x[i])
			}
			pred[i] = s / float64(trees)
		}
	}
	mae := func(f func(i int) float64) (float64, float64) {
		var a, b float64
		for i := 0; i < n; i++ {
			d := f(i) - y[i]
			a, b = a+math.Abs(d), b+d*d
		}
		return a / float64(n), math.Sqrt(b / float64(n))
	}
	m1, r1 := mae(func(i int) float64 { return mean })
	m2, r2 := mae(func(i int) float64 { return msh[i] })
	m3, r3 := mae(func(i int) float64 { return pred[i] })
	within := func(f func(i int) float64, tol float64) float64 {
		c := 0
		for i := 0; i < n; i++ {
			if math.Abs(f(i)-y[i]) <= tol {
				c++
			}
		}
		return 100 * float64(c) / float64(n)
	}
	fmt.Printf("%d buildings, real height from the city model: mean %.1f m\n\n", n, mean)
	fmt.Printf("%-44s %8s %8s %12s\n", "guess (test on buildings not used to learn)", "error", "rms", "within 2 m")
	fmt.Printf("%-44s %6.2f m %6.2f m %11.0f%%\n", "the same height for every building (mean)", m1, r1, within(func(i int) float64 { return mean }, 2))
	fmt.Printf("%-44s %6.2f m %6.2f m %11.0f%%\n", "Microsoft's guess alone", m2, r2, within(func(i int) float64 { return msh[i] }, 2))
	fmt.Printf("%-44s %6.2f m %6.2f m %11.0f%%\n", "forest: measurements + Microsoft's guess", m3, r3, within(func(i int) float64 { return pred[i] }, 2))
	type fi struct {
		n string
		v float64
	}
	var list []fi
	var sum float64
	for i, v := range imp {
		list = append(list, fi{names[i], v})
		sum += v
	}
	sort.Slice(list, func(a, b int) bool { return list[a].v > list[b].v })
	fmt.Print("\nwhat the forest looked at most: ")
	for i, f := range list {
		if i >= 6 {
			break
		}
		fmt.Printf("%s %.0f%%  ", f.n, 100*f.v/sum)
	}
	fmt.Println()
}
