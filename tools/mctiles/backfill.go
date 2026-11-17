package main

import (
	"bufio"
	"fmt"
	"log"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

// ---- backfill: making a list of squares ahead of time (on GitHub Actions, see .github/workflows/backfill.yml) ----
//
//	mctiles -backfill backfill/landmarks.squares -backfill-out out -backfill-workers 3 [-backfill-limit 20]
//
// The list has one square per line, "x z" (as in the requests of the game). Every square is made like the tile service makes it, and written to
// out/<x>/<z>.mct, the same layout as the folder of the service, so that the files can be copied there. A square that is already in the folder is left alone.
// A square that could not get its roads (Postpass and Overpass did not answer) is NOT written (it would be kept without roads); it is counted as failed, and
// the program says so and exits with an error when there are any, so that the list can be run again.

func runBackfill(listFile, outDir string, limit, workers int) error {
	f, err := os.Open(listFile)
	if err != nil {
		return err
	}
	var squares [][2]int
	sc := bufio.NewScanner(f)
	for sc.Scan() {
		var x, z int
		if _, err := fmt.Sscanf(strings.TrimSpace(sc.Text()), "%d %d", &x, &z); err == nil {
			squares = append(squares, [2]int{x, z})
		}
	}
	f.Close()
	if limit > 0 && limit < len(squares) {
		squares = squares[:limit]
	}
	if workers < 1 {
		workers = 1
	}
	s := &server{dir: outDir}
	var wg sync.WaitGroup
	sem := make(chan struct{}, workers)
	var made, had, failed int32
	var failMu sync.Mutex
	var failList []string
	t0 := time.Now()
	for i, sq := range squares {
		wg.Add(1)
		sem <- struct{}{}
		go func(i int, x, z int) {
			defer wg.Done()
			defer func() { <-sem }()
			p := s.path(x, z)
			if fi, err := os.Stat(p); err == nil && fi.Size() > 0 {
				atomic.AddInt32(&had, 1)
				return
			}
			data, err := buildTile(x, z)
			_, noRoads := roadsMissing.LoadAndDelete([2]int{x, z})
			if err != nil || noRoads {
				atomic.AddInt32(&failed, 1)
				failMu.Lock()
				failList = append(failList, fmt.Sprintf("%d %d", x, z))
				failMu.Unlock()
				log.Printf("square %d,%d: not made (%v, roads missing: %v)", x, z, err, noRoads)
				return
			}
			if err := os.MkdirAll(filepath.Dir(p), 0o755); err == nil {
				tmp := p + ".tmp"
				if os.WriteFile(tmp, data, 0o644) == nil {
					os.Rename(tmp, p)
					n := atomic.AddInt32(&made, 1)
					if n%25 == 0 {
						log.Printf("%d made (%d of %d done, %.0f s)", n, i+1, len(squares), time.Since(t0).Seconds())
					}
				}
			}
		}(i, sq[0], sq[1])
	}
	wg.Wait()
	log.Printf("backfill: %d squares in the list, %d made, %d were there already, %d failed, %.0f s", len(squares), made, had, failed, time.Since(t0).Seconds())
	if failed > 0 {
		os.WriteFile(filepath.Join(outDir, "failed.squares"), []byte(strings.Join(failList, "\n")+"\n"), 0o644)
		return fmt.Errorf("%d squares failed (listed in %s/failed.squares)", failed, outDir)
	}
	return nil
}
