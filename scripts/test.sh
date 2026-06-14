#!/bin/sh
# Default gate: unit tests + JNI smoke (if JDK) + ASan/UBSan.
# Quick iteration: WEIZHI_SKIP_ASAN=1 ./scripts/test.sh
set -eu
root=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)

"$root/scripts/build.sh"
echo "== weizhi_tests =="
"$root/build/weizhi_tests"

echo "== JNI smoke =="
"$root/scripts/test-jni.sh"

if [ "${WEIZHI_SKIP_ASAN:-}" = "1" ]; then
    echo "skip ASan (WEIZHI_SKIP_ASAN=1)"
    exit 0
fi

echo "== ASan/UBSan =="
"$root/scripts/test-asan.sh"
