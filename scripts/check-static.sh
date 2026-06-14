#!/bin/sh
# Static checks (does not run the program). Prefer cppcheck; prompt to install if missing.
set -eu
root=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)

if ! command -v cppcheck >/dev/null 2>&1; then
    echo "cppcheck not found. Install with: brew install cppcheck" >&2
    exit 1
fi

cppcheck --enable=warning,style,performance,portability \
    --error-exitcode=1 \
    --inline-suppr \
    --suppress=missingIncludeSystem \
    -I "$root/include" \
    -I "$root/src" \
    "$root/src" \
    "$root/jni" \
    "$root/tests"
