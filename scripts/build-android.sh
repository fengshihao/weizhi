#!/bin/sh
set -eu
root=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
"$root/scripts/fetch-deps.sh"

abi=${1:-arm64-v8a}
api=${ANDROID_API:-24}
sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}

pick_ndk() {
    if [ -n "${ANDROID_NDK_HOME:-}" ] && [ -f "$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" ]; then
        printf '%s\n' "$ANDROID_NDK_HOME"
        return
    fi
    if [ -n "${ANDROID_NDK_ROOT:-}" ] && [ -f "$ANDROID_NDK_ROOT/build/cmake/android.toolchain.cmake" ]; then
        printf '%s\n' "$ANDROID_NDK_ROOT"
        return
    fi
    if [ -n "${ANDROID_NDK_LATEST_HOME:-}" ] && [ -f "$ANDROID_NDK_LATEST_HOME/build/cmake/android.toolchain.cmake" ]; then
        printf '%s\n' "$ANDROID_NDK_LATEST_HOME"
        return
    fi
    if [ -d "$sdk/ndk" ]; then
        # Prefer the highest installed NDK version
        latest=$(ls -1 "$sdk/ndk" | sort -V | tail -1)
        if [ -n "$latest" ] && [ -f "$sdk/ndk/$latest/build/cmake/android.toolchain.cmake" ]; then
            printf '%s\n' "$sdk/ndk/$latest"
            return
        fi
    fi
    return 1
}

if ! ndk=$(pick_ndk); then
    echo "Android NDK not found. Set ANDROID_NDK_HOME, or install under \$ANDROID_HOME/ndk/" >&2
    exit 1
fi

case "$abi" in
    arm64-v8a|armeabi-v7a|x86_64|x86) ;;
    *)
        echo "usage: $0 [arm64-v8a|armeabi-v7a|x86_64|x86]" >&2
        exit 1
        ;;
esac

out="$root/build-android/$abi"
mkdir -p "$out"
cd "$out"

aot_flag=OFF
if [ "${WEIZHI_WAMR_AOT:-0}" = "1" ] || [ "${WEIZHI_WAMR_AOT:-}" = "ON" ]; then
    aot_flag=ON
fi

cmake "$root" \
    -DCMAKE_TOOLCHAIN_FILE="$ndk/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$abi" \
    -DANDROID_PLATFORM="android-$api" \
    -DANDROID_STL=c++_static \
    -DCMAKE_BUILD_TYPE=Release \
    -DWEIZHI_WAMR_AOT="$aot_flag"

jobs=$(sysctl -n hw.ncpu 2>/dev/null || getconf _NPROCESSORS_ONLN 2>/dev/null || echo 4)
cmake --build . -- -j"$jobs"

echo "NDK: $ndk"
echo "ABI: $abi  API: $api  WEIZHI_WAMR_AOT=$aot_flag"
echo "artifacts: $out/libweizhi.a  $out/weizhi_tests"
if [ -f "$out/libweizhijni.so" ]; then
    echo "JNI:  $out/libweizhijni.so"
fi
echo "Java: $root/java/com/weizhi/WeizhiEngine.java"
