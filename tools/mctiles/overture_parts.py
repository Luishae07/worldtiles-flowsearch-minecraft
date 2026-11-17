#!/usr/bin/env python3
# Overture Maps building parts (the pieces of a building: a tower on a base, a spire, a roof of its own), worldwide.
# It stays running: a line "west south east north" on stdin is answered with one line of JSON on stdout.
# The parts layer is ONE file (about 600 MB) on Overture's storage; DuckDB reads only the pieces that cover the asked area (about 0.1 MB for one square,
# 3 MB the very first time for the index of the file). Nothing else is downloaded.
import json, sys
import duckdb

RELEASE = "2026-10-21.0"
SRC = "s3://overturemaps-us-west-2/release/%s/theme=buildings/type=building_part/*" % RELEASE

con = duckdb.connect()
con.execute("INSTALL httpfs; LOAD httpfs; INSTALL spatial; LOAD spatial; SET s3_region='us-west-2';")
cols = {r[0] for r in con.execute("DESCRIBE SELECT * FROM read_parquet('%s')" % SRC).fetchall()}
pick = lambda c: c if c in cols else "NULL"
SQL = ("SELECT %s AS h, %s AS mh, %s AS rs, %s AS rh, %s AS fl, ST_AsGeoJSON(geometry) AS g FROM read_parquet('%s') "
       "WHERE bbox.xmin <= ? AND bbox.xmax >= ? AND bbox.ymin <= ? AND bbox.ymax >= ?") % (
    pick("height"), pick("min_height"), pick("roof_shape"), pick("roof_height"), pick("num_floors"), SRC)


def parts(w, s, e, n):
    out = []
    for h, mh, rs, rh, fl, g in con.execute(SQL, [e, w, n, s]).fetchall():
        if h is None and fl:
            h = fl * 3.2 + 1
        if not h or h <= 0:
            continue
        if mh and mh > 3:  # a part that hangs in the air: would be drawn as a column from the ground, so it is left out
            continue
        geo = json.loads(g)
        polys = geo["coordinates"] if geo["type"] == "MultiPolygon" else [geo["coordinates"]] if geo["type"] == "Polygon" else []
        for p in polys:
            out.append({"h": round(float(h), 1), "s": rs or "", "rh": round(float(rh), 1) if rh else 0, "r": [[round(x, 6), round(y, 6)] for x, y in p[0]]})
    return out


for line in sys.stdin:
    try:
        w, s, e, n = map(float, line.split())
        sys.stdout.write(json.dumps(parts(w, s, e, n), separators=(",", ":")) + "\n")
    except Exception as ex:  # one bad square must not stop the helper
        sys.stdout.write(json.dumps({"error": str(ex)[:200]}) + "\n")
    sys.stdout.flush()
