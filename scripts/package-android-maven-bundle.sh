#!/usr/bin/env bash
# Create a .tgz of android/build/maven for Agent1 WEIZHI_PREBUILT_URL / import-weizhi-prebuilt.sh
#
# Usage:
#   ./scripts/package-android-maven-bundle.sh
#   ./scripts/package-android-maven-bundle.sh /path/to/out/weizhi-android-maven.tgz

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
MAVEN="${REPO_ROOT}/android/build/maven"
OUT="${1:-${REPO_ROOT}/dist/weizhi-android-maven.tgz}"

if [[ ! -d "${MAVEN}/com/weizhi" ]]; then
  echo "Missing ${MAVEN}/com/weizhi — run ./scripts/publish-android-maven.sh first" >&2
  exit 1
fi

mkdir -p "$(dirname "${OUT}")"
TMP="$(mktemp -d)"
trap 'rm -rf "${TMP}"' EXIT
mkdir -p "${TMP}/maven-root"
cp -a "${MAVEN}/." "${TMP}/maven-root/"
tar -czf "${OUT}" -C "${TMP}/maven-root" .
echo "Bundle: ${OUT}"
echo "Agent1: WEIZHI_PREBUILT_URL='file://${OUT}' ./import-weizhi-prebuilt.sh"
