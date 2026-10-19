#!/bin/sh
set -eu
root=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
"$root/scripts/fetch-deps.sh"

abi=${1:-arm64-v8a}
# Align with the Gradle modules' minSdk (26); NDK media surface APIs need 26.
api=${ANDROID_API:-26}
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

# Stage typed sample plugins next to JNI for device tests / local loading.
for p in echo_math echo_video; do
    plugin_out="$out/plugins/$p"
    mkdir -p "$plugin_out"
    cp -f "$root/plugins/$p/generated/manifest.json" "$plugin_out/manifest.json" || true
    built=$(find "$out" -name "lib$p.so" 2>/dev/null | head -1 || true)
    if [ -n "$built" ] && [ "$built" != "$plugin_out/lib$p.so" ]; then
        cp -f "$built" "$plugin_out/lib$p.so" || true
    fi

    assets_plugin="$root/android/app/src/androidTest/assets/plugins/$p"
    mkdir -p "$assets_plugin"
    cp -f "$plugin_out/manifest.json" "$assets_plugin/" || true
    if [ -f "$plugin_out/lib$p.so" ]; then
        cp -f "$plugin_out/lib$p.so" "$assets_plugin/" || true
    fi
done

# Bundle the media plugins into the :weizhi-video AAR jniLibs (soname loading).
video_dir="$root/android/weizhi-video/src/main/jniLibs/$abi"
mkdir -p "$video_dir"
for so in libweizhivideo.so libweizhitranscode.so; do
    built=$(find "$out" -name "$so" 2>/dev/null | head -1 || true)
    if [ -n "$built" ]; then
        cp -f "$built" "$video_dir/$so"
        echo "weizhi-video jniLibs: $video_dir/$so ($(wc -c < "$video_dir/$so") bytes)"
    fi
done

# Stage stripped JNI into the :weizhi Android library (Release AAR).
# Keep unstripped $out/libweizhijni.so for local debugging.
if [ -f "$out/libweizhijni.so" ]; then
    jni_dir="$root/android/weizhi/src/main/jniLibs/$abi"
    stripped_dir="$out/stripped"
    mkdir -p "$jni_dir" "$stripped_dir"
    # llvm-strip is often a symlink to llvm-objcopy; do not require -type f.
    strip_bin=""
    for host in darwin-arm64 darwin-x86_64 linux-x86_64; do
        cand="$ndk/toolchains/llvm/prebuilt/$host/bin/llvm-strip"
        if [ -x "$cand" ]; then
            strip_bin=$cand
            break
        fi
    done
    if [ -z "$strip_bin" ]; then
        strip_bin=$(find "$ndk/toolchains/llvm/prebuilt" -name llvm-strip 2>/dev/null | head -1 || true)
    fi
    if [ -n "$strip_bin" ] && [ -x "$strip_bin" ]; then
        "$strip_bin" --strip-unneeded -o "$stripped_dir/libweizhijni.so" "$out/libweizhijni.so"
        cp -f "$stripped_dir/libweizhijni.so" "$jni_dir/libweizhijni.so"
        echo "stripped SO: $(wc -c < "$stripped_dir/libweizhijni.so") bytes (unstripped $(wc -c < "$out/libweizhijni.so"))"
    else
        echo "warning: llvm-strip not found; packaging unstripped SO" >&2
        cp -f "$out/libweizhijni.so" "$stripped_dir/libweizhijni.so"
        cp -f "$out/libweizhijni.so" "$jni_dir/libweizhijni.so"
    fi
fi

echo "NDK: $ndk"
echo "ABI: $abi  API: $api"
echo "artifacts: $out/libweizhi.a  $out/weizhi_tests"
if [ -f "$out/libweizhijni.so" ]; then
    echo "JNI:  $out/libweizhijni.so"
    if [ -f "$out/stripped/libweizhijni.so" ]; then
        echo "JNI stripped: $out/stripped/libweizhijni.so"
    fi
    echo "AAR jniLibs: $root/android/weizhi/src/main/jniLibs/$abi/libweizhijni.so"
fi
for p in echo_math echo_video; do
    if [ -f "$out/plugins/$p/lib$p.so" ]; then
        echo "plugin: $out/plugins/$p/lib$p.so"
    fi
done
echo "Java: $root/java/com/weizhi/WeizhiEngine.java"
echo "Gradle AAR: cd android && gradle :weizhi:assembleRelease"
