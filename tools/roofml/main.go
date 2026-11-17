// roofml learns from the measurements of roofs (roofs-*.csv, made by "mctiles -roofdata") which roof a building has: flat, gabled or hipped.
// It is a forest of decision trees (no libraries). It is tested the honest way: five times it learns from four fifths of the buildings and is asked about
// the fifth that it has not seen.
//
//	go run main.go -csv data/roofs-kw.csv,data/roofs-test.csv
package main

import (
	"encoding/csv"
	"encoding/json"
	"flag"
	"fmt"
	"math"
	"math/rand"
	"os"
	"sort"
	"strconv"
	"strings"
)

var classNames = []string{"flat", "gabled", "hipped"}

type sample struct {
	x   []float64
	y   int
	src int // the number of the file (the place) that it comes from
}

func classOf(shape string) int {
	switch shape {
	case "flat":
		return 0
	case "gabled":
		return 1
	case "hipped", "pyramidal":
		if binary {
			return 1 // the game builds gabled and hipped roofs the same way: sloped
		}
		return 2
	}
	return -1
}

var binary bool

type node struct {
	f    int
	t    float64
	l, r *node
	dist [3]float64
}

type params struct{ maxDepth, minLeaf, mtry int }

func gini(c [3]float64, n float64) float64 {
	if n == 0 {
		return 0
	}
	s := 1.0
	for _, v := range c {
		p := v / n
		s -= p * p
	}
	return s
}

func build(data []sample, idx []int, depth int, p params, rng *rand.Rand, imp []float64) *node {
	var c [3]float64
	for _, i := range idx {
		c[data[i].y]++
	}
	n := float64(len(idx))
	nd := &node{f: -1, dist: c}
	parent := gini(c, n)
	if depth >= p.maxDepth || len(idx) < 2*p.minLeaf || parent == 0 {
		return nd
	}
	feats := rng.Perm(len(data[0].x))[:p.mtry]
	bestGain, bf, bt := 0.0, -1, 0.0
	ord := make([]int, len(idx))
	for _, f := range feats {
		copy(ord, idx)
		sort.Slice(ord, func(a, b int) bool { return data[ord[a]].x[f] < data[ord[b]].x[f] })
		var left [3]float64
		for k := 0; k < len(ord)-1; k++ {
			left[data[ord[k]].y]++
			if data[ord[k]].x[f] == data[ord[k+1]].x[f] {
				continue
			}
			nl := float64(k + 1)
			nr := n - nl
			if int(nl) < p.minLeaf || int(nr) < p.minLeaf {
				continue
			}
			var right [3]float64
			for j := range right {
				right[j] = c[j] - left[j]
			}
			g := parent - (nl*gini(left, nl)+nr*gini(right, nr))/n
			if g > bestGain {
				bestGain, bf, bt = g, f, (data[ord[k]].x[f]+data[ord[k+1]].x[f])/2
			}
		}
	}
	if bf < 0 {
		return nd
	}
	imp[bf] += bestGain * n
	var li, ri []int
	for _, i := range idx {
		if data[i].x[bf] <= bt {
			li = append(li, i)
		} else {
			ri = append(ri, i)
		}
	}
	nd.f, nd.t = bf, bt
	nd.l = build(data, li, depth+1, p, rng, imp)
	nd.r = build(data, ri, depth+1, p, rng, imp)
	return nd
}

func (nd *node) predict(x []float64) [3]float64 {
	for nd.f >= 0 {
		if x[nd.f] <= nd.t {
			nd = nd.l
		} else {
			nd = nd.r
		}
	}
	var out [3]float64
	s := nd.dist[0] + nd.dist[1] + nd.dist[2]
	for i := range out {
		out[i] = nd.dist[i] / s
	}
	return out
}

func forest(data []sample, idx []int, trees int, p params, rng *rand.Rand, imp []float64) []*node {
	out := make([]*node, trees)
	for t := range out {
		boot := make([]int, len(idx))
		for i := range boot {
			boot[i] = idx[rng.Intn(len(idx))]
		}
		out[t] = build(data, boot, 0, p, rng, imp)
	}
	return out
}

func vote(fs []*node, x []float64) int {
	var s [3]float64
	for _, t := range fs {
		d := t.predict(x)
		for i := range s {
			s[i] += d[i]
		}
	}
	best := 0
	for i := range s {
		if s[i] > s[best] {
			best = i
		}
	}
	return best
}

func main() {
	files := flag.String("csv", "", "the files of measurements, separated by commas")
	trees := flag.Int("trees", 300, "trees in the forest")
	depth := flag.Int("depth", 8, "the deepest a tree goes")
	minLeaf := flag.Int("minleaf", 2, "the fewest buildings in a leaf")
	seed := flag.Int64("seed", 1, "random seed")
	useMsh := flag.Bool("msh", false, "also use the height that Microsoft guessed")
	folds := flag.Int("k", 5, "folds of the test")
	flag.BoolVar(&binary, "binary", false, "only flat or sloped (gabled and hipped together)")
	export := flag.String("export", "", "after the test: learn from all the buildings and write the forest to this file (with -binary; the tile service reads it)")
	exportTrees := flag.Int("exporttrees", 100, "trees in the exported forest")
	heightMode := flag.Bool("height", false, "test how well the height can be guessed instead of the roof (height.go)")
	testCSV := flag.String("testcsv", "", "with -height: learn from -csv and test on these files (another part of town)")
	flag.Parse()
	if binary {
		classNames = []string{"flat", "sloped", "-"}
	}
	if *heightMode {
		if *testCSV != "" {
			runHeightHoldout(*files, *testCSV, *trees, *depth, *minLeaf, *seed)
		} else {
			runHeight(*files, *trees, *depth, *minLeaf, *folds, *seed)
		}
		return
	}
	var data []sample
	var names []string
	fileList := strings.Split(*files, ",")
	for fi, fn := range fileList {
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
		hdr := rows[0]
		col := map[string]int{}
		for i, h := range hdr {
			col[h] = i
		}
		last := col["msh"]
		if *useMsh {
			last++
		}
		names = hdr[3:last]
		for _, r := range rows[1:] {
			y := classOf(r[col["shape"]])
			if y < 0 {
				continue
			}
			x := make([]float64, last-3)
			for i := range x {
				x[i], _ = strconv.ParseFloat(r[3+i], 64)
			}
			data = append(data, sample{x, y, fi})
		}
	}
	var counts [3]int
	for _, s := range data {
		counts[s.y]++
	}
	fmt.Printf("%d buildings: flat %d, gabled %d, hipped %d. Features: %s\n", len(data), counts[0], counts[1], counts[2], strings.Join(names, " "))
	major := 0
	for i := range counts {
		if counts[i] > counts[major] {
			major = i
		}
	}
	fmt.Printf("guessing '%s' for every building would be right %.1f%% of the time\n", classNames[major], 100*float64(counts[major])/float64(len(data)))
	rng := rand.New(rand.NewSource(*seed))
	fold := make([]int, len(data)) // every class is spread over the folds
	for c := 0; c < 3; c++ {
		var ids []int
		for i, s := range data {
			if s.y == c {
				ids = append(ids, i)
			}
		}
		rng.Shuffle(len(ids), func(a, b int) { ids[a], ids[b] = ids[b], ids[a] })
		for k, i := range ids {
			fold[i] = k % *folds
		}
	}
	p := params{maxDepth: *depth, minLeaf: *minLeaf, mtry: int(math.Max(1, math.Round(math.Sqrt(float64(len(data[0].x))))))}
	var conf [3][3]int
	imp := make([]float64, len(data[0].x))
	for k := 0; k < *folds; k++ {
		var train, test []int
		for i := range data {
			if fold[i] == k {
				test = append(test, i)
			} else {
				train = append(train, i)
			}
		}
		fs := forest(data, train, *trees, p, rng, imp)
		for _, i := range test {
			conf[data[i].y][vote(fs, data[i].x)]++
		}
	}
	ok, total := 0, 0
	fmt.Printf("\nthe test (buildings that were not used for learning): rows = the truth, columns = the guess\n%-8s", "")
	for _, n := range classNames {
		fmt.Printf("%8s", n)
	}
	fmt.Println("   right")
	for t := 0; t < 3; t++ {
		fmt.Printf("%-8s", classNames[t])
		rs := 0
		for g := 0; g < 3; g++ {
			fmt.Printf("%8d", conf[t][g])
			rs += conf[t][g]
		}
		ok += conf[t][t]
		total += rs
		if rs > 0 {
			fmt.Printf("   %.0f%%", 100*float64(conf[t][t])/float64(rs))
		}
		fmt.Println()
	}
	fmt.Printf("\nright in %.1f%% of the buildings (guessing the most common roof: %.1f%%)\n", 100*float64(ok)/float64(total), 100*float64(counts[major])/float64(len(data)))
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
	fmt.Print("what the forest looked at most: ")
	for i, f := range list {
		if i >= 6 {
			break
		}
		fmt.Printf("%s %.0f%%  ", f.n, 100*f.v/sum)
	}
	fmt.Println()
	if *export != "" {
		if !binary {
			fmt.Fprintln(os.Stderr, "-export needs -binary")
			os.Exit(1)
		}
		all := make([]int, len(data))
		for i := range all {
			all[i] = i
		}
		fs := forest(data, all, *exportTrees, p, rng, make([]float64, len(data[0].x)))
		type jn struct {
			F int     `json:"f"`
			T float64 `json:"t"`
			L int     `json:"l"`
			R int     `json:"r"`
			P float64 `json:"p"` // in a leaf: the share of flat roofs
		}
		var trees [][]jn
		for _, t := range fs {
			var nodes []jn
			var walk func(nd *node) int
			walk = func(nd *node) int {
				at := len(nodes)
				nodes = append(nodes, jn{})
				if nd.f < 0 {
					s := nd.dist[0] + nd.dist[1]
					nodes[at] = jn{F: -1, P: math.Round(nd.dist[0]/s*1000) / 1000}
					return at
				}
				l := walk(nd.l)
				r := walk(nd.r)
				nodes[at] = jn{F: nd.f, T: math.Round(nd.t*1e5) / 1e5, L: l, R: r}
				return at
			}
			walk(t)
			trees = append(trees, nodes)
		}
		b, _ := json.Marshal(map[string]any{"names": names, "trees": trees, "buildings": len(data), "accuracy": float64(ok) / float64(total)})
		if err := os.WriteFile(*export, b, 0o644); err != nil {
			fmt.Fprintln(os.Stderr, err)
			os.Exit(1)
		}
		fmt.Printf("forest of %d trees written to %s (%d KB)\n", len(trees), *export, len(b)>>10)
	}
}
