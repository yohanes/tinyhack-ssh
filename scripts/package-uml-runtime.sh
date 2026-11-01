#!/usr/bin/env bash
set -euo pipefail
PROJECT=$(cd "$(dirname "$0")/.." >/dev/null && pwd)
NDK=${UML_NDK:-$HOME/Android/Sdk/ndk/27.2.12479018}
STRIP="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
DEST="$PROJECT/app/src/main/jniLibs/arm64-v8a"
"$PROJECT/scripts/build-uml-env-tools.sh"
for pair in linux-bionic:libuml.so stub_exe_bionic:libuml-stub.so passt:libuml-passt.so umnet:libuml-net.so; do
    "$STRIP" --strip-debug "$PROJECT/build/uml/artifacts/${pair%%:*}" -o "$DEST/${pair#*:}"
done
cp "$PROJECT/build/uml/jniLibs/arm64-v8a/libuml-sparse-image.so" "$DEST/"
