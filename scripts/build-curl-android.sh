#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." >/dev/null && pwd)
NDK_DIR=${TINYHACK_SSH_NDK_DIR:-${ANDROID_NDK_HOME:-/home/yohanes/Android/Sdk/ndk/26.1.10909125}}
CURL_VERSION=8.23.0
CURL_SHA256=8dadd7d2536a314f982ad05a651b713f426bf7d02cc2c76cffb9796f2218e6c5
BUILD="$PROJECT_DIR/build-curl"
SOURCE="$BUILD/curl-$CURL_VERSION"
ARCHIVE="$BUILD/curl-$CURL_VERSION.tar.xz"
OPENSSL_PREFIX="$PROJECT_DIR/build-openssl/install"
TOOLCHAIN="$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64/bin"
OUT="$PROJECT_DIR/app/src/main/jniLibs/arm64-v8a/libcurl.so"
JOBS=${JOBS:-$(nproc)}

for required in aarch64-linux-android34-clang llvm-ar llvm-ranlib llvm-strip; do
    [[ -x "$TOOLCHAIN/$required" ]] || {
        echo "Missing Android NDK tool: $TOOLCHAIN/$required" >&2
        exit 1
    }
done

mkdir -p "$BUILD" "$(dirname "$OUT")"
if [[ ! -f "$ARCHIVE" ]]; then
    curl -fL "https://curl.se/download/curl-$CURL_VERSION.tar.xz" -o "$ARCHIVE"
fi
printf '%s  %s\n' "$CURL_SHA256" "$ARCHIVE" | sha256sum -c -
[[ -d "$SOURCE" ]] || tar -xJf "$ARCHIVE" -C "$BUILD"

if [[ ! -f "$OPENSSL_PREFIX/lib/libssl.a" || ! -f "$OPENSSL_PREFIX/lib/libcrypto.a" ]]; then
    TINYHACK_SSH_NDK_DIR="$NDK_DIR" "$PROJECT_DIR/scripts/build-openssl-android.sh"
fi

export PATH="$TOOLCHAIN:$PATH"
(
    cd "$SOURCE"
    make distclean >/dev/null 2>&1 || true
    CC=aarch64-linux-android34-clang \
    AR=llvm-ar \
    RANLIB=llvm-ranlib \
    STRIP=llvm-strip \
    CFLAGS='-O2 -fPIE' \
    CPPFLAGS="-D__ANDROID__ -D_GNU_SOURCE -I$OPENSSL_PREFIX/include" \
    LDFLAGS="-pie -Wl,-z,relro -Wl,-z,now -Wl,-z,max-page-size=16384 -L$OPENSSL_PREFIX/lib" \
    PKG_CONFIG_LIBDIR="$OPENSSL_PREFIX/lib/pkgconfig" \
    ./configure \
        --host=aarch64-linux-android \
        --prefix=/data/data/com.tinyhack.ssh/files/usr \
        --disable-shared \
        --enable-static \
        --with-openssl="$OPENSSL_PREFIX" \
        --with-ca-bundle=/data/data/com.tinyhack.ssh/files/usr/etc/ssl/certs/ca-certificates.crt \
        --with-zlib \
        --without-brotli \
        --without-zstd \
        --without-libpsl \
        --without-libidn2 \
        --without-nghttp2 \
        --without-nghttp3 \
        --without-libssh2 \
        --without-libgsasl \
        --disable-ldap \
        --disable-ldaps
    make -C lib -j"$JOBS"
    make -C src -j"$JOBS" curl
    llvm-strip src/curl -o "$OUT"
)

echo "curl $CURL_VERSION installed as $OUT"
