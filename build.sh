#!/bin/sh
set -eu
cd "$(dirname "$0")"
josm_jar=${JOSM_JAR:-/usr/share/josm/josm.jar}
if [ ! -r "$josm_jar" ]; then
    echo "Set JOSM_JAR to the path of a readable JOSM JAR." >&2
    exit 1
fi
rm -rf build/classes
mkdir -p build/classes
find src/main/java -name '*.java' -print > build/sources.txt
javac --release 17 -encoding UTF-8 -cp "$josm_jar" -d build/classes @build/sources.txt
jar --create --file build/orbilume.jar --manifest manifest.mf -C build/classes . LICENSE
