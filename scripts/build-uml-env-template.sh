#!/usr/bin/env bash
set -euo pipefail
PROJECT=$(cd "$(dirname "$0")/.." >/dev/null && pwd)
WORK="$PROJECT/build/uml/env-template"
mkdir -p "$WORK"
# Reuse the pinned Debian export and static BusyBox from the Debian builder.
[[ -f "$PROJECT/build/uml/debian/base.tar" && -f "$PROJECT/build/uml/rootfs/bin/busybox" ]] || "$PROJECT/scripts/build-uml-debian.sh"
fakeroot bash "$PROJECT/experiments/uml/debian/make-image.sh" \
    "$PROJECT/build/uml/debian/base.tar" "$PROJECT/build/uml/rootfs/bin/busybox" \
    "$PROJECT/experiments/uml/debian/overlay" "$WORK/debian.ext4" 256M
e2fsck -fn "$WORK/debian.ext4"
gzip -1 -c "$WORK/debian.ext4" > "$WORK/debian.ext4.gz"
(cd "$WORK" && sha256sum debian.ext4.gz > SHA256SUMS)
