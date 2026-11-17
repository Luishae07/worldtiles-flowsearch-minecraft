#!/bin/bash
# Downloads the landmark models (about 180 MB for the CC0 library, 100 MB for Open Landmarks) into data/awl and data/ol.
# Files that are there already are not fetched again. Four downloads at a time.
cd "$(dirname "$0")"
mkdir -p data/awl data/ol
fetch_awl() { f="data/awl/$1.glb"; [ -s "$f" ] || curl -fsSL -m 120 -o "$f.part" "https://raw.githubusercontent.com/alexwohlbruck/landmarks/main/models/$1.glb" && mv "$f.part" "$f"; }
fetch_ol() { d="data/ol/$1"; mkdir -p "$d"; [ -s "$d/detail.glb" ] || { curl -fsSL -m 120 -o "$d/detail.glb.part" "https://raw.githubusercontent.com/benjamintd/open-landmarks/main/collection/$1/detail.glb" && mv "$d/detail.glb.part" "$d/detail.glb"; }; [ -s "$d/asset.json" ] || curl -fsSL -m 60 -o "$d/asset.json" "https://raw.githubusercontent.com/benjamintd/open-landmarks/main/collection/$1/asset.json"; }
export -f fetch_awl fetch_ol
xargs -P 4 -I{} bash -c 'fetch_ol {} || echo "failed: ol {}"' < data/ol-ids.txt
xargs -P 4 -I{} bash -c 'fetch_awl {} || echo "failed: awl {}"' < data/awl-ids.txt
echo "ol: $(ls data/ol/*/detail.glb 2>/dev/null | wc -l) models, awl: $(ls data/awl/*.glb 2>/dev/null | wc -l) models, $(du -sh data | cut -f1)"
