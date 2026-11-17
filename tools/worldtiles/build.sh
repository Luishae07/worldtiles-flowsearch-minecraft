#!/bin/bash
# Builds WorldTiles.jar against the libraries inside the Paper jar of the server, and puts it in the server's plugins folder.
#   ./build.sh            (the Paper jar is /media/data/minecraft-server/paper.jar)
set -e
cd "$(dirname "$0")"
SRV=/media/data/minecraft-server
rm -rf libs && mkdir -p libs
(cd libs && unzip -oq $SRV/paper.jar 'META-INF/libraries/*' 'META-INF/versions/*')
CP=$(find libs/META-INF/libraries -name '*.jar' | tr '\n' ':')$(find libs/META-INF/versions -name 'paper-*.jar' | head -1):$(find $SRV/libraries -name '*.jar' | tr '\n' ':')extra/annotations.jar
rm -rf out && mkdir -p out
if ! javac --release 25 -Xlint:-options -proc:none -cp "$CP" -d out $(find src -name '*.java') > javac.log 2>&1; then
  grep -v 'warning: \[classfile\]' javac.log
  echo "BUILD FAILED: no jar was made or installed" >&2
  exit 1
fi
rm -f javac.log
cp plugin.yml out/
jar cf WorldTiles.jar -C out .
cp WorldTiles.jar $SRV/plugins/WorldTiles.jar
chown mcserver:mcserver $SRV/plugins/WorldTiles.jar 2>/dev/null || true
ls -la WorldTiles.jar
