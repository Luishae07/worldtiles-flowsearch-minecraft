#!/bin/sh
# Downloads the VBB timetable again and makes trains.bin (the plugin reads it again by itself within the hour).
set -e
cd /media/data/gtfs-berlin
curl -sL -m 580 -o vbb.zip.new https://www.vbb.de/vbbgtfs
mv vbb.zip.new vbb.zip
unzip -o -q vbb.zip
cd /srv/uploads/flowsites/minecraft/tools/transit
go run . -out /media/data/gtfs-berlin/trains.bin.new
mv /media/data/gtfs-berlin/trains.bin.new /media/data/gtfs-berlin/trains.bin
