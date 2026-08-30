#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." >/dev/null && pwd)
NDK_DIR=${ANDROID_NDK_HOME:-/home/yohanes/Android/Sdk/ndk/26.1.10909125}
OPENSSH_DIR=${OPENSSH_DIR:-"$PROJECT_DIR/build-openssh/openssh-10.5p1"}
OPENSSL_VERSION=3.5.7
OPENSSL_SHA256=a8c0d28a529ca480f9f36cf5792e2cd21984552a3c8e4aa11a24aa31aeac98e8
OPENSSL_BUILD="$PROJECT_DIR/build-openssl"
OPENSSL_SOURCE="$OPENSSL_BUILD/openssl-$OPENSSL_VERSION"
OPENSSL_PREFIX="$OPENSSL_BUILD/install"
TOOLCHAIN="$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64/bin"
JOBS=${JOBS:-$(nproc)}

# Fail fast if $NDK_DIR (e.g. a stale ANDROID_NDK_HOME) predates API 34.
[[ -x "$TOOLCHAIN/aarch64-linux-android34-clang" ]] || {
    echo "NDK $NDK_DIR has no aarch64-linux-android34-clang (too old?). Set ANDROID_NDK_HOME to NDK 26+." >&2
    exit 1
}

apply_patch_once() {
    local patch_file=$1
    if patch --dry-run --forward -p1 < "$patch_file" >/dev/null 2>&1; then
        patch --forward -p1 < "$patch_file"
    elif patch --dry-run --reverse -p1 < "$patch_file" >/dev/null 2>&1; then
        echo "Already applied: $(basename "$patch_file")"
    else
        echo "Patch cannot be applied cleanly: $patch_file" >&2
        exit 1
    fi
}

mkdir -p "$OPENSSL_BUILD"
if [[ ! -f "$OPENSSL_BUILD/openssl-$OPENSSL_VERSION.tar.gz" ]]; then
    curl -fL "https://github.com/openssl/openssl/releases/download/openssl-$OPENSSL_VERSION/openssl-$OPENSSL_VERSION.tar.gz" \
        -o "$OPENSSL_BUILD/openssl-$OPENSSL_VERSION.tar.gz"
fi
printf '%s  %s\n' "$OPENSSL_SHA256" "$OPENSSL_BUILD/openssl-$OPENSSL_VERSION.tar.gz" | sha256sum -c -
if [[ ! -d "$OPENSSL_SOURCE" ]]; then
    tar -xzf "$OPENSSL_BUILD/openssl-$OPENSSL_VERSION.tar.gz" -C "$OPENSSL_BUILD"
fi

export PATH="$TOOLCHAIN:$PATH"
export ANDROID_NDK_ROOT="$NDK_DIR"
(
    cd "$OPENSSL_SOURCE"
    ./Configure android-arm64 -D__ANDROID_API__=34 no-shared no-tests no-apps no-docs \
        no-ui-console --prefix="$OPENSSL_PREFIX"
    make -j"$JOBS" build_libs
    make install_dev
)

if [[ ! -d "$OPENSSH_DIR" ]]; then
    echo "Missing OpenSSH 10.5p1 source directory: $OPENSSH_DIR" >&2
    exit 1
fi
(
    cd "$OPENSSH_DIR"
    apply_patch_once "$PROJECT_DIR/patches/openssh-abstract-auth-socket.patch"
    apply_patch_once "$PROJECT_DIR/patches/openssh-android-compat.patch"
    make distclean >/dev/null 2>&1 || true
    ac_cv_func_bzero=yes \
    CC=aarch64-linux-android34-clang AR=llvm-ar RANLIB=llvm-ranlib STRIP=llvm-strip \
    CFLAGS='-O2 -fPIE -D__ANDROID__ -D_GNU_SOURCE' \
    CPPFLAGS="-I$OPENSSL_PREFIX/include" \
    LDFLAGS="-pie -Wl,-z,max-page-size=16384 -L$OPENSSL_PREFIX/lib" \
    ./configure --host=aarch64-linux-android \
        --prefix=/data/data/com.tinyhack.ssh/files/usr \
        --sysconfdir=/data/data/com.tinyhack.ssh/files/usr/etc/ssh \
        --with-privsep-path=/data/data/com.tinyhack.ssh/files/usr/var/empty \
        --with-privsep-user=nobody --with-sandbox=no \
        --with-ssl-dir="$OPENSSL_PREFIX" --without-zlib-version-check
    make -j"$JOBS" ssh ssh-add ssh-agent ssh-keygen ssh-keyscan scp sftp

    for mapping in \
        ssh:libssh.so ssh-add:libssh-add.so ssh-agent:libssh-agent.so \
        ssh-keygen:libssh-keygen.so ssh-keyscan:libssh-keyscan.so \
        scp:libscp.so sftp:libsftp.so; do
        source_name=${mapping%%:*}
        output_name=${mapping#*:}
        llvm-strip "$source_name" -o "$PROJECT_DIR/app/src/main/jniLibs/arm64-v8a/$output_name"
    done
)
