#!/usr/bin/env bash
set -euo pipefail
PROJECT=$(cd "$(dirname "$0")/.." >/dev/null && pwd)
WORK="$PROJECT/build/uml"
SRC="$WORK/passt"
COMMIT=6aa4f88bd9846411edff3ed70460475b538addca
ZIG=${UML_ZIG:-$PROJECT/build/toolchains/zig-x86_64-linux-0.17.0/zig}
NDK=${UML_NDK:-$HOME/Android/Sdk/ndk/27.2.12479018}
[[ -x "$ZIG" ]] || { echo 'Set UML_ZIG to a Zig 0.17.0 executable' >&2; exit 1; }
[[ $("$ZIG" version) == 0.17.0 ]] || { echo 'Zig 0.17.0 required' >&2; exit 1; }
if [[ ! -d "$SRC/.git" ]]; then
    mkdir -p "$WORK"
    git init "$SRC"
    git -C "$SRC" remote add origin https://passt.top/passt
    git -C "$SRC" fetch --depth 1 origin "$COMMIT"
    git -C "$SRC" checkout --detach FETCH_HEAD
fi
[[ $(git -C "$SRC" rev-parse HEAD) == "$COMMIT" ]] || {
    echo "passt must be pinned to $COMMIT" >&2; exit 1;
}
PATCH="$PROJECT/patches/passt-uml-android.patch"
if git -C "$SRC" apply --check "$PATCH" 2>/dev/null; then
    git -C "$SRC" apply "$PATCH"
else
    git -C "$SRC" apply --reverse --check "$PATCH"
fi
# musl avoids Bionic's incompatible Linux networking header definitions and
# glibc's startup syscalls that Android's app seccomp filter rejects.
make -C "$SRC" CC="$ZIG cc -target aarch64-linux-musl" clean
make -C "$SRC" CC="$ZIG cc -target aarch64-linux-musl" \
    LDFLAGS='-static -Wl,-z,max-page-size=16384' passt
mkdir -p "$WORK/artifacts"
cp "$SRC/passt" "$WORK/artifacts/passt"
PATCH="$PROJECT/patches/uml-network-cleanup.patch"
if git -C "$WORK/linux" apply --check "$PATCH" 2>/dev/null; then
    git -C "$WORK/linux" apply "$PATCH"
else
    git -C "$WORK/linux" apply --reverse --check "$PATCH"
fi
"$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android34-clang" \
    -O2 -static -Wl,-z,max-page-size=16384 \
    "$WORK/linux/tools/um-arm64/harness/umnet.c" -o "$WORK/artifacts/umnet"
file "$WORK/artifacts/passt" "$WORK/artifacts/umnet"
sha256sum "$WORK/artifacts/passt" "$WORK/artifacts/umnet"
