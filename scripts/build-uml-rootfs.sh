#!/usr/bin/env bash
# Minimal Linux (not Android/Bionic) ARM64 userspace for the UML experiment.
set -euo pipefail
PROJECT=$(cd "$(dirname "$0")/.." >/dev/null && pwd)
WORK="$PROJECT/build/uml"
VERSION=1.38.0
SHA=34f9ea6ff8636f2c9241153b9114eefa9e65674a45318ae1ef95bb5f31c53bb2
mkdir -p "$WORK"
TARBALL="$WORK/busybox-$VERSION.tar.bz2"
if [[ ! -f "$TARBALL" ]]; then
    curl -fL --retry 3 "https://busybox.net/downloads/busybox-$VERSION.tar.bz2" -o "$TARBALL"
fi
echo "$SHA  $TARBALL" | sha256sum -c -
SRC="$WORK/busybox-$VERSION"
[[ -d "$SRC" ]] || tar -xjf "$TARBALL" -C "$WORK"
OUT="$WORK/busybox-build"
ROOT="$WORK/rootfs"
mkdir -p "$OUT" "$ROOT"/{bin,sbin,etc,proc,sys,dev,tmp,root}
make -C "$SRC" O="$OUT" CROSS_COMPILE=aarch64-linux-gnu- allnoconfig >/dev/null
for opt in STATIC ASH SH_IS_ASH ASH_JOB_CONTROL ASH_ALIAS ASH_MATH_SUPPORT \
    ASH_ECHO ASH_PRINTF ASH_TEST CAT ECHO PRINTF TEST TRUE FALSE UNAME ID \
    LS MKDIR MOUNT UMOUNT SLEEP SYNC HALT POWEROFF REBOOT DMESG PS \
    FEATURE_PS_WIDE FEATURE_MOUNT_FLAGS CAT CP RM TOUCH HEAD GREP WC \
    SETSID CTTYHACK IP FEATURE_IP_ADDRESS FEATURE_IP_LINK FEATURE_IP_ROUTE \
    PING FEATURE_FANCY_PING NSLOOKUP WGET FEATURE_WGET_STATUSBAR \
    FEATURE_WGET_TIMEOUT SHA256SUM INIT FEATURE_USE_INITTAB FEATURE_INIT_SCTTY \
    VI FEATURE_VI_COLON FEATURE_VI_YANKMARK FEATURE_VI_SEARCH FEATURE_VI_USE_SIGNALS \
    FEATURE_VI_DOT_CMD FEATURE_VI_READONLY FEATURE_VI_SETOPTS FEATURE_VI_SET; do
    sed -i "s/^# CONFIG_${opt} is not set/CONFIG_${opt}=y/" "$OUT/.config"
done
(set +o pipefail; yes '' | make -C "$SRC" O="$OUT" CROSS_COMPILE=aarch64-linux-gnu- oldconfig >/dev/null)
make -C "$SRC" O="$OUT" CROSS_COMPILE=aarch64-linux-gnu- -j"${JOBS:-8}" >/dev/null
cp "$OUT/busybox" "$ROOT/bin/busybox"
for applet in sh cat echo printf test true false uname id ls mkdir mount umount \
    sleep sync halt poweroff reboot dmesg ps cp rm touch head grep wc setsid cttyhack \
    ip ping nslookup wget sha256sum; do
    ln -sfn busybox "$ROOT/bin/$applet"
done
cp "$PROJECT/experiments/uml/init" "$ROOT/init"
chmod 755 "$ROOT/init"
truncate -s 64M "$WORK/rootfs.ext4"
mkfs.ext4 -q -F -b 4096 -L tinyhack-uml -d "$ROOT" "$WORK/rootfs.ext4"
# Populate device nodes without sudo; devtmpfs replaces these after init starts.
printf 'cd /dev\nmknod console c 5 1\nmknod null c 1 3\nset_inode_field console mode 020600\nset_inode_field null mode 020666\n' | debugfs -w "$WORK/rootfs.ext4" >/dev/null 2>&1
file "$ROOT/bin/busybox"
sha256sum "$WORK/rootfs.ext4"
