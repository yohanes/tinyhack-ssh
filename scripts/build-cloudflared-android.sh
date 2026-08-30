#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." >/dev/null && pwd)
CLOUDFLARED_DIR="$PROJECT_DIR/cloudflared"
OUT_DIR="$PROJECT_DIR/app/src/main/jniLibs/arm64-v8a"
OUT="$OUT_DIR/libcloudflared.so"
# Use explicit NDK 26.1; ignore stale ANDROID_NDK_HOME pointing to missing r25b
if [[ -d "/home/yohanes/Android/Sdk/ndk/26.1.10909125" ]]; then
    NDK_DIR="/home/yohanes/Android/Sdk/ndk/26.1.10909125"
elif [[ -n "${ANDROID_NDK_HOME:-}" && -d "$ANDROID_NDK_HOME" ]]; then
    NDK_DIR="$ANDROID_NDK_HOME"
else
    NDK_DIR="${ANDROID_NDK_HOME:-/home/yohanes/Android/Sdk/ndk/26.1.10909125}"
fi
TOOLCHAIN="$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64/bin"

apply_patch_once() {
    local patch_file=$1
    if [[ ! -f "$patch_file" ]]; then
        echo "Patch not found: $patch_file" >&2
        return 1
    fi
    # Try to apply forward; if already applied, skip
    if patch --dry-run --forward -p1 < "$patch_file" >/dev/null 2>&1; then
        echo "Applying $(basename "$patch_file")..."
        patch --forward -p1 < "$patch_file"
    elif patch --dry-run --reverse -p1 < "$patch_file" >/dev/null 2>&1; then
        echo "Already applied: $(basename "$patch_file")"
    else
        echo "Patch cannot be applied cleanly: $patch_file" >&2
        exit 1
    fi
}

# Apply Android browser fix if not already present
(
    cd "$CLOUDFLARED_DIR"
    if [[ -f "$PROJECT_DIR/patches/cloudflared-android.patch" ]]; then
        apply_patch_once "$PROJECT_DIR/patches/cloudflared-android.patch"
    fi
)

# Ensure output dir exists
mkdir -p "$OUT_DIR"

# Build flags - use UTC ISO format without spaces to avoid ldflags parsing issues
VERSION=${VERSION:-$(git -C "$CLOUDFLARED_DIR" describe --tags --always --match "[0-9][0-9][0-9][0-9].*.*" 2>/dev/null || echo "DEV")}
DATE=${DATE:-$(date -u -r "$CLOUDFLARED_DIR/RELEASE_NOTES" '+%Y-%m-%dT%H:%M:%SZ' 2>/dev/null || date -u '+%Y-%m-%dT%H:%M:%SZ')}
VERSION_FLAGS="-X main.Version=$VERSION -X main.BuildTime=$DATE"

# Choose GOOS: prefer android (PIE) but fallback to linux static if needed
# GOOS=android produces PIE with correct linker; requires shell.go fix
GOOS=${GOOS:-android}
GOARCH=arm64

echo "Building cloudflared $VERSION for $GOOS/$GOARCH (CGO_ENABLED=1 for Android DNS)..."

(
    cd "$CLOUDFLARED_DIR"
    export CGO_ENABLED=1
    export CC="$TOOLCHAIN/aarch64-linux-android34-clang"
    export CXX="$TOOLCHAIN/aarch64-linux-android34-clang++"
    export CGO_CFLAGS="-O2 -fPIE"
    export CGO_LDFLAGS="-pie -Wl,-z,max-page-size=16384"
    # Use vendor mode as per Makefile
    GOOS=$GOOS GOARCH=$GOARCH go build -mod=vendor -trimpath \
        -ldflags="-s -w $VERSION_FLAGS -extldflags=-Wl,-z,max-page-size=16384" \
        -o "$OUT.tmp" \
        ./cmd/cloudflared
)

# Strip with llvm-strip if available (Go already strips with -s -w, but ensure)
if [[ -x "$TOOLCHAIN/llvm-strip" ]]; then
    "$TOOLCHAIN/llvm-strip" "$OUT.tmp" -o "$OUT" 2>/dev/null || cp "$OUT.tmp" "$OUT"
else
    cp "$OUT.tmp" "$OUT"
fi
rm -f "$OUT.tmp"

# Ensure executable permission and proper ELF
chmod +x "$OUT"
ls -lh "$OUT"
file "$OUT" || true

echo "Done: $OUT"
echo "Size: $(du -h "$OUT" | cut -f1)"

# Verify it can --version (when running on linux host via qemu? skip unless testing on device)
# Just check local file type
if file "$OUT" | grep -q "aarch64"; then
    echo "Build verified: aarch64 ELF"
else
    echo "Warning: output doesn't look like aarch64 ELF"
fi
