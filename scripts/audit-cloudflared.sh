#!/usr/bin/env bash
set -euo pipefail
PROJECT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." >/dev/null && pwd)
export GOTOOLCHAIN=go1.27.3
export GOBIN="$PROJECT_DIR/build/tools"
NDK_DIR=${TINYHACK_SSH_NDK_DIR:-/home/yohanes/Android/Sdk/ndk/26.1.10909125}
go install golang.org/x/vuln/cmd/govulncheck@v1.9.0
cd "$PROJECT_DIR/build/cloudflared-2026.10.7"
export GOOS=android GOARCH=arm64 CGO_ENABLED=1 GOFLAGS=-mod=vendor
export CC="$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android34-clang"
# Source mode honors this target's import graph. Stripped-binary mode can
# conservatively flag unused packages in required modules (notably OpenPGP).
imports=$(go list -deps ./cmd/cloudflared)
if grep -q '^golang.org/x/crypto/openpgp' <<<"$imports"; then
    echo "Unmaintained OpenPGP dependency has entered the Android build" >&2
    exit 1
fi
"$GOBIN/govulncheck" ./cmd/cloudflared
