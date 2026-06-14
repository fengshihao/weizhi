#!/bin/sh
set -eu
root=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
abi=${1:-arm64-v8a}
bin="$root/build-android/$abi/weizhi_tests"
so="$root/build-android/$abi/libweizhijni.so"
max_bytes=${WEIZHI_SIZE_MAX:-1572864} # default 1.5MB strip cap (baseline ~1.1MB + headroom)

if [ ! -f "$bin" ] && [ ! -f "$so" ]; then
    echo "Run first: ./scripts/build-android.sh $abi" >&2
    exit 1
fi

ndk=${ANDROID_NDK_HOME:-}
if [ -z "$ndk" ] || [ ! -x "$ndk/toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-strip" ]; then
    sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}
    latest=$(ls -1 "$sdk/ndk" 2>/dev/null | sort -V | tail -1)
    ndk="$sdk/ndk/$latest"
fi
strip="$ndk/toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-strip"
if [ ! -x "$strip" ]; then
    strip="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
fi

check_one() {
    path=$1
    label=$2
    tmp=$(mktemp)
    cp "$path" "$tmp"
    "$strip" "$tmp"
    size=$(wc -c < "$tmp" | tr -d ' ')
    rm -f "$tmp"
    echo "$label after strip: $size bytes"
    if [ "$size" -gt "$max_bytes" ]; then
        echo "exceeds limit $max_bytes" >&2
        exit 1
    fi
}

if [ -f "$bin" ]; then
    check_one "$bin" "weizhi_tests"
fi
if [ -f "$so" ]; then
    check_one "$so" "libweizhijni.so"
fi
echo "size check passed"
