#!/bin/sh
set -eu
root=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
rm -rf "$root/build" "$root/build-android"
echo "已删除 $root/build 与 $root/build-android"
