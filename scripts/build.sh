#!/bin/sh
set -eu
root=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
"$root/scripts/fetch-deps.sh"
mkdir -p "$root/build"
cd "$root/build"
cmake ..
jobs=$(sysctl -n hw.ncpu 2>/dev/null || getconf _NPROCESSORS_ONLN 2>/dev/null || echo 4)
make -j"$jobs"
# Ensure sample plugin is built for typed-native tests.
cmake --build . --target echo_math -- -j"$jobs" || true
echo "artifacts: $root/build/libweizhi.a  $root/build/weizhi_tests"
