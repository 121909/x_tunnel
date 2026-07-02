#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

"$ROOT_DIR/tools/build-android-aar.sh"
pushd "$ROOT_DIR/android" >/dev/null
./gradlew assembleDebug
popd >/dev/null
