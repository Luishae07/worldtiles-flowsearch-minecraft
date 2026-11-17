package main

import (
	"encoding/json"
	"log"
	"os"
	"path/filepath"
	"sync"
)

// ---- the forest of roofml: flat or sloped, guessed from the satellite picture of a building ----
//
// Used for the buildings that have no real roof from a city model (so, outside Berlin, Brandenburg and the Netherlands). It was learned from about 2,700
// buildings of Brandenburg, where the real roof is known, and it is right for about 80% of buildings that it has not seen (guessing "sloped" every time: 72%).
// The file is made by tools/roofml (-export). MCTILES_ROOFMODEL=/path/roofmodel.json, or roofmodel.json next to this program.

type rmNode struct {
	F int     `json:"f"`
	T float64 `json:"t"`
	L int     `json:"l"`
	R int     `json:"r"`
	P float64 `json:"p"` // in a leaf (f = -1): the share of flat roofs
}

type roofModel struct {
	Names []string   `json:"names"`
	Trees [][]rmNode `json:"trees"`
}

var (
	roofMod     *roofModel
	roofModOnce sync.Once
)

func getRoofModel() *roofModel {
	roofModOnce.Do(func() {
		path := os.Getenv("MCTILES_ROOFMODEL")
		if path == "" {
			exe, _ := os.Executable()
			path = filepath.Join(filepath.Dir(exe), "roofmodel.json")
		}
		b, err := os.ReadFile(path)
		if err != nil {
			log.Printf("roofml: no model (%v): roofs without a city model stay as they were", err)
			return
		}
		m := &roofModel{}
		if err := json.Unmarshal(b, m); err != nil || len(m.Trees) == 0 || len(m.Names) != len(roofFeatureNames) {
			log.Printf("roofml: the model %s does not fit this program (%v)", path, err)
			return
		}
		for i, n := range m.Names {
			if n != roofFeatureNames[i] {
				log.Printf("roofml: the measurements of the model differ from those of the program (%s is not %s)", n, roofFeatureNames[i])
				return
			}
		}
		roofMod = m
		log.Printf("roofml: model of %d trees read from %s", len(m.Trees), path)
	})
	return roofMod
}

// the share of the trees that say "flat" for these measurements (the measurements of roofFeatures)
func (m *roofModel) flat(x []float64) float64 {
	sum := 0.0
	for _, t := range m.Trees {
		i := 0
		for t[i].F >= 0 {
			if x[t[i].F] <= t[i].T {
				i = t[i].L
			} else {
				i = t[i].R
			}
		}
		sum += t[i].P
	}
	return sum / float64(len(m.Trees))
}
