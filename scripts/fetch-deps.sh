#!/bin/sh
set -eu
root=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
# QuickJS is pinned so builds and CI are reproducible; upgrading is an explicit
# action (edit QUICKJS_PIN, then run the full gates: ./scripts/test.sh [+ android]).
QUICKJS_PIN=84e723f1ace5e3823cef8149dd0f890fe74b77ce
mkdir -p "$root/third_party"
qj="$root/third_party/quickjs"
if [ -f "$qj/quickjs.h" ] && [ ! -d "$qj/.git" ]; then
    echo "third_party/quickjs exists without git metadata; remove it and re-run" >&2
    exit 1
fi
if [ ! -d "$qj/.git" ]; then
    git init "$qj"
    git -C "$qj" remote add origin https://github.com/bellard/quickjs.git
fi
if [ "$(git -C "$qj" rev-parse HEAD 2>/dev/null || true)" != "$QUICKJS_PIN" ]; then
    # depth-1 clones cannot checkout arbitrary commits directly; fetch the pin by sha.
    git -C "$qj" fetch --depth 1 origin "$QUICKJS_PIN"
    git -C "$qj" checkout --detach FETCH_HEAD
fi
