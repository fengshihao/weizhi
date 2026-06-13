#!/bin/sh
set -eu
root=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
rm -rf "$root/build"
echo "已删除 $root/build"
