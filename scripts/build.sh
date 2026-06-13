#!/bin/sh
set -eu
root=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
"$root/scripts/fetch-deps.sh"
mkdir -p "$root/build"
cd "$root/build"
cmake ..
jobs=$(sysctl -n hw.ncpu 2>/dev/null || getconf _NPROCESSORS_ONLN 2>/dev/null || echo 4)
make -j"$jobs"
echo "产物: $root/build/libweizhi.a  $root/build/weizhi_tests"
