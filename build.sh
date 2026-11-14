#!/bin/sh
set -eu
cd "$(dirname "$0")"
josm_jar=${JOSM_JAR:-/usr/share/josm/josm.jar}
if [ ! -r "$josm_jar" ]; then
    echo "Set JOSM_JAR to the path of a readable JOSM JAR." >&2
    exit 1
fi
mkdir -p build/classes build/test-classes
find src/main/java -name '*.java' -print > build/sources.txt
find src/test/java -name '*.java' -print > build/tests.txt
javac --release 17 -encoding UTF-8 -cp "$josm_jar" -d build/classes @build/sources.txt
javac --release 17 -encoding UTF-8 -cp "$josm_jar:build/classes" -d build/test-classes @build/tests.txt
jar --create --file build/orbilume.jar --manifest manifest.mf -C build/classes . LICENSE
java -Djava.awt.headless=true -cp "$josm_jar:build/orbilume.jar:build/test-classes" \
    org.openstreetmap.josm.plugins.orbilume.OrbilumeTest "$@"
