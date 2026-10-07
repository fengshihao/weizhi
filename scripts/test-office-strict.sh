#!/bin/sh
# Optional well-formed XML check on OfficeTest artifacts (unzip + xmllint).
set -eu
root=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
docx="$root/build/office-artifacts/report.docx"
pptx="$root/build/office-artifacts/deck.pptx"
if [ ! -f "$docx" ] || [ ! -f "$pptx" ]; then
  echo "run ./scripts/test-jni.sh first (creates build/office-artifacts/report.docx and deck.pptx)" >&2
  exit 1
fi
if ! command -v unzip >/dev/null 2>&1; then
  echo "skip strict office: unzip not installed"
  exit 0
fi

lint_tree() {
  dest=$1
  if ! command -v xmllint >/dev/null 2>&1; then
    return 0
  fi
  list="$dest.list"
  find "$dest" -type f \( -name '*.xml' -o -name '*.rels' \) > "$list"
  while IFS= read -r f; do
    xmllint --noout "$f"
  done < "$list"
  rm -f "$list"
}

check_zip() {
  file=$1
  needle=$2
  dest=$3
  head -c 2 "$file" | grep -q PK || { echo "not PK zip: $file" >&2; exit 1; }
  unzip -l "$file" | grep -q "$needle"
  rm -rf "$dest"
  mkdir -p "$dest"
  unzip -q -o "$file" -d "$dest"
  lint_tree "$dest"
}

check_zip "$docx" 'word/document.xml' "$root/build/office-artifacts/unz-docx"
check_zip "$pptx" 'ppt/slides/slide1.xml' "$root/build/office-artifacts/unz-pptx"
if command -v xmllint >/dev/null 2>&1; then
  echo "strict office: xmllint OK"
else
  echo "strict office: unzip + part list OK (install xmllint for well-formed check)"
fi
