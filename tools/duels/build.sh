#!/bin/bash
# Builds BerlinDuels.jar against the libraries inside the Paper jar of the server, and puts it in the plugins folder of the duels server.
#   ./build.sh            (the Paper jar is /media/data/minecraft-server/paper.jar; the duels server is /media/data/minecraft-duels)
set -e
cd "$(dirname "$0")"
SRC_SRV=/media/data/minecraft-server
DST=/media/data/minecraft-duels
rm -rf libs && mkdir -p libs
(cd libs && unzip -oq $SRC_SRV/paper.jar 'META-INF/libraries/*' 'META-INF/versions/*')
CP=$(find libs/META-INF/libraries -name '*.jar' | tr '\n' ':')$(find libs/META-INF/versions -name 'paper-*.jar' | head -1):$(find $SRC_SRV/libraries -name '*.jar' | tr '\n' ':')
rm -rf out && mkdir -p out
if ! javac --release 25 -Xlint:-options -proc:none -cp "$CP" -d out $(find src -name '*.java') > javac.log 2>&1; then
  grep -v 'warning: \[classfile\]' javac.log
  echo "BUILD FAILED: no jar was made or installed" >&2
  exit 1
fi
rm -f javac.log
cp plugin.yml out/
jar cf BerlinDuels.jar -C out .
mkdir -p $DST/plugins
cp BerlinDuels.jar $DST/plugins/BerlinDuels.jar
chown mcserver:mcserver $DST/plugins/BerlinDuels.jar 2>/dev/null || true
