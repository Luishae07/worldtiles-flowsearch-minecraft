#!/bin/bash
# Downloads the squares that the Backfill workflow made (the release of this repository) and puts them into the folder of the tile service.
# Squares that are there already are not replaced. Needs the gh program, signed in.
#   ./fetch.sh [tag] [folder]        defaults: squares-landmarks  /media/data/tiles-data
set -e
tag="${1:-squares-landmarks}"
dir="${2:-/media/data/tiles-data}"
repo="${REPO:-Luishae07/worldtiles-flowsearch-minecraft}"
tmp="$(mktemp -d "${dir%/*}/backfill-download.XXXXXX")"
trap 'rm -rf "$tmp"' EXIT
gh release download "$tag" --repo "$repo" --pattern squares.tar.gz --dir "$tmp"
mkdir -p "$dir"
tar -xzf "$tmp/squares.tar.gz" -C "$dir" --skip-old-files
echo "$(find "$dir" -name '*.mct' | wc -l) squares in $dir"
