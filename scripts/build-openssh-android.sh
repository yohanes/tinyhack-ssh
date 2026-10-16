#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." >/dev/null && pwd)
NDK_DIR=${ANDROID_NDK_HOME:-/home/yohanes/Android/Sdk/ndk/26.1.10909125}
OPENSSH_DIR=${OPENSSH_DIR:-"$PROJECT_DIR/build-openssh/openssh-10.6p1"}
OPENSSL_PREFIX="$PROJECT_DIR/build-openssl/install"
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

TINYHACK_SSH_NDK_DIR="$NDK_DIR" "$PROJECT_DIR/scripts/build-openssl-android.sh"

export PATH="$TOOLCHAIN:$PATH"
export ANDROID_NDK_ROOT="$NDK_DIR"

if [[ "$OPENSSH_DIR" == "$PROJECT_DIR/build-openssh/openssh-10.6p1" ]]; then
    mkdir -p "$PROJECT_DIR/build-openssh"
    archive="$PROJECT_DIR/build-openssh/openssh-10.6p1.tar.gz"
    if [[ ! -f "$archive" ]]; then
        curl -fL https://cdn.openbsd.org/pub/OpenBSD/OpenSSH/portable/openssh-10.6p1.tar.gz -o "$archive"
    fi
    printf '%s  %s\n' a9dc9565dffe8640f64d863cd29a32bc4a3dbdec0566a7fc44c5d6ee767d5f39 "$archive" | sha256sum -c -
    [[ -d "$OPENSSH_DIR" ]] || tar -xzf "$archive" -C "$PROJECT_DIR/build-openssh"
fi
[[ -d "$OPENSSH_DIR" ]] || { echo "Missing OpenSSH source: $OPENSSH_DIR" >&2; exit 1; }
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
