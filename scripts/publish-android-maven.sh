#!/usr/bin/env bash
# Build native JNI + release AARs, publish Maven repo for Agent1 weizhi-prebuilt import.
#
# Usage:
#   ./scripts/publish-android-maven.sh [abi]
#   ./scripts/publish-android-maven.sh arm64-v8a --to-maven-local
#   WEIZHI_PUBLISH_URL=... WEIZHI_PUBLISH_USERNAME=... WEIZHI_PUBLISH_PASSWORD=... \
#     ./scripts/publish-android-maven.sh --remote
#
# Output (default): android/build/maven/com/weizhi/...
# Agent1: ./import-weizhi-prebuilt.sh /path/to/weizhi/android/build/maven

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
ABI="${1:-arm64-v8a}"
shift || true

TARGET=repo
while [[ $# -gt 0 ]]; do
  case "$1" in
    --to-maven-local) TARGET=mavenLocal ;;
    --remote) TARGET=remote ;;
    -h|--help)
      echo "usage: $0 [abi] [--to-maven-local|--remote]" >&2
      exit 0
      ;;
    *)
      echo "unknown option: $1" >&2
      exit 1
      ;;
  esac
  shift
done

"$REPO_ROOT/scripts/build-android.sh" "$ABI"

cd "$REPO_ROOT/android"
export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -n "${ANDROID_HOME}" ]] && [[ ! -f local.properties ]]; then
  printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties
fi

if [[ -x ./gradlew ]]; then
  GRADLE=(./gradlew)
elif command -v gradle >/dev/null 2>&1; then
  GRADLE=(gradle)
else
  echo "gradlew / gradle not found" >&2
  exit 1
fi

GRADLE_PROPS=()
if [[ -n "${WEIZHI_PUBLISH_URL:-}" ]]; then
  GRADLE_PROPS+=(-PweizhiPublishUrl="${WEIZHI_PUBLISH_URL}")
fi

echo "==> assembleRelease (all publishable modules)"
"${GRADLE[@]}" --no-daemon "${GRADLE_PROPS[@]}" \
  :weizhi:assembleRelease \
  :caps:assembleRelease \
  :agent-tools:assembleRelease \
  :agent-tools-webview:assembleRelease \
  :agent-tools-mcp:assembleRelease

case "$TARGET" in
  repo)
    echo "==> publishWeizhiAndroidLibraries -> android/build/maven"
    "${GRADLE[@]}" --no-daemon "${GRADLE_PROPS[@]}" publishWeizhiAndroidLibraries
    echo "Maven repo: ${REPO_ROOT}/android/build/maven"
    echo "Agent1: ${REPO_ROOT}/../agent1/import-weizhi-prebuilt.sh ${REPO_ROOT}/android/build/maven"
    ;;
  mavenLocal)
    echo "==> publishWeizhiAndroidLibrariesToMavenLocal"
    "${GRADLE[@]}" --no-daemon "${GRADLE_PROPS[@]}" publishWeizhiAndroidLibrariesToMavenLocal
    ;;
  remote)
    if [[ -z "${WEIZHI_PUBLISH_URL:-}" ]]; then
      echo "Set WEIZHI_PUBLISH_URL (GitHub Packages: https://maven.pkg.github.com/OWNER/weizhi)" >&2
      exit 1
    fi
    echo "==> publishWeizhiAndroidLibrariesToRemote"
    "${GRADLE[@]}" --no-daemon "${GRADLE_PROPS[@]}" publishWeizhiAndroidLibrariesToRemote
    ;;
esac

echo "Coordinates: group=$(grep -E '^weizhi.group=' gradle.properties | cut -d= -f2) version=$(grep -E '^weizhi.version=' gradle.properties | cut -d= -f2)"
