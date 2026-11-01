#!/usr/bin/env bash
# Run under fakeroot so mke2fs receives the original rootfs ownership metadata.
set -euo pipefail
BASE=$1 BUSYBOX=$2 OVERLAY=$3 IMAGE=$4 SIZE=$5
STAGE=$(mktemp -d "$(dirname "$IMAGE")/stage.XXXXXX")
trap 'rm -rf "$STAGE"' EXIT
tar --numeric-owner -xf "$BASE" -C "$STAGE"
cp -a "$OVERLAY/." "$STAGE/"
mkdir -p "$STAGE/usr/local/libexec" "$STAGE/dev" "$STAGE/proc" "$STAGE/sys"
# Docker export may include placeholder files; create real nodes below once.
rm -f "$STAGE/dev/console" "$STAGE/dev/null"
cp "$BUSYBOX" "$STAGE/usr/local/libexec/busybox"
chmod 755 "$STAGE/init" "$STAGE/usr/local/sbin/uml-boot" "$STAGE/usr/local/libexec/busybox" "$STAGE/usr/local/bin/vi"
chmod 755 "$STAGE/usr/local/sbin/uml-login" "$STAGE/usr/local/sbin/uml-create"
ln -s ../libexec/busybox "$STAGE/usr/local/sbin/poweroff"
ln -s ../libexec/busybox "$STAGE/usr/local/sbin/reboot"
printf 'nameserver 1.1.1.1\n' > "$STAGE/etc/resolv.conf"
printf 'debian-uml\n' > "$STAGE/etc/hostname"
printf '127.0.0.1 localhost debian-uml\n::1 localhost\n' > "$STAGE/etc/hosts"
chown -R 0:0 "$STAGE/usr/local" "$STAGE/init" "$STAGE/etc/inittab" \
    "$STAGE/etc/profile.d/uml.sh" "$STAGE/etc/resolv.conf" "$STAGE/etc/hostname" "$STAGE/etc/hosts"
truncate -s "$SIZE" "$IMAGE"
mkfs.ext4 -q -F -b 4096 -m 1 -L tinyhack-debian -d "$STAGE" "$IMAGE"
printf 'cd /dev\nmknod console c 5 1\nmknod null c 1 3\nset_inode_field console mode 020600\nset_inode_field null mode 020666\n' | debugfs -w "$IMAGE" >/dev/null 2>&1
