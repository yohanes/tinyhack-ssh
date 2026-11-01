#!/usr/bin/env bash
set -euo pipefail
PROJECT=$(cd "$(dirname "$0")/.." >/dev/null && pwd)
ADB=${ADB:-adb}
PACKAGE=com.tinyhack.ssh.uml
"$ADB" shell run-as "$PACKAGE" mkdir -p files/home/uml/templates files/usr/libexec
"$ADB" shell run-as "$PACKAGE" sh -c "'cat >files/home/uml/templates/debian.ext4.gz.partial'" < "$PROJECT/build/uml/env-template/debian.ext4.gz"
"$ADB" shell run-as "$PACKAGE" gzip -t files/home/uml/templates/debian.ext4.gz.partial
"$ADB" shell run-as "$PACKAGE" mv files/home/uml/templates/debian.ext4.gz.partial files/home/uml/templates/debian.ext4.gz
"$ADB" shell run-as "$PACKAGE" sh -c "'cat >files/usr/libexec/uml-env'" < "$PROJECT/app/src/main/assets/usr/libexec/uml-env"
echo 'Open a new Local Shell, then: uml-env create -t debian -n devenv -d 1024M -u user'
