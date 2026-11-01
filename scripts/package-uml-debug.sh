#!/usr/bin/env bash
set -euo pipefail
PROJECT=$(cd "$(dirname "$0")/.." >/dev/null && pwd)
WORK="$PROJECT/build/uml"
mkdir -p "$WORK/jniLibs/arm64-v8a"
"$PROJECT/scripts/build-uml-env-tools.sh"
cp "$WORK/artifacts/linux-bionic" "$WORK/jniLibs/arm64-v8a/libuml.so"
cp "$WORK/artifacts/stub_exe_bionic" "$WORK/jniLibs/arm64-v8a/libuml-stub.so"
if [[ -f "$WORK/artifacts/passt" ]]; then
    cp "$WORK/artifacts/passt" "$WORK/jniLibs/arm64-v8a/libuml-passt.so"
    cp "$WORK/artifacts/umnet" "$WORK/jniLibs/arm64-v8a/libuml-net.so"
fi
cd "$PROJECT"
"$PROJECT/scripts/package-uml-runtime.sh"
./gradlew -PumlExperiment assemblePlayDebug
cp app/build/outputs/apk/play/debug/app-play-debug.apk "$WORK/tinyhack-uml-debug.apk"
