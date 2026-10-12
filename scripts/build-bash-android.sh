#!/usr/bin/env bash
set -euo pipefail
PROJECT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." >/dev/null && pwd)
NDK_DIR=${TINYHACK_SSH_NDK_DIR:-/home/yohanes/Android/Sdk/ndk/26.1.10909125}
TOOLCHAIN="$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64/bin"
BUILD="$PROJECT_DIR/build-bash"
SOURCE="$BUILD/bash-5.3.20"
JOBS=${JOBS:-$(nproc)}
mkdir -p "$BUILD"
if [[ ! -f "$BUILD/bash-5.3.tar.gz" ]]; then
    curl -fL https://ftp.gnu.org/gnu/bash/bash-5.3.tar.gz -o "$BUILD/bash-5.3.tar.gz"
fi
printf '%s  %s\n' 0d5cd86965f869a26cf64f4b71be7b96f90a3ba8b3d74e27e8e9d9d5550f31ba "$BUILD/bash-5.3.tar.gz" | sha256sum -c -
if [[ ! -d "$SOURCE" ]]; then
    mkdir -p "$SOURCE"
    tar -xzf "$BUILD/bash-5.3.tar.gz" --strip-components=1 -C "$SOURCE"
fi
cd "$SOURCE"
# Apply GNU's official patch sequence, retaining the exact patches in Git.
for patch_file in "$PROJECT_DIR"/patches/bash-5.3/bash53-*; do
    number=${patch_file##*-}
    level=$(awk '/^#define PATCHLEVEL/ {print $3}' patchlevel.h)
    if (( 10#$number > level )); then
        patch --forward -p0 < "$patch_file"
    fi
done
compat="$PROJECT_DIR/patches/bash-android-compat.patch"
if patch --dry-run --forward -p1 < "$compat" >/dev/null 2>&1; then
    patch --forward -p1 < "$compat"
elif ! patch --dry-run --reverse -p1 < "$compat" >/dev/null 2>&1; then
    echo "Cannot apply Bash Android compatibility patch" >&2
    exit 1
fi
export PATH="$TOOLCHAIN:$PATH"
CC=aarch64-linux-android34-clang AR=llvm-ar RANLIB=llvm-ranlib \
CFLAGS='-O2 -fPIE -D__ANDROID__ -D_GNU_SOURCE' \
LDFLAGS='-pie -Wl,-z,relro -Wl,-z,now -Wl,-z,max-page-size=16384' \
./configure --host=aarch64-linux-android --prefix=/data/data/com.tinyhack.ssh/files/usr \
    --without-bash-malloc --enable-readline --enable-history --enable-bang-history \
    --enable-alias --enable-job-control --enable-progcomp --disable-nls \
    bash_cv_job_control_missing=present bash_cv_sys_named_pipes=present \
    bash_cv_must_reinstall_sighandlers=no bash_cv_func_sigsetjmp=present \
    bash_cv_printf_a_format=yes bash_cv_snprintf_broken=no bash_cv_vsnprintf_broken=no \
    bash_cv_termcap_lib=gnutermcap bash_cv_func_ctype_nonascii=yes \
    bash_cv_wexitstatus_offset=8 bash_cv_pgrp_pipe=no bash_cv_unusable_rtsigs=no \
    bash_cv_dup2_broken=no
make -j"$JOBS" CFLAGS_FOR_BUILD="-O2 -std=gnu17 -DCROSS_COMPILING"
mkdir -p "$PROJECT_DIR/app/src/main/jniLibs/arm64-v8a"
llvm-strip bash -o "$PROJECT_DIR/app/src/main/jniLibs/arm64-v8a/libbash.so"
