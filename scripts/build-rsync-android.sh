#!/usr/bin/env bash
set -euo pipefail
PROJECT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." >/dev/null && pwd)
NDK_DIR=${TINYHACK_SSH_NDK_DIR:-/home/yohanes/Android/Sdk/ndk/26.1.10909125}
TOOLCHAIN="$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64/bin"
BUILD="$PROJECT_DIR/build-rsync"
SOURCE="$BUILD/rsync-3.5.1"
JOBS=${JOBS:-$(nproc)}
mkdir -p "$BUILD"
if [[ ! -f "$BUILD/rsync-3.5.1.tar.gz" ]]; then
    curl -fL https://download.samba.org/pub/rsync/rsync-3.5.1.tar.gz -o "$BUILD/rsync-3.5.1.tar.gz"
fi
printf '%s  %s\n' c55f9c9dc10fb8bec397b399a0fdded53cc9a2d8e30891bb0d63724d25c37bef "$BUILD/rsync-3.5.1.tar.gz" | sha256sum -c -
[[ -d "$SOURCE" ]] || tar -xzf "$BUILD/rsync-3.5.1.tar.gz" -C "$BUILD"
cd "$SOURCE"
compat="$PROJECT_DIR/patches/rsync-android-data-dir.patch"
if patch --dry-run --forward -p1 < "$compat" >/dev/null 2>&1; then
    patch --forward -p1 < "$compat"
elif ! patch --dry-run --reverse -p1 < "$compat" >/dev/null 2>&1; then
    echo "Cannot apply rsync Android compatibility patch" >&2
    exit 1
fi
export PATH="$TOOLCHAIN:$PATH"
CC=aarch64-linux-android34-clang AR=llvm-ar RANLIB=llvm-ranlib \
CFLAGS='-O2 -fPIE' LDFLAGS='-pie -Wl,-z,max-page-size=16384' \
./configure --host=aarch64-linux-android --prefix=/data/data/com.tinyhack.ssh/files/usr \
    --disable-acl-support --disable-xattr-support --disable-locale --with-included-popt \
    --disable-openssl --disable-xxhash --disable-zstd --disable-lz4 --disable-md2man --disable-iconv --disable-idn
make -j"$JOBS"
mkdir -p "$PROJECT_DIR/app/src/main/jniLibs/arm64-v8a"
llvm-strip rsync -o "$PROJECT_DIR/app/src/main/jniLibs/arm64-v8a/librsync.so"
