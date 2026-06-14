#!/bin/sh
# Run tests under AddressSanitizer + UBSan to catch OOB, UAF, and some UB.
# Leak detection is weak on macOS; on Linux set ASAN_OPTIONS=detect_leaks=1
set -eu
root=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
"$root/scripts/fetch-deps.sh"
out="$root/build-asan"
rm -rf "$out"
mkdir -p "$out"
cd "$out"
cmake "$root" -DWEIZHI_SANITIZE=ON -DCMAKE_BUILD_TYPE=Debug
jobs=$(sysctl -n hw.ncpu 2>/dev/null || getconf _NPROCESSORS_ONLN 2>/dev/null || echo 4)
cmake --build . -- -j"$jobs"

export ASAN_OPTIONS="${ASAN_OPTIONS:-halt_on_error=1:abort_on_error=1:detect_stack_use_after_return=1}"
export UBSAN_OPTIONS="${UBSAN_OPTIONS:-halt_on_error=1:print_stacktrace=1}"
# Enable leak detection by default on Linux; AppleClang on macOS usually lacks detect_leaks
case "$(uname -s)" in
    Linux)
        export ASAN_OPTIONS="${ASAN_OPTIONS}:detect_leaks=1"
        ;;
esac

echo "Running ASan/UBSan tests..."
exec "$out/weizhi_tests"
