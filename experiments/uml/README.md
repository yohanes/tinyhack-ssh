# ARM64 UML exploration

Branch: `explore/uml-arm64`. The pre-existing uncommitted 1.0.4 security
update remains intact on this branch. No experimental binaries are added to
normal release builds.

For named Debian environments, user accounts with sudo, and local-profile
selection, see [uml-env usage](ENVIRONMENTS.md).

## Verified on 2026-11-01

An ARM64 UML kernel boots a small ARM64 Linux filesystem inside TinyHack SSH's
terminal, on an unrooted Moto G45 5G running Android 15/API 35. The host kernel
is `5.4.295-moto-gdaa2fa57da42` with 4096-byte pages. The kernel process runs
under the application's `untrusted_app` SELinux domain, not `adb shell` or
`run-as`. ADB installs and seeds the experiment, then Android starts the app
and its normal PTY/session implementation starts the guest.

Observed guest behavior:

- Linux `7.2.0-rc4-g32e5e10b81d8`, AArch64, 256 MB guest RAM.
- Ext4 root mounted read/write; `/init` runs as guest PID 1 and guest UID 0.
- BusyBox shell executes child processes and accepts commands typed through
  the Android terminal.
- A file written interactively survives shutdown and another boot.
- `sync; mount -o remount,ro /; poweroff -f` remounts the disk read-only and
  shuts down the guest; no UML processes remain on the host.
- The final boot log contains no kernel BUG, panic, or WARNING diagnostics.
- Experimental APK build and `lintPlayDebug` pass.
- Networking works through `vec0` and userspace passt: public DNS lookup,
  three ICMP replies from 1.1.1.1, an internet HTTP connectivity-check request,
  and a 16 MB LAN HTTP download with an exact SHA-256 match.

Evidence is in [results](results/). Full build outputs and screenshots are in
the ignored `build/uml/` directory. `terminal-verified.png` shows the actual
terminal; `terminal-verified.log` records the interactive session.

## Source and toolchains

This uses the experimental [zalexdev ARM64 UML port](https://github.com/zalexdev/linux-um-arm64),
pinned to commit `32e5e10b81d8882bdaf77b71e4106a941840c750`, based on Linux
7.2-rc4. It is not an upstream stable ARM64 UML release. The author states
that they are not maintaining the port. Kernel source is GPL-2.0; preserve its
source and build configuration with any distribution of these binaries.

The kernel and its stub use Android NDK r27c (`27.2.12479018`), API 34, static
Bionic linkage. The guest uses BusyBox 1.38.0, built separately with the host's
`aarch64-linux-gnu-gcc` and static glibc. Guest binaries run under Linux, not
under Android's libc. BusyBox is GPL-2.0; its source archive checksum is pinned
in the rootfs script.

## Reproduce

Prerequisites: NDK r27c, Zig 0.17.0 (set `UML_ZIG` if installed elsewhere),
clang, make, flex, bison, an AArch64 GNU cross compiler
with static libc, e2fsprogs, curl, Git, and the normal Android app build tools.
No sudo, host mounts, or device root are required.

```sh
scripts/build-uml-kernel.sh
scripts/build-uml-rootfs.sh
scripts/build-uml-network.sh
scripts/package-uml-debug.sh
scripts/install-uml-debug.sh
```

The last command installs a separate `com.tinyhack.ssh.uml` debug app and
seeds its private guest disk only when absent. It does not overwrite an
existing guest disk. In that app's Local Shell:

```sh
bash ~/uml/run.sh
```

Inside the guest:

```sh
uname -a
id
echo hello > /root/persist.txt
cat /root/persist.txt
sync; mount -o remount,ro /; poweroff -f
```

Run the launcher again and read `/root/persist.txt` to verify persistence.
Only one guest may use a given writable root disk at a time.

The initial offline experiment's existing disk needs its BusyBox and `/init`
updated for networking; the installer deliberately does not replace disks.
The connected test device has been upgraded while stopped, preserving the
file used for the persistence test. A fresh installation uses the current seed.

Artifacts:

- `build/uml/artifacts/linux-bionic`: compiled UML kernel (unstripped).
- `build/uml/artifacts/stub_exe_bionic`: matching guest execution helper.
- `build/uml/artifacts/passt` and `umnet`: userspace networking programs.
- `build/uml/rootfs.ext4`: 64 MB sparse ext4 seed disk (about 5.5 MB allocated).
- `build/uml/tinyhack-uml-debug.apk`: standalone experimental app build.
- `build/uml/kernel-build/.config`: exact kernel configuration.
- `build/uml/busybox-build/.config`: guest BusyBox configuration.

`-PumlExperiment` adds the debug application-ID suffix and the native
executables. They are packaged as `libuml.so` and `libuml-stub.so` so Android
extracts them into the APK's executable native-library directory. They are
executables, not libraries loaded with `dlopen`. A launcher fix passes the
actual application files directory to JNI instead of hardcoding the release
package's HOME/PATH.

## Networking implementation

The kernel's vector `fd` transport exchanges Ethernet datagrams with `umnet`,
which converts them to passt's length-prefixed stream format. Passt opens
ordinary Android sockets for outbound traffic. No VPN, TUN device, root, or
inbound port forwarding is needed. The guest uses `10.0.2.15/24`, gateway
`10.0.2.2`, and DNS `1.1.1.1`. Set `UML_NETWORK=0` to boot offline.

Passt is pinned to upstream commit `6aa4f88bd9846411edff3ed70460475b538addca`
(2026-10-25) and built with Zig 0.17.0's musl target. Bionic's networking headers
conflict with passt's definitions; static musl also avoids glibc's Android-
blocked startup syscalls. `umnet` uses the pinned kernel source and static Bionic.

`patches/passt-uml-android.patch` is from
[GlassOnTin/uml-transport at 21ef1403](https://github.com/GlassOnTin/uml-transport/tree/21ef1403aa41ac31f1f02e83718bd45ca560252c/passt),
applied to the newer upstream source. It permits Android's unavailable netlink
and namespace operations. `PASST_NO_SANDBOX=1` skips passt's namespace sandbox;
the Android app sandbox still applies, and passt attempts its additional seccomp
filter. Its optional raw-L2 path is unused here. Passt is GPL-2.0-or-later; the
adapter and kernel patches are GPL-2.0.

`patches/uml-vector-napi-budget.patch`, from the same project, fixes completion
at an exhausted NAPI budget. `patches/uml-network-cleanup.patch` is our adapter
fix: a closed datagram socket does not reliably deliver POLLHUP, so the pump
checks child exits and reaps both children. The original wrapper otherwise
left passt alive and the kernel as a zombie after poweroff.

The phone had an unavailable global proxy at `192.168.1.26:8877`. It was
disabled with the user's approval. The final guest tests use direct sockets;
the Android proxy is not automatically imported into the guest. Native HTTPS
also returned HTTP 200 after proxy removal. Plain HTTP to example.com reset
even for native curl; the public guest HTTP test instead uses Google's
`connectivitycheck.gstatic.com/generate_204` endpoint.

## Limits and next work

- IPv4 outbound networking is tested; IPv6, guest HTTPS, inbound forwarding,
  network switching, and sustained throughput are not tested. The minimal
  BusyBox wget has no TLS support. DNS is currently fixed rather than inherited
  from Android. This is not a full distribution networking setup.
- The network wrapper produces a `winch_thread: TIOCSCTTY` diagnostic; basic
  interactive input works, but guest console resizing needs follow-up.
- This device selects **ptrace mode** by default; seccomp mode is not tested.
- Guest RAM is backed by an unlinked file in app-private storage, not tmpfs.
  UML warns about that. Performance, flash I/O, and memory pressure require
  measurement; a RAM-backed implementation is a follow-up.
- No 16 KB Android host, long-duration workload, suspend/resume, or low-memory
  behavior has been tested. The guest itself uses 16 KB pages.
- This is a minimal root filesystem, without a package manager or normal
  distribution init. Use the remount/shutdown command above instead of killing
  the app while the filesystem is writable.
- This is a feasibility experiment, not a production release or a claim that
  the unmaintained kernel port has received a security review.
