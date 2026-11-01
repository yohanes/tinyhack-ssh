#!/usr/bin/env bash
set -euo pipefail
PROJECT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." >/dev/null && pwd)
SDK=${ANDROID_HOME:-/home/yohanes/Android/Sdk}
BUILD_TOOLS=$(find "$SDK/build-tools" -mindepth 1 -maxdepth 1 -type d -name '[0-9]*' | sort -V | tail -1)
AAB="$PROJECT_DIR/release/tinyhack-ssh-play-release.aab"
APK="$PROJECT_DIR/release/tinyhack-ssh-fdroid-release.apk"
VERIFY_DIR=$(mktemp -d)
trap 'rm -rf "$VERIFY_DIR"' EXIT
jarsigner -verify "$AAB" > "$VERIFY_DIR/signature.txt"
grep -q 'jar verified' "$VERIFY_DIR/signature.txt"
"$BUILD_TOOLS/apksigner" verify "$APK"
"$BUILD_TOOLS/zipalign" -c -P 16 4 "$APK"
for artifact in "$AAB" "$APK"; do
    directory="$VERIFY_DIR/$(basename "$artifact")"
    mkdir -p "$directory"
    unzip -q "$artifact" '*arm64-v8a/*.so' -d "$directory"
    while IFS= read -r -d '' elf; do
        alignments=$(readelf -lW "$elf" | awk '/ LOAD / {print $NF}' | sort -u)
        [[ "$alignments" == "0x4000" ]] || {
            echo "Invalid ELF alignment in $elf: $alignments" >&2
            exit 1
        }
    done < <(find "$directory" -name '*.so' -print0)
done
"$BUILD_TOOLS/aapt" dump badging "$APK" | sed -n '1,3p'
for artifact in "$AAB" "$APK"; do
    (cd "$PROJECT_DIR/release" && sha256sum "$(basename "$artifact")") > "$artifact.sha256"
done
echo "Release signatures and 16 KiB alignment verified"
