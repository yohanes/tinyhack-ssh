#!/usr/bin/env bash
set -euo pipefail
PROJECT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." >/dev/null && pwd)
SOURCE=${GHOSTTY_SOURCE_DIR:-"$PROJECT_DIR/ghostty"}
GHOSTTY_COMMIT=83fb95624b0439ee660c2679b43b38c8b63769cd # v1.4.0
ZIG=${ZIG:-zig}
if [[ ! -d "$SOURCE" ]]; then
    git clone --branch v1.4.0 https://github.com/ghostty-org/ghostty.git "$SOURCE"
fi
if [[ -e "$SOURCE/.git" ]]; then
    source_commit=$(git -C "$SOURCE" rev-parse HEAD)
else
    source_commit=$(cat "$SOURCE/.tinyhack-source-commit")
fi
[[ "$source_commit" == "$GHOSTTY_COMMIT" ]] || {
    echo "Expected Ghostty v1.4.0 at $GHOSTTY_COMMIT in $SOURCE" >&2
    exit 1
}
NDK_DIR=${TINYHACK_SSH_NDK_DIR:-/home/yohanes/Android/Sdk/ndk/26.1.10909125}
[[ -d "$SOURCE/src/terminal" ]] || { echo "Missing Ghostty source: $SOURCE" >&2; exit 1; }
cd "$SOURCE"
compat="$PROJECT_DIR/patches/ghostty-osc99.patch"
if patch --dry-run --forward -p1 < "$compat" >/dev/null 2>&1; then
    patch --forward -p1 < "$compat"
elif ! patch --dry-run --reverse -p1 < "$compat" >/dev/null 2>&1; then
    echo "Cannot apply OSC 99 patch to this Ghostty source snapshot" >&2
    exit 1
fi
ANDROID_NDK_HOME="$NDK_DIR" "$ZIG" build -Demit-lib-vt=true \
    -Dtarget=aarch64-linux-android -Doptimize=ReleaseFast \
    --prefix "$PROJECT_DIR/build/ghostty-android"
install -m 0644 "$PROJECT_DIR/build/ghostty-android/lib/libghostty-vt.a" \
    "$PROJECT_DIR/native/ghostty/lib/arm64-v8a/libghostty-vt.a"
# The C API evolves with the archive; install the entire matching header set.
cp -R include/ghostty/. "$PROJECT_DIR/native/ghostty/include/ghostty/"
