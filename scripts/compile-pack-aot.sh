#!/bin/sh
# Compile tests/fixtures add.wasm → build-android/packs/add.aot (aarch64) via wamrc.
set -eu
root=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
outdir="$root/build-android/packs"
wasm="$outdir/add.wasm"
aot="$outdir/add.aot"
mkdir -p "$outdir"

# Same bytes as tests/test_engine.c ADD_WASM
printf '\x00\x61\x73\x6d\x01\x00\x00\x00\x01\x07\x01\x60\x02\x7f\x7f\x01\x7f\x03\x02\x01\x00\x07\x07\x01\x03\x61\x64\x64\x00\x00\x0a\x09\x01\x07\x00\x20\x00\x20\x01\x6a\x0b' > "$wasm"

pick_wamrc() {
    if command -v wamrc >/dev/null 2>&1; then
        command -v wamrc
        return
    fi
    for c in \
        "$root/third_party/wamr/wamr-compiler/build/wamrc" \
        "$root/build-wamrc/wamrc" \
        "$HOME/wamrc"
    do
        if [ -x "$c" ]; then
            printf '%s\n' "$c"
            return
        fi
    done
    return 1
}

if ! wamrc=$(pick_wamrc); then
    echo "wamrc not found. Build it once:" >&2
    echo "  cd third_party/wamr/wamr-compiler && ./build_llvm.sh && mkdir -p build && cd build && cmake .. -DWAMR_BUILD_PLATFORM=darwin && cmake --build ." >&2
    exit 1
fi

# Target the phone ABI used by scripts/build-android.sh
"$wamrc" -o "$aot" --target=aarch64 "$wasm"
echo "wrote $aot"
ls -la "$aot"
