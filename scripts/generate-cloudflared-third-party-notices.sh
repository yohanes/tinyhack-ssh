#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." >/dev/null && pwd)
CLOUDFLARED_DIR="$PROJECT_DIR/cloudflared"
OUTPUT="$PROJECT_DIR/licenses/cloudflared-2026.8.2-THIRD-PARTY-NOTICES.txt"
ASSET_OUTPUT="$PROJECT_DIR/app/src/main/assets/licenses/$(basename "$OUTPUT")"
TEMP_FILE=$(mktemp)
trap 'rm -f "$TEMP_FILE"' EXIT

[[ -d "$CLOUDFLARED_DIR/vendor" ]] || {
    echo "Missing cloudflared vendor tree: $CLOUDFLARED_DIR/vendor" >&2
    exit 1
}

{
    echo "cloudflared 2026.8.2 third-party dependency notices"
    echo
    echo "Generated from the exact vendored dependency tree used by this build."
    echo
    find "$CLOUDFLARED_DIR/vendor" -type f \
        \( -iname 'LICENSE*' -o -iname 'COPYING*' -o -iname 'NOTICE*' \) \
        -print0 | sort -z | while IFS= read -r -d '' license_file; do
            relative=${license_file#"$CLOUDFLARED_DIR/vendor/"}
            echo "==============================================================================="
            echo "$relative"
            echo "==============================================================================="
            cat "$license_file"
            echo
        done
} > "$TEMP_FILE"

install -m 0644 "$TEMP_FILE" "$OUTPUT"
install -m 0644 "$TEMP_FILE" "$ASSET_OUTPUT"
echo "Wrote $OUTPUT and $ASSET_OUTPUT"
