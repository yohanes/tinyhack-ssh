#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." >/dev/null && pwd)
NDK_DIR=${TINYHACK_SSH_NDK_DIR:-${ANDROID_NDK_HOME:-/home/yohanes/Android/Sdk/ndk/26.1.10909125}}
OPENSSL_VERSION=3.5.9
OPENSSL_SHA256=603f5602e2eef00d77fbd429d34dcd5822bb301757a1bc9cdb24c670f1eb859a
OPENSSL_BUILD="$PROJECT_DIR/build-openssl"
OPENSSL_SOURCE="$OPENSSL_BUILD/openssl-$OPENSSL_VERSION"
OPENSSL_PREFIX="$OPENSSL_BUILD/install"
TOOLCHAIN="$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64/bin"
JOBS=${JOBS:-$(nproc)}

[[ -x "$TOOLCHAIN/aarch64-linux-android34-clang" ]] || {
    echo "NDK $NDK_DIR has no aarch64-linux-android34-clang (too old?). Set ANDROID_NDK_HOME to NDK 26+." >&2
    exit 1
}

mkdir -p "$OPENSSL_BUILD"
archive="$OPENSSL_BUILD/openssl-$OPENSSL_VERSION.tar.gz"
if [[ ! -f "$archive" ]]; then
    curl -fL "https://github.com/openssl/openssl/releases/download/openssl-$OPENSSL_VERSION/openssl-$OPENSSL_VERSION.tar.gz" \
        -o "$archive"
fi
printf '%s  %s\n' "$OPENSSL_SHA256" "$archive" | sha256sum -c -
[[ -d "$OPENSSL_SOURCE" ]] || tar -xzf "$archive" -C "$OPENSSL_BUILD"

export PATH="$TOOLCHAIN:$PATH"
export ANDROID_NDK_ROOT="$NDK_DIR"
(
    cd "$OPENSSL_SOURCE"
    ./Configure android-arm64 -D__ANDROID_API__=34 no-shared no-tests no-apps no-docs \
        no-ui-console --prefix="$OPENSSL_PREFIX"
    make -j"$JOBS" build_libs
    make install_dev
)

echo "OpenSSL $OPENSSL_VERSION installed in $OPENSSL_PREFIX"
