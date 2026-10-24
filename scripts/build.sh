#!/bin/sh
set -eu
root=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
"$root/scripts/fetch-deps.sh"
if [ -f "$root/third_party/micropython/ports/embed/embed.mk" ]; then
    # Optional Python experiment; a no-op once the embed sources are generated.
    "$root/scripts/build-python-embed.sh"
fi
mkdir -p "$root/build"
cd "$root/build"
cmake ..
jobs=$(sysctl -n hw.ncpu 2>/dev/null || getconf _NPROCESSORS_ONLN 2>/dev/null || echo 4)
make -j"$jobs"
# Ensure sample plugins are built for typed-native tests.
for p in echo_math echo_video; do
    cmake --build . --target "$p" -- -j"$jobs" || true
done
echo "artifacts: $root/build/libweizhi.a  $root/build/weizhi_tests"
