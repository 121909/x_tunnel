#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
BUILD_TOOLS_VERSION="${BUILD_TOOLS_VERSION:-34.0.0}"
APKSIGNER="$ANDROID_HOME/build-tools/$BUILD_TOOLS_VERSION/apksigner"

"$ROOT_DIR/tools/build-android-aar.sh"
"$ROOT_DIR/tools/generate-release-keystore.sh"

pushd "$ROOT_DIR/android" >/dev/null
./gradlew clean assembleRelease bundleRelease
popd >/dev/null

APK_PATH="$ROOT_DIR/android/app/build/outputs/apk/release/app-release.apk"
AAB_PATH="$ROOT_DIR/android/app/build/outputs/bundle/release/app-release.aab"

if [[ -x "$APKSIGNER" && -f "$APK_PATH" ]]; then
  "$APKSIGNER" verify --print-certs "$APK_PATH"
fi

echo "Built signed release artifacts:"
echo "  APK: $APK_PATH"
echo "  AAB: $AAB_PATH"
