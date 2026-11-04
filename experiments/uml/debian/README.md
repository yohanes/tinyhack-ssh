# Minimal extensible Debian for ARM64 UML

This is a writable Debian 13 (trixie) ARM64 filesystem for the existing
[UML experiment](../README.md). It uses a 4 GB ext4 disk and 512 MB guest RAM
by default. The clean seed compresses to approximately 49 MB.

The base is the official `debian:trixie-slim` ARM64 image, pinned by digest in
`scripts/build-uml-debian.sh`. Docker is used only to download and export the
filesystem; the phone boots it with our UML kernel, not Docker or PRoot.
Original Debian package metadata, archive signing keys, APT, and dpkg remain
intact. Stable, updates, and security repositories are configured normally.

## Build and install

First build/install the UML experimental APK and networking as described in
the parent README. Then, on the host with Docker and fakeroot installed:

```sh
scripts/build-uml-debian.sh
scripts/install-uml-debian.sh
```

The installer keeps an existing `debian.ext4`, preserving installed packages
and files. It never replaces the original small `rootfs.ext4` experiment.
In the experimental app's Local Shell:

```sh
bash ~/uml/run-debian.sh
```

Inside Debian:

```sh
apt-get update
apt-get install vim-tiny ca-certificates jq python3
vi /root/my-config.conf
poweroff
```

Use `poweroff` without `-f`: PID 1 runs shutdown hooks, syncs, and unmounts
filesystems. Reopening `run-debian.sh` uses the same persistent disk. APT's
usual Debian ARM64 repository selection and dependency resolution apply;
there is no package allowlist imposed by this experiment.

## Included and optional components

- Debian's minimal base, bash, APT/dpkg, and repository signing keys.
- BusyBox init as PID 1, a login shell on `tty0`, and normal child reaping.
- `/proc`, `/sys`, `/dev`, `/dev/pts`, `/dev/shm`, `/run`, and `/tmp` mounts.
- Static IPv4 configuration for the existing passt-backed `vec0` interface.
- Basic BusyBox vi from the first boot. `/usr/local/bin/vi` automatically
  prefers Debian's `/usr/bin/vi` when vim-tiny or vim is installed.
- A standard Docker-base `policy-rc.d` prevents packages from automatically
  starting services during installation. Services may be started manually.
  This minimal image does not boot systemd; packages that require particular
  services, kernel features, hardware, or kernel modules need additional setup.
- The slim base omits many manpages and locales. They can be restored or
  installed using Debian's normal mechanisms; missing manpage-alternative
  warnings do not prevent package installation.

APT initially uses Debian's HTTP mirrors with signed repository metadata and
package hash verification. For HTTPS mirrors, install `ca-certificates`, then
edit `/etc/apt/sources.list.d/debian.sources` with vi to use HTTPS. Do not disable
APT signature verification. The image names `1.1.1.1` in `/etc/resolv.conf`;
the app's boot shim replaces that default with `10.0.2.3`, the network
launcher's forwarder to Android's DNS, and keeps any nameserver you set.

## Storage and artifacts

- `build/uml/debian/debian.ext4.gz`: clean compressed seed to install.
- `build/uml/debian/debian.ext4`: sparse 4 GB seed disk.
- On-device disk: app-private `files/home/uml/debian.ext4`.
- On-device transcript: `files/home/uml/debian-console.log`.

Choose a larger *new seed* with `UML_DEBIAN_SIZE=8G scripts/build-uml-debian.sh`.
Choose more RAM with `UML_MEMORY=1G bash ~/uml/run-debian.sh`. These do not erase
or resize an installed disk. To enlarge an existing disk, shut down the guest,
back it up, expand the backing file, and use e2fsprogs `resize2fs`; do not
replace it with a fresh seed if you want to retain installed packages.

All runtime packages retain their Debian copyright/license metadata in
`/usr/share/doc`. BusyBox 1.38.0 source/checksum and build configuration are
provided by the existing rootfs build script; it is GPL-2.0. Debian package
sources can be obtained by enabling `deb-src` and using `apt-get source`.

This inherits the experimental UML kernel's limitations: ARM64 only, no
hardware virtualization, file-backed guest RAM, and currently IPv4 networking.

## Device verification

Tested on Moto G45 / Android 15: the final clean seed boots, updates APT,
installs and runs `hello`, and saves configuration files with built-in vi.
Installing vim-tiny, ca-certificates, jq, and Python also succeeded; installed
packages and edited files survived a guest reboot. See the
[validation report and logs](results/validation.md).
