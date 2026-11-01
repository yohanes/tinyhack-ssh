#!/usr/bin/env bash
set -euo pipefail
PROJECT=$(cd "$(dirname "$0")/.." >/dev/null && pwd)
NDK=${UML_NDK:-$HOME/Android/Sdk/ndk/27.2.12479018}
mkdir -p "$PROJECT/build/uml/jniLibs/arm64-v8a"
"$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android34-clang" \
    -O2 -Wall -Wextra -Werror -static -Wl,-z,max-page-size=16384 \
    "$PROJECT/experiments/uml/tools/sparse-image.c" \
    -o "$PROJECT/build/uml/jniLibs/arm64-v8a/libuml-sparse-image.so"
