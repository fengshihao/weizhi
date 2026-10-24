#!/bin/sh
set -eu
root=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
# QuickJS is pinned so builds and CI are reproducible; upgrading is an explicit
# action (edit QUICKJS_PIN, then run the full gates: ./scripts/test.sh [+ android]).
QUICKJS_PIN=84e723f1ace5e3823cef8149dd0f890fe74b77ce
# MicroPython for the optional Python runtime (experiment branch; see
# docs/experiment-python.md). Same pin rule as QuickJS.
MICROPYTHON_PIN=0fd6c573ea815774668bbb16b8e197c8822368b2 # tag v1.29.0
mkdir -p "$root/third_party"

fetch_pinned() { # fetch_pinned <dir> <url> <pin>
    dir="$1"; url="$2"; pin="$3"
    if [ -d "$dir" ] && [ ! -d "$dir/.git" ] && [ -n "$(ls -A "$dir" 2>/dev/null)" ]; then
        echo "$dir exists without git metadata; remove it and re-run" >&2
        exit 1
    fi
    if [ ! -d "$dir/.git" ]; then
        git init "$dir"
        git -C "$dir" remote add origin "$url"
    fi
    if [ "$(git -C "$dir" rev-parse HEAD 2>/dev/null || true)" != "$pin" ]; then
        # depth-1 clones cannot checkout arbitrary commits directly; fetch the pin by sha.
        git -C "$dir" fetch --depth 1 origin "$pin"
        git -C "$dir" checkout --detach FETCH_HEAD
    fi
}

qj="$root/third_party/quickjs"
fetch_pinned "$qj" https://github.com/bellard/quickjs.git "$QUICKJS_PIN"

mp="$root/third_party/micropython"
fetch_pinned "$mp" https://github.com/micropython/micropython.git "$MICROPYTHON_PIN"
