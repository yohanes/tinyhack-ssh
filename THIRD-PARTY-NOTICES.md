# Third-Party Notices — Tinyhack SSH

This file lists the third-party components distributed inside the Tinyhack SSH APK,
their licenses, and the corresponding source-offer. License texts are bundled in
the [`licenses/`](licenses/) directory.

Tinyhack SSH's own code is MIT-licensed (see [LICENSE](LICENSE)). The components
below are distributed as **standalone executables or data** inside the APK
(process-boundary "mere aggregation"), so they do not change the license of the
app's own code; each component's license and notice obligations still apply to
its distribution.

## Components

| Component | Version | License | License text | Source |
|---|---|---|---|---|
| GNU Bash | 5.2.37 | GPLv3 | `licenses/bash-5.2.37.COPYING.GPLv3` | upstream tarball + `build-bash/` |
| BusyBox | 1.38.0 | GPLv2 **only** | `licenses/busybox-1.38.0.LICENSE.GPLv2` | upstream tarball + `build-busybox/` |
| rsync | 3.5.0 | GPLv3 | `licenses/rsync-3.5.0.COPYING.GPLv3` | upstream tarball + `build-rsync/` |
| cloudflared (Cloudflare Access tunnel client) | 2026.8.2 | Apache-2.0 | `licenses/cloudflared-2026.8.2.LICENSE.Apache-2.0` | upstream git checkout + `scripts/build-cloudflared-android.sh`, `patches/cloudflared-android.patch` |
| OpenSSH (ssh, scp, sftp, ssh-add, ssh-agent, ssh-keygen, ssh-keyscan) | 10.5p1 | BSD-style (permissive) | `licenses/openssh-10.5p1.LICENCE` | upstream tarball + `build-openssh/`, `scripts/build-openssh-android.sh`, `patches/` |
| OpenSSL (statically linked into OpenSSH and mosh binaries) | 3.5.7 LTS | Apache-2.0 | `licenses/openssl-3.5.7.LICENSE.Apache-2.0` | upstream tarball + `build-openssl/` |
| Mosh (mosh-client + native `mosh` launcher) | 1.4.0 | GPLv3 | `licenses/mosh-1.4.0.COPYING.GPLv3` | `mosh/` (vendored) + `scripts/build-mosh-android.sh`, `scripts/mosh-launcher.cc` |
| protobuf (statically linked into mosh-client) | 21.12 | BSD-3-Clause | `licenses/protobuf-21.12.LICENSE.BSD-3` | upstream tarball + `build-mosh/` |
| ncurses / tinfo (statically linked into mosh-client) | 6.5 | X11-style (permissive) | `licenses/ncurses-6.5.COPYING` | upstream tarball + `build-mosh/` |
| zlib (statically linked into mosh-client) | 1.3.1 | zlib license | `licenses/zlib-1.3.1.LICENSE` | upstream tarball + `build-mosh/` |
| Ghostty terminal core (`libghostty-vt.a`) | vendored in `native/ghostty/` | MIT | upstream: https://ghostty.org (the vendored archive does not include a LICENSE file) | `native/ghostty/` |
| terminfo entries (xterm-kitty, xterm-ghostty, ghostty, kitty) | — | data / upstream project licenses (kitty is GPLv3) | see upstream kitty/ghostty projects | `app/src/main/assets/usr/share/terminfo/` |
| Fonts: Cascadia Code/Mono, Fira Code, Inconsolata, JetBrains Mono, Noto Mono | — | SIL Open Font License 1.1 | see each upstream project | `app/src/main/assets/fonts/` |
| Font: Hack | — | MIT + Bitstream Vera license | see upstream (source-foundry/Hack) | `app/src/main/assets/fonts/` |
| Font: Ubuntu Mono | — | Ubuntu Font Licence 1.0 (reserved font name "Ubuntu") | see upstream (design.ubuntu.com/font) | `app/src/main/assets/fonts/` |
| AndroidX / Material Components / androidx.biometric | — | Apache-2.0 | see AOSP/third-party metadata in each AAR | Gradle dependencies |

## GPL source offer (bash, BusyBox, rsync, mosh)

The complete corresponding source for the GPLv2/GPLv3 components — the exact
upstream release tarballs plus the build scripts, configuration, and patches
used to produce the binaries shipped in this APK — is available:

1. as the complete corresponding-source archive published alongside each app
   release (generated with `scripts/bundle-release-source.sh`); and
2. on written request to `tinyhack-ssh@tinyhack.com`, for at least three years / as
   long as the APK is offered, per GPLv3 §6 and GPLv2 §3(b).

No additional restrictions beyond the GPL terms are imposed on the redistribution
of these components.

## Font license notes

The bundled fonts are redistributed under their respective licenses (OFL 1.1,
MIT, Ubuntu Font Licence). The original font names are used unmodified; no
renamed/modified derivatives are distributed. OFL-reserved names and the Ubuntu
Font Licence reserved name ("Ubuntu") remain subject to their clauses. Font
license texts are available from the respective upstream projects.
