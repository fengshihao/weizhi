#!/bin/sh
# Build arm64 JNI .so, then run Android instrumented JNI smoke on a connected device.
# Invoked as: ./scripts/test.sh android
# Optional: ./scripts/test.sh android --skip-native  (reuse existing .so)
set -eu
root=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)

skip_native=0
for arg in "$@"; do
    case "$arg" in
        --skip-native|--skip-build) skip_native=1 ;;
        -h|--help)
            echo "usage: $0 [--skip-native]" >&2
            exit 0
            ;;
        *)
            echo "unknown option: $arg" >&2
            exit 1
            ;;
    esac
done

if [ "$skip_native" -eq 0 ]; then
    "$root/scripts/build-android.sh" arm64-v8a
elif [ ! -f "$root/build-android/arm64-v8a/libweizhijni.so" ]; then
    echo "missing $root/build-android/arm64-v8a/libweizhijni.so (run without --skip-native)" >&2
    exit 1
fi

if ! command -v adb >/dev/null 2>&1; then
    echo "adb not found" >&2
    exit 1
fi
if ! adb get-state >/dev/null 2>&1; then
    echo "no authorized Android device (adb devices)" >&2
    exit 1
fi

export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"

cd "$root/android"
if [ ! -f local.properties ]; then
    printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties
fi

if [ -x ./gradlew ]; then
    gradlew=./gradlew
elif command -v gradle >/dev/null 2>&1; then
    gradlew=gradle
else
    echo "gradle / gradlew not found" >&2
    exit 1
fi

echo "Running connectedDebugAndroidTest with $gradlew ..."
"$gradlew" :app:connectedDebugAndroidTest

echo "Android JNI smoke OK"
