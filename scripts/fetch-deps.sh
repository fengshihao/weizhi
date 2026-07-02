#!/bin/sh
set -eu
root=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
mkdir -p "$root/third_party"
if [ ! -f "$root/third_party/quickjs/quickjs.h" ]; then
    git clone --depth 1 https://github.com/bellard/quickjs.git "$root/third_party/quickjs"
fi
