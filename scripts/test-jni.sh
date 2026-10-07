#!/bin/sh
# Desktop JNI smoke against libweizhijni built by CMake.
set -eu
root=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
libdir="$root/build"
if [ -f "$libdir/libweizhijni.dylib" ]; then
    lib="$libdir/libweizhijni.dylib"
elif [ -f "$libdir/libweizhijni.so" ]; then
    lib="$libdir/libweizhijni.so"
else
    echo "skip JNI smoke: libweizhijni not built (need JDK / JAVA_HOME)"
    exit 0
fi
if ! command -v javac >/dev/null 2>&1; then
    echo "skip JNI smoke: javac not found"
    exit 0
fi

outdir="$root/build/java-classes"
rm -rf "$outdir"
mkdir -p "$outdir"
javac -encoding UTF-8 -d "$outdir" \
    "$root/java/com/weizhi/WeizhiLimits.java" \
    "$root/java/com/weizhi/WeizhiEngine.java" \
    "$root/java/com/weizhi/platform/MiniJson.java" \
    "$root/java/com/weizhi/platform/LocalWorkspace.java" \
    "$root/java/com/weizhi/platform/ZipTools.java" \
    "$root/java/com/weizhi/platform/PlatformScripts.java" \
    "$root/java/com/weizhi/platform/PlatformHost.java" \
    "$root/java/com/weizhi/platform/OrganizeFiles.java" \
    "$root/java/com/weizhi/desktop/DesktopCaps.java" \
    "$root/tests/java/OfficeTest.java" \
    "$root/tests/java/ApiCardsTest.java" \
    "$root/tests/java/FetchRedirectTest.java" \
    "$root/tests/java/SmokeTest.java"

echo "Running JNI smoke ($lib)..."
java -Djava.library.path="$libdir" -cp "$outdir" tests.java.SmokeTest
