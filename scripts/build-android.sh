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

cmake "$root" \
    -DCMAKE_TOOLCHAIN_FILE="$ndk/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$abi" \
    -DANDROID_PLATFORM="android-$api" \
    -DANDROID_STL=c++_static \
    -DCMAKE_BUILD_TYPE=Release

jobs=$(sysctl -n hw.ncpu 2>/dev/null || getconf _NPROCESSORS_ONLN 2>/dev/null || echo 4)
cmake --build . -- -j"$jobs"

# Stage typed sample plugin next to JNI for device tests / local loading.
plugin_out="$out/plugins/echo_math"
mkdir -p "$plugin_out"
cp -f "$root/plugins/echo_math/generated/manifest.json" "$plugin_out/manifest.json" || true
built=$(find "$out" -name 'libecho_math.so' 2>/dev/null | head -1 || true)
if [ -n "$built" ] && [ "$built" != "$plugin_out/libecho_math.so" ]; then
    cp -f "$built" "$plugin_out/libecho_math.so" || true
elif [ -n "$built" ]; then
    : # already in place
fi

assets_plugin="$root/android/app/src/androidTest/assets/plugins/echo_math"
mkdir -p "$assets_plugin"
cp -f "$plugin_out/manifest.json" "$assets_plugin/" || true
if [ -f "$plugin_out/libecho_math.so" ]; then
    cp -f "$plugin_out/libecho_math.so" "$assets_plugin/" || true
fi

# Stage JNI into the :weizhi Android library (AAR packaging).
if [ -f "$out/libweizhijni.so" ]; then
    jni_dir="$root/android/weizhi/src/main/jniLibs/$abi"
    mkdir -p "$jni_dir"
    cp -f "$out/libweizhijni.so" "$jni_dir/libweizhijni.so"
fi

echo "NDK: $ndk"
echo "ABI: $abi  API: $api"
echo "artifacts: $out/libweizhi.a  $out/weizhi_tests"
if [ -f "$out/libweizhijni.so" ]; then
    echo "JNI:  $out/libweizhijni.so"
    echo "AAR jniLibs: $root/android/weizhi/src/main/jniLibs/$abi/libweizhijni.so"
fi
if [ -f "$plugin_out/libecho_math.so" ]; then
    echo "plugin: $plugin_out/libecho_math.so"
fi
echo "Java: $root/java/com/weizhi/WeizhiEngine.java"
echo "Gradle AAR: cd android && gradle :weizhi:assembleRelease"
