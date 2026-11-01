#!/usr/bin/env bash
set -euo pipefail
PROJECT=$(cd "$(dirname "$0")/.." >/dev/null && pwd)
WORK="$PROJECT/build/uml"
PACKAGE=com.tinyhack.ssh.uml
adb install -r "$WORK/tinyhack-uml-debug.apk"
adb shell run-as "$PACKAGE" mkdir -p files/home/uml
# Seed only a new guest disk; don't overwrite a guest the user has modified.
if ! adb shell run-as "$PACKAGE" test -f files/home/uml/rootfs.ext4; then
    adb shell run-as "$PACKAGE" sh -c "'cat >files/home/uml/rootfs.ext4'" < "$WORK/rootfs.ext4"
fi
adb shell run-as "$PACKAGE" sh -c "'cat >files/home/uml/run.sh'" < "$PROJECT/experiments/uml/run.sh"
adb shell am start -n "$PACKAGE/com.tinyhack.ssh.MainActivity"
echo 'In a Local Shell, run: bash ~/uml/run.sh'
