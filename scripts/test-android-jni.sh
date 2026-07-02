#!/bin/sh
# Build arm64 JNI .so (optional AOT), then run Android instrumented tests.
# Invoked as: ./scripts/test.sh android
# Optional: ./scripts/test.sh android --skip-native
# Optional: ./scripts/test.sh android --aot   (WEIZHI_WAMR_AOT=1 + compile add.aot if wamrc exists)
set -eu
root=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)

skip_native=0
want_aot=0
for arg in "$@"; do
    case "$arg" in
        --skip-native|--skip-build) skip_native=1 ;;
        --aot) want_aot=1 ;;
        -h|--help)
            echo "usage: $0 [--skip-native] [--aot]" >&2
            exit 0
            ;;
        *)
            echo "unknown option: $arg" >&2
            exit 1
            ;;
    esac
done

export WEIZHI_WAMR_AOT=0
if [ "$want_aot" -eq 1 ]; then
    export WEIZHI_WAMR_AOT=1
fi

if [ "$skip_native" -eq 0 ]; then
    "$root/scripts/build-android.sh" arm64-v8a
elif [ ! -f "$root/build-android/arm64-v8a/libweizhijni.so" ]; then
    echo "missing $root/build-android/arm64-v8a/libweizhijni.so (run without --skip-native)" >&2
    exit 1
fi

if [ "$want_aot" -eq 1 ]; then
    if "$root/scripts/compile-pack-aot.sh"; then
        mkdir -p "$root/android/app/src/androidTest/assets"
        cp "$root/build-android/packs/add.aot" "$root/android/app/src/androidTest/assets/add.aot"
        adb push "$root/build-android/packs/add.aot" /data/local/tmp/weizhi-add.aot >/dev/null || true
    else
        echo "warn: compile-pack-aot.sh failed; AOT instrumented tests will be skipped" >&2
        rm -f "$root/android/app/src/androidTest/assets/add.aot"
    fi
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
if adb logcat -d -s weizhi-bench:I 2>/dev/null | grep -q 'kind='; then
    echo "== weizhi-bench (logcat) =="
    adb logcat -d -s weizhi-bench:I | grep 'kind=' | tail -20
fi
