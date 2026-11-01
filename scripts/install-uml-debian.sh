#!/usr/bin/env bash
set -euo pipefail
PROJECT=$(cd "$(dirname "$0")/.." >/dev/null && pwd)
PACKAGE=com.tinyhack.ssh.uml
adb shell run-as "$PACKAGE" mkdir -p files/home/uml
if adb shell run-as "$PACKAGE" test -e files/home/uml/debian.ext4; then
    echo 'Keeping existing Debian disk (including installed packages).'
else
    gzip -t "$PROJECT/build/uml/debian/debian.ext4.gz"
    # Transfer the compressed sparse image and expand in app-private storage.
    adb shell run-as "$PACKAGE" sh -c "'gzip -dc >files/home/uml/debian.ext4.partial'" \
        < "$PROJECT/build/uml/debian/debian.ext4.gz"
    adb shell run-as "$PACKAGE" mv files/home/uml/debian.ext4.partial files/home/uml/debian.ext4
fi
adb shell run-as "$PACKAGE" sh -c "'cat >files/home/uml/run.sh'" < "$PROJECT/experiments/uml/run.sh"
adb shell run-as "$PACKAGE" sh -c "'cat >files/home/uml/run-debian.sh'" < "$PROJECT/experiments/uml/debian/run-debian.sh"
adb shell am start -n "$PACKAGE/com.tinyhack.ssh.MainActivity"
echo 'In Local Shell: bash ~/uml/run-debian.sh'
