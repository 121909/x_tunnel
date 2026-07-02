#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
ANDROID_NDK_VERSION="${ANDROID_NDK_VERSION:-29.0.14206865}"
ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/$ANDROID_NDK_VERSION}"
GOPATH_BIN="$(go env GOPATH)/bin"

export ANDROID_HOME
export ANDROID_NDK_HOME
export PATH="$PATH:$GOPATH_BIN"

if [[ ! -d "$ANDROID_HOME" ]]; then
  echo "ANDROID_HOME not found: $ANDROID_HOME" >&2
  exit 1
fi

if [[ ! -d "$ANDROID_NDK_HOME" ]]; then
  echo "ANDROID_NDK_HOME not found: $ANDROID_NDK_HOME" >&2
  exit 1
fi

if ! command -v gomobile >/dev/null 2>&1; then
  go install golang.org/x/mobile/cmd/gomobile@latest
fi

if ! command -v gobind >/dev/null 2>&1; then
  go install golang.org/x/mobile/cmd/gobind@latest
fi

pushd "$ROOT_DIR" >/dev/null
gomobile init
mkdir -p android/app/libs
gomobile bind -target=android -androidapi=26 -o android/app/libs/xtunnel.aar ./mobile/bridge
popd >/dev/null

echo "Built android/app/libs/xtunnel.aar"
