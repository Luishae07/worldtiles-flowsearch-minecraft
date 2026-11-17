# The World in Blocks

A Minecraft server that is the real Earth: **one block is one metre**. Walk through Berlin, climb the Matterhorn, take a flight, run a shop. Java and Bedrock players share one world.

Not made by or connected to Mojang or Microsoft. "Minecraft" is a trademark of Mojang AB.

**Licence:** [PolyForm Noncommercial 1.0.0](LICENSE). You can read, run, change and share the code for non-commercial use. Selling it or using it for business needs permission. This is source-available, not open source in the strict sense. The data and models the world is built from have their own licences, see below.

## What is in here

| Folder | What it is |
|---|---|
| `tools/worldtiles` | The Paper plugin (Java). Builds the world from tiles, and has the game: jobs, shops, houses, hotels, datacenters, trains, flights, football, weather, `/go`, `/tpll`, `/credits` and more. `./build.sh` builds `WorldTiles.jar` into the server's plugins folder. |
| `tools/mctiles` | The tile service (Go, port 8858). Makes a 500 m square of the world (ground, water, roads, buildings, trees) from open data, on demand. |
| `tools/landmarkvox` | Turns 3D landmark models (GLB) into blocks, for the Eiffel Tower and about 1,300 more. |
| `tools/transit`, `tools/mcgen` | Helpers for train timetables and a few generators. |
| `index.html`, `walk.html` | The website, with a browser version that builds the same blocks (three.js). |

## Building

You need Go, Java 25 and a Paper server (Minecraft Java 26.x). Nothing from Mojang or Paper is in this repository.

```sh
# the tile service
cd tools/mctiles && go build -o mctiles ./main.go
./mctiles -dir /path/to/tiles-data -addr 127.0.0.1:8858 -max-gb 60 -builders 4

# the plugin (needs the Paper jar of your server, see the top of build.sh, and the
# JetBrains annotations jar as tools/worldtiles/extra/annotations.jar, from Maven Central: org.jetbrains:annotations)
cd tools/worldtiles && mkdir -p extra && ./build.sh
```

The Overture building parts use a small Python helper (DuckDB). Install it with `pip install duckdb` and start the tile service with `MCTILES_PYTHON=/path/to/python`. Without it the squares are built without the parts.

The landmarks are not in this repository (their licences differ). To make them:

```sh
cd tools/landmarkvox && ./fetch.sh && go build -o landmarkvox ./main.go
./landmarkvox -mode ol  -src data/ol -out ../../landmarks
./landmarkvox -mode awl -catalog data/awl-catalog.json -src data/awl -out ../../landmarks
```

Several folders for data and caches are set near the top of `tools/mctiles/main.go` (for example `/media/data/...`). Change them to fit your machine. The weather key, if you have one, goes in a file called `tomorrow.key` next to the server (never in the code).

## Where the data comes from

The same list is shown in the game with `/credits`.

- Building outlines: Microsoft Global ML Building Footprints (ODbL)
- Land cover and trees: ESA WorldCover 10 m (CC BY 4.0)
- Roads, railways, shops, stops: OpenStreetMap contributors (ODbL), through Postpass; place search by Nominatim
- Towers, spires and roofs of buildings: Overture Maps Foundation building parts
- Building heights: GHSL (European Commission JRC); BD TOPO, IGN, for France (Licence Ouverte 2.0)
- Real buildings of Berlin: 3D-Stadtmodell LoD2, Land Berlin; ground heights DGM1, Land Berlin
- Real buildings of the Netherlands: 3DBAG, TU Delft 3D geoinformation (CC BY 4.0)
- Roof colours: Esri World Imagery (only the average colour of a roof is read)
- Terrain elsewhere: Mapzen Terrain Tiles on AWS
- Landmark models: Open Landmarks (CC BY 4.0); Landmarks by Alex Wohlbruck (CC0); places from OpenStreetMap data (ODbL)
- Trains: VBB timetable (GTFS); flights: adsb.fi; fixtures: TheSportsDB; weather: Tomorrow.io and Open-Meteo
- The Moon: NASA LOLA and Moon Trek

Please keep the attribution and the licence terms of each source if you reuse data made with these tools. Block pictures of the web version are drawn in code.

## Third-party code

`vendor/three.module.js` is three.js (MIT licence). The plugin is built against the Paper API.
