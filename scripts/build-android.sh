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
        # 选版本号最大的已安装 NDK
        latest=$(ls -1 "$sdk/ndk" | sort -V | tail -1)
        if [ -n "$latest" ] && [ -f "$sdk/ndk/$latest/build/cmake/android.toolchain.cmake" ]; then
            printf '%s\n' "$sdk/ndk/$latest"
            return
        fi
    fi
    return 1
}

if ! ndk=$(pick_ndk); then
    echo "找不到 Android NDK。请设置 ANDROID_NDK_HOME，或安装到 \$ANDROID_HOME/ndk/" >&2
    exit 1
fi

case "$abi" in
    arm64-v8a|armeabi-v7a|x86_64|x86) ;;
    *)
        echo "用法: $0 [arm64-v8a|armeabi-v7a|x86_64|x86]" >&2
        exit 1
        ;;
esac

out="$root/build-android/$abi"
mkdir -p "$out"
cd "$out"

cmake "$root" \
    -DCMAKE_TOOLCHAIN_FILE="$ndk/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$abi" \
    -DANDROID_PLATFORM="android-$api" \
    -DANDROID_STL=c++_static \
    -DCMAKE_BUILD_TYPE=Release

jobs=$(sysctl -n hw.ncpu 2>/dev/null || getconf _NPROCESSORS_ONLN 2>/dev/null || echo 4)
cmake --build . -- -j"$jobs"

echo "NDK: $ndk"
echo "ABI: $abi  API: $api"
echo "产物: $out/libweizhi.a  $out/weizhi_tests"
