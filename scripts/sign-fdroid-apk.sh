#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." >/dev/null && pwd)
TINYHACK_SSH_RELEASE_ENV_FILE=${TINYHACK_SSH_RELEASE_ENV_FILE:-"$PROJECT_DIR/.env.release"}

[[ -f "$TINYHACK_SSH_RELEASE_ENV_FILE" ]] || {
    echo "Missing $TINYHACK_SSH_RELEASE_ENV_FILE; copy .env.release.example and fill it in." >&2
    exit 1
}

# shellcheck disable=SC1090
source "$TINYHACK_SSH_RELEASE_ENV_FILE"
: "${TINYHACK_SSH_KEYSTORE_PATH:?Set TINYHACK_SSH_KEYSTORE_PATH}"
: "${TINYHACK_SSH_KEYSTORE_PASSWORD:?Set TINYHACK_SSH_KEYSTORE_PASSWORD}"
: "${TINYHACK_SSH_KEY_ALIAS:?Set TINYHACK_SSH_KEY_ALIAS}"
: "${TINYHACK_SSH_KEY_PASSWORD:?Set TINYHACK_SSH_KEY_PASSWORD}"

[[ -f "$TINYHACK_SSH_KEYSTORE_PATH" ]] || {
    echo "Keystore not found: $TINYHACK_SSH_KEYSTORE_PATH" >&2
    exit 1
}

export TINYHACK_SSH_KEYSTORE_PASSWORD TINYHACK_SSH_KEY_PASSWORD
export ANDROID_HOME=${ANDROID_HOME:-/home/yohanes/Android/Sdk}
GRADLE_BIN=${TINYHACK_SSH_GRADLE_BIN:-/home/yohanes/apps/gradle-8.13/bin/gradle}
OUTPUT_DIR="$PROJECT_DIR/release"
UNSIGNED_APK="$PROJECT_DIR/app/build/outputs/apk/fdroid/release/app-fdroid-release-unsigned.apk"
SIGNED_APK="$OUTPUT_DIR/tinyhack-ssh-fdroid-release.apk"
BUILD_TOOLS=$(find "$ANDROID_HOME/build-tools" -mindepth 1 -maxdepth 1 \
    -type d -name '[0-9]*' | sort -V | tail -1)
APKSIGNER="$BUILD_TOOLS/apksigner"

[[ -x "$APKSIGNER" ]] || {
    echo "apksigner not found under $ANDROID_HOME/build-tools" >&2
    exit 1
}

"$GRADLE_BIN" -p "$PROJECT_DIR" clean lintFdroidRelease assembleFdroidRelease
mkdir -p "$OUTPUT_DIR"

"$APKSIGNER" sign \
    --ks "$TINYHACK_SSH_KEYSTORE_PATH" \
    --ks-key-alias "$TINYHACK_SSH_KEY_ALIAS" \
    --ks-pass env:TINYHACK_SSH_KEYSTORE_PASSWORD \
    --key-pass env:TINYHACK_SSH_KEY_PASSWORD \
    --out "$SIGNED_APK" \
    "$UNSIGNED_APK"
"$APKSIGNER" verify --verbose --print-certs "$SIGNED_APK"

echo "Signed F-Droid/direct-download APK: $SIGNED_APK"
