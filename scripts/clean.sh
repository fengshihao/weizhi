#!/bin/sh
set -eu
root=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
rm -rf "$root/build" "$root/build-asan" "$root/build-android"
echo "Removed $root/build, $root/build-asan, and $root/build-android"
