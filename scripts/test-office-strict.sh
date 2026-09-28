#!/bin/sh
# Optional well-formed XML check on OfficeTest artifact (unzip + xmllint).
set -eu
root=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
docx="$root/build/office-artifacts/report.docx"
if [ ! -f "$docx" ]; then
  echo "run ./scripts/test-jni.sh first (creates build/office-artifacts/report.docx)" >&2
  exit 1
fi
if ! command -v unzip >/dev/null 2>&1; then
  echo "skip strict office: unzip not installed"
  exit 0
fi
head -c 2 "$docx" | grep -q PK || { echo "not PK zip: $docx" >&2; exit 1; }
unzip -l "$docx" | grep -q 'word/document.xml'
dest="$root/build/office-artifacts/unz"
rm -rf "$dest"
mkdir -p "$dest"
unzip -q -o "$docx" -d "$dest"
if command -v xmllint >/dev/null 2>&1; then
  xmllint --noout "$dest/word/document.xml"
  echo "strict office: xmllint OK"
else
  echo "strict office: unzip + part list OK (install xmllint for well-formed check)"
fi
