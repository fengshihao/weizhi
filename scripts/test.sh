#!/bin/sh
# Default gate: unit tests + JNI smoke (if JDK) + ASan/UBSan.
# Device Java/JNI: ./scripts/test.sh android
# Quick desktop: WEIZHI_SKIP_ASAN=1 ./scripts/test.sh
set -eu
root=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)

case "${1:-}" in
    android|device)
        shift
        exec "$root/scripts/test-android-jni.sh" "$@"
        ;;
    ""|desktop|host)
        ;;
    -h|--help|help)
        cat <<'EOF'
usage: ./scripts/test.sh [desktop|android]

  (default)  desktop C tests + JNI smoke + ASan
  android    build arm64 libweizhijni.so and run instrumented Java tests on device

  ./scripts/test.sh android --skip-native   reuse existing .so
EOF
        exit 0
        ;;
    *)
        echo "unknown target: $1 (try: desktop | android)" >&2
        exit 1
        ;;
esac

"$root/scripts/build.sh"
echo "== weizhi_tests =="
WEIZHI_BUILD_DIR="$root/build" "$root/build/weizhi_tests"

echo "== JNI smoke =="
"$root/scripts/test-jni.sh"

if [ "${WEIZHI_SKIP_ASAN:-}" = "1" ]; then
    echo "skip ASan (WEIZHI_SKIP_ASAN=1)"
    exit 0
fi

echo "== ASan/UBSan =="
"$root/scripts/test-asan.sh"
