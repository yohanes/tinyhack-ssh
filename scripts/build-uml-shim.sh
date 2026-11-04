#!/usr/bin/env bash
# Build the UML boot shim: an initramfs holding a static BusyBox and
# experiments/uml/shim/init. uml-env passes it as initrd= so the shim runs
# before each environment disk's own /init (see that script for what it does).
# BusyBox is built separately from the guest image's copy so that changing the
# shim's applets never changes the bundled templates.
set -euo pipefail
PROJECT=$(cd "$(dirname "$0")/.." >/dev/null && pwd)
WORK="$PROJECT/build/uml"
VERSION=1.38.0
SHA=34f9ea6ff8636f2c9241153b9114eefa9e65674a45318ae1ef95bb5f31c53bb2
GEN_INIT_CPIO="$WORK/kernel-build/usr/gen_init_cpio"
DEST="$PROJECT/app/src/main/assets/usr/share/uml/shim.cpio"
[[ -x "$GEN_INIT_CPIO" ]] || { echo "Build the kernel first: scripts/build-uml-kernel.sh" >&2; exit 1; }
mkdir -p "$WORK"
TARBALL="$WORK/busybox-$VERSION.tar.bz2"
if [[ ! -f "$TARBALL" ]]; then
    curl -fL --retry 3 "https://busybox.net/downloads/busybox-$VERSION.tar.bz2" -o "$TARBALL"
fi
echo "$SHA  $TARBALL" | sha256sum -c -
SRC="$WORK/busybox-$VERSION"
[[ -d "$SRC" ]] || tar -xjf "$TARBALL" -C "$WORK"
OUT="$WORK/busybox-shim-build"
mkdir -p "$OUT"
make -C "$SRC" O="$OUT" CROSS_COMPILE=aarch64-linux-gnu- allnoconfig >/dev/null
for opt in STATIC ASH SH_IS_ASH ASH_ECHO ASH_TEST ECHO TEST MKDIR MOUNT UMOUNT \
    FEATURE_MOUNT_FLAGS SWITCH_ROOT HALT POWEROFF; do
    sed -i "s/^# CONFIG_${opt} is not set/CONFIG_${opt}=y/" "$OUT/.config"
done
(set +o pipefail; yes '' | make -C "$SRC" O="$OUT" CROSS_COMPILE=aarch64-linux-gnu- oldconfig >/dev/null)
make -C "$SRC" O="$OUT" CROSS_COMPILE=aarch64-linux-gnu- -j"${JOBS:-8}" >/dev/null
for opt in ASH MOUNT UMOUNT SWITCH_ROOT POWEROFF FEATURE_MOUNT_FLAGS; do
    grep -qx "CONFIG_${opt}=y" "$OUT/.config" || { echo "BusyBox option $opt missing" >&2; exit 1; }
done
SPEC="$WORK/shim.list"
cat > "$SPEC" <<EOF
dir /bin 0755 0 0
dir /dev 0755 0 0
dir /proc 0755 0 0
dir /newroot 0755 0 0
nod /dev/console 0600 0 0 c 5 1
nod /dev/null 0666 0 0 c 1 3
file /bin/busybox $OUT/busybox 0755 0 0
slink /bin/sh busybox 0777 0 0
slink /bin/echo busybox 0777 0 0
slink /bin/mkdir busybox 0777 0 0
slink /bin/mount busybox 0777 0 0
slink /bin/umount busybox 0777 0 0
slink /bin/switch_root busybox 0777 0 0
slink /bin/poweroff busybox 0777 0 0
slink /bin/[ busybox 0777 0 0
file /init $PROJECT/experiments/uml/shim/init 0755 0 0
EOF
# A fixed timestamp keeps the archive reproducible.
mkdir -p "$(dirname "$DEST")"
"$GEN_INIT_CPIO" -t 0 "$SPEC" > "$DEST"
file "$OUT/busybox"
sha256sum "$DEST"
