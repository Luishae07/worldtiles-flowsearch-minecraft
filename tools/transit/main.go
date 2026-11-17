// transit-prep turns a GTFS timetable (VBB Berlin) into one compact file for the WorldTiles plugin:
// only the rail lines (S-Bahn, U-Bahn, tram, regional trains), with the days they run on in a bitmask.
//
//	go run . -in /media/data/gtfs-berlin -out /media/data/gtfs-berlin/trains.bin
package main

import (
	"bufio"
	"encoding/binary"
	"encoding/csv"
	"flag"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"time"
)

type row map[string]string

func readCSV(path string, fn func(get func(string) string)) error {
	f, err := os.Open(path)
	if err != nil {
		return err
	}
	defer f.Close()
	r := csv.NewReader(bufio.NewReaderSize(f, 1<<20))
	r.LazyQuotes = true
	r.FieldsPerRecord = -1
	head, err := r.Read()
	if err != nil {
		return err
	}
	idx := map[string]int{}
	for i, h := range head {
		idx[strings.TrimPrefix(h, "\xef\xbb\xbf")] = i
	}
	var rec []string
	get := func(k string) string {
		i, ok := idx[k]
		if !ok || i >= len(rec) {
			return ""
		}
		return rec[i]
	}
	for {
		rec, err = r.Read()
		if err == io.EOF {
			return nil
		}
		if err != nil {
			continue
		}
		fn(get)
	}
}

func secs(s string) int {
	p := strings.Split(strings.TrimSpace(s), ":")
	if len(p) < 2 {
		return -1
	}
	h, _ := strconv.Atoi(p[0])
	m, _ := strconv.Atoi(p[1])
	sec := 0
	if len(p) > 2 {
		sec, _ = strconv.Atoi(p[2])
	}
	return h*3600 + m*60 + sec
}

func wstr(w *bufio.Writer, s string) {
	if len(s) > 65000 {
		s = s[:65000]
	}
	binary.Write(w, binary.BigEndian, uint16(len(s)))
	w.WriteString(s)
}

type stopRec struct {
	lat, lon float64
	name     string
}

type stopTime struct {
	stop     int32
	seq      int
	arr, dep int32
}

type trip struct {
	route    int
	service  string
	headsign string
	times    []stopTime
}

func main() {
	in := flag.String("in", "/media/data/gtfs-berlin", "folder with the GTFS files")
	out := flag.String("out", "/media/data/gtfs-berlin/trains.bin", "output file")
	flag.Parse()
	loc, _ := time.LoadLocation("Europe/Berlin")
	today := time.Now().In(loc)
	base := time.Date(today.Year(), today.Month(), today.Day(), 0, 0, 0, 0, loc).AddDate(0, 0, -1) // bit 0 = yesterday

	// the lines: 100 trains, 109 S-Bahn, 400 U-Bahn, 900 tram
	cat := func(t int) int {
		switch {
		case t == 109:
			return 0
		case t >= 400 && t < 500:
			return 1
		case t >= 900 && t < 1000:
			return 2
		case t >= 100 && t < 200, t == 2:
			return 3
		}
		return -1
	}
	routeIdx := map[string]int{}
	var routeNames []string
	var routeCat []byte
	var routeColor []int32
	must(readCSV(filepath.Join(*in, "routes.txt"), func(g func(string) string) {
		t, _ := strconv.Atoi(g("route_type"))
		c := cat(t)
		if c < 0 {
			return
		}
		routeIdx[g("route_id")] = len(routeNames)
		routeNames = append(routeNames, g("route_short_name"))
		routeCat = append(routeCat, byte(c))
		col := int32(-1)
		if v, err := strconv.ParseInt(g("route_color"), 16, 32); err == nil && g("route_color") != "" {
			col = int32(v)
		}
		routeColor = append(routeColor, col)
	}))
	fmt.Println("lines:", len(routeNames))

	// the days
	svcMask := map[string]uint32{}
	wd := []string{"sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday"}
	must(readCSV(filepath.Join(*in, "calendar.txt"), func(g func(string) string) {
		var m uint32
		for i := 0; i < 32; i++ {
			d := base.AddDate(0, 0, i)
			ds := d.Format("20060102")
			if ds < g("start_date") || ds > g("end_date") {
				continue
			}
			if g(wd[int(d.Weekday())]) == "1" {
				m |= 1 << uint(i)
			}
		}
		svcMask[g("service_id")] = m
	}))
	must(readCSV(filepath.Join(*in, "calendar_dates.txt"), func(g func(string) string) {
		d, err := time.ParseInLocation("20060102", g("date"), loc)
		if err != nil {
			return
		}
		i := int(d.Sub(base).Hours()/24 + 0.5)
		if i < 0 || i >= 32 {
			return
		}
		if g("exception_type") == "1" {
			svcMask[g("service_id")] |= 1 << uint(i)
		} else if g("exception_type") == "2" {
			svcMask[g("service_id")] &^= 1 << uint(i)
		}
	}))

	trips := map[string]*trip{}
	must(readCSV(filepath.Join(*in, "trips.txt"), func(g func(string) string) {
		ri, ok := routeIdx[g("route_id")]
		if !ok || svcMask[g("service_id")] == 0 {
			return
		}
		trips[g("trip_id")] = &trip{route: ri, service: g("service_id"), headsign: g("trip_headsign")}
	}))
	fmt.Println("trips:", len(trips))

	stopIdx := map[string]int32{}
	var stops []stopRec
	stopInfo := map[string]stopRec{}
	must(readCSV(filepath.Join(*in, "stops.txt"), func(g func(string) string) {
		la, _ := strconv.ParseFloat(g("stop_lat"), 64)
		lo, _ := strconv.ParseFloat(g("stop_lon"), 64)
		stopInfo[g("stop_id")] = stopRec{la, lo, g("stop_name")}
	}))
	must(readCSV(filepath.Join(*in, "stop_times.txt"), func(g func(string) string) {
		t := trips[g("trip_id")]
		if t == nil {
			return
		}
		si, ok := stopInfo[g("stop_id")]
		if !ok {
			return
		}
		id, ok := stopIdx[g("stop_id")]
		if !ok {
			id = int32(len(stops))
			stopIdx[g("stop_id")] = id
			stops = append(stops, si)
		}
		seq, _ := strconv.Atoi(g("stop_sequence"))
		a, d := secs(g("arrival_time")), secs(g("departure_time"))
		if a < 0 {
			a = d
		}
		if d < 0 {
			d = a
		}
		t.times = append(t.times, stopTime{id, seq, int32(a), int32(d)})
	}))

	var keep []*trip
	for _, t := range trips {
		if len(t.times) < 2 {
			continue
		}
		sort.Slice(t.times, func(i, j int) bool { return t.times[i].seq < t.times[j].seq })
		keep = append(keep, t)
	}
	sort.Slice(keep, func(i, j int) bool { return keep[i].times[0].dep < keep[j].times[0].dep })
	fmt.Println("kept:", len(keep), "stops:", len(stops))

	f, err := os.Create(*out)
	must(err)
	w := bufio.NewWriterSize(f, 1<<20)
	w.WriteString("TRN1")
	binary.Write(w, binary.BigEndian, int32(base.Year()*10000+int(base.Month())*100+base.Day()))
	binary.Write(w, binary.BigEndian, int32(len(stops)))
	for _, s := range stops {
		binary.Write(w, binary.BigEndian, s.lat)
		binary.Write(w, binary.BigEndian, s.lon)
		wstr(w, s.name)
	}
	binary.Write(w, binary.BigEndian, int32(len(routeNames)))
	for i, n := range routeNames {
		wstr(w, n)
		w.WriteByte(routeCat[i])
		binary.Write(w, binary.BigEndian, routeColor[i])
	}
	binary.Write(w, binary.BigEndian, int32(len(keep)))
	for _, t := range keep {
		binary.Write(w, binary.BigEndian, uint16(t.route))
		binary.Write(w, binary.BigEndian, svcMask[t.service])
		wstr(w, t.headsign)
		binary.Write(w, binary.BigEndian, uint16(len(t.times)))
		for _, st := range t.times {
			binary.Write(w, binary.BigEndian, st.stop)
			binary.Write(w, binary.BigEndian, st.arr)
			binary.Write(w, binary.BigEndian, st.dep)
		}
	}
	must(w.Flush())
	must(f.Close())
	fmt.Println("written", *out)
}

func must(err error) {
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}
