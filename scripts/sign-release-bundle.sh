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
UNSIGNED_AAB="$PROJECT_DIR/app/build/outputs/bundle/playRelease/app-play-release.aab"
SIGNED_AAB="$OUTPUT_DIR/tinyhack-ssh-play-release.aab"

"$GRADLE_BIN" -p "$PROJECT_DIR" clean lintPlayRelease bundlePlayRelease
mkdir -p "$OUTPUT_DIR"
cp "$UNSIGNED_AAB" "$SIGNED_AAB"

jarsigner \
    -keystore "$TINYHACK_SSH_KEYSTORE_PATH" \
    -storepass:env TINYHACK_SSH_KEYSTORE_PASSWORD \
    -keypass:env TINYHACK_SSH_KEY_PASSWORD \
    -sigalg SHA256withRSA \
    -digestalg SHA-256 \
    "$SIGNED_AAB" "$TINYHACK_SSH_KEY_ALIAS"
jarsigner -verify -verbose -certs "$SIGNED_AAB"

echo "Signed Google Play Android App Bundle: $SIGNED_AAB"
