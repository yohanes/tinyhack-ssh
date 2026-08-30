#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." >/dev/null && pwd)
NDK_DIR=${TINYHACK_SSH_NDK_DIR:-/home/yohanes/Android/Sdk/ndk/26.1.10909125}
TOOLCHAIN="$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64/bin"
CC="$TOOLCHAIN/aarch64-linux-android34-clang"
STRIP="$TOOLCHAIN/llvm-strip"
OUT="$PROJECT_DIR/app/src/main/jniLibs/arm64-v8a"
JOBS=${JOBS:-$(nproc)}
PAGE_LDFLAG='-Wl,-z,max-page-size=16384'

for required in "$CC" "$STRIP"; do
    [[ -x "$required" ]] || { echo "Missing tool: $required" >&2; exit 1; }
done
mkdir -p "$OUT"

echo "Re-linking Bash for 16 KiB pages"
rm -f "$PROJECT_DIR/build-bash/bash-5.2.37/bash"
make -C "$PROJECT_DIR/build-bash/bash-5.2.37" -j"$JOBS" \
    ADDON_LDFLAGS="$PAGE_LDFLAG"
"$STRIP" "$PROJECT_DIR/build-bash/bash-5.2.37/bash" -o "$OUT/libbash.so"

echo "Re-linking BusyBox for 16 KiB pages"
BUSYBOX_DIR="$PROJECT_DIR/build-busybox/busybox-1.38.0"
# These applets depend on Linux console, init, or utmp APIs that Android's
# public Bionic API intentionally does not provide. They cannot function in an
# untrusted app process, so keep the Android build reproducible by disabling
# them before compiling instead of relying on stale objects.
for option in FEATURE_UTMP FEATURE_SUID FEATURE_SUID_CONFIG \
    FEATURE_SUID_CONFIG_QUIET HOSTID CHVT DEALLOCVT DUMPKMAP FGCONSOLE KBD_MODE \
    LOADFONT SETFONT LOADKMAP OPENVT SHOWKEY BOOTCHARTD HALT INIT SU SULOGIN \
    ADJTIMEX ETHER_WAKE IFCONFIG CONSPY SYSLOGD LOGGER HUSH SHELL_HUSH \
    FSCK_MINIX MKFS_MINIX ARP IFENSLAVE IPCRM IPCS MOUNT SWAPON SWAPOFF \
    FEATURE_NSLOOKUP_BIG NSLOOKUP; do
    sed -i -E \
        -e "s/^CONFIG_${option}=y$/# CONFIG_${option} is not set/" \
        -e "s/^CONFIG_${option}=m$/# CONFIG_${option} is not set/" \
        "$BUSYBOX_DIR/.config"
done
sed -i -E 's/^CONFIG_EXTRA_LDLIBS=.*/CONFIG_EXTRA_LDLIBS="m"/' \
    "$BUSYBOX_DIR/.config"
set +o pipefail
yes '' | make -C "$BUSYBOX_DIR" oldconfig >/dev/null
oldconfig_status=${PIPESTATUS[1]}
set -o pipefail
(( oldconfig_status == 0 )) || exit "$oldconfig_status"
make -C "$PROJECT_DIR/build-busybox/busybox-1.38.0" -j"$JOBS" \
    CC="$CC" HOSTCC=cc STRIP="$STRIP" \
    CFLAGS='-O2 -fPIE -D__ANDROID__ -D_GNU_SOURCE' \
    EXTRA_CFLAGS='-O2 -fPIE -DANDROID -D__ANDROID__ -D_GNU_SOURCE -Wno-error' \
    LDFLAGS='-pie -Wl,-z,relro -Wl,-z,now' \
    EXTRA_LDFLAGS="$PAGE_LDFLAG"
"$STRIP" "$PROJECT_DIR/build-busybox/busybox-1.38.0/busybox" -o "$OUT/libbusybox.so"

echo "Re-linking rsync for 16 KiB pages"
rm -f "$PROJECT_DIR/build-rsync/rsync-3.5.0/rsync"
make -C "$PROJECT_DIR/build-rsync/rsync-3.5.0" -j"$JOBS" \
    CC="$CC" LDFLAGS="-pie $PAGE_LDFLAG"
"$STRIP" "$PROJECT_DIR/build-rsync/rsync-3.5.0/rsync" -o "$OUT/librsync.so"

echo "Rebuilding OpenSSH and Mosh for 16 KiB pages"
ANDROID_NDK_HOME="$NDK_DIR" "$PROJECT_DIR/scripts/build-openssh-android.sh"
MOSH_NDK_DIR="$NDK_DIR" "$PROJECT_DIR/scripts/build-mosh-android.sh"

echo "Verifying bundled ELF LOAD alignment"
failed=0
for elf in "$OUT"/*.so; do
    alignments=$(readelf -lW "$elf" | awk '/ LOAD / {print $NF}' | sort -u)
    if [[ -z "$alignments" ]] || grep -Eq '0x(1000|2000)' <<<"$alignments"; then
        echo "FAIL $(basename "$elf"): ${alignments:-no LOAD segments}" >&2
        failed=1
    else
        echo "OK   $(basename "$elf"): $alignments"
    fi
done
exit "$failed"
