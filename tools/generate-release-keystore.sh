#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ANDROID_DIR="$ROOT_DIR/android"
SIGNING_DIR="$ANDROID_DIR/signing"
PROPERTIES_FILE="$ANDROID_DIR/keystore.properties"
KEYSTORE_RELATIVE="signing/xtunnel-release.jks"
KEYSTORE_PATH="$ANDROID_DIR/$KEYSTORE_RELATIVE"
ALIAS_NAME="xtunnel-release"

mkdir -p "$SIGNING_DIR"

if [[ -f "$PROPERTIES_FILE" && -f "$KEYSTORE_PATH" && "${FORCE:-0}" != "1" ]]; then
  echo "Release keystore already exists at $KEYSTORE_PATH"
  exit 0
fi

PASSWORD="$(
python3 - <<'PY'
import secrets
print(secrets.token_urlsafe(24))
PY
)"

rm -f "$KEYSTORE_PATH"
keytool -genkeypair \
  -alias "$ALIAS_NAME" \
  -keyalg RSA \
  -keysize 4096 \
  -validity 3650 \
  -keystore "$KEYSTORE_PATH" \
  -storepass "$PASSWORD" \
  -keypass "$PASSWORD" \
  -dname "CN=XTunnel Release, OU=Mobile, O=XTunnel, L=Unknown, S=Unknown, C=US" \
  -noprompt

cat > "$PROPERTIES_FILE" <<EOF
storeFile=$KEYSTORE_RELATIVE
storePassword=$PASSWORD
keyAlias=$ALIAS_NAME
keyPassword=$PASSWORD
EOF

echo "Generated release keystore:"
echo "  keystore: $KEYSTORE_PATH"
echo "  properties: $PROPERTIES_FILE"
echo "Rotate these credentials before distributing a production build."
