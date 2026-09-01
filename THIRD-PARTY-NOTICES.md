# Third-Party Notices -- Tinyhack SSH

This file lists the third-party components distributed inside the Tinyhack SSH APK,
their licenses, and the corresponding source-offer. License texts are bundled in
the [`licenses/`](licenses/) directory.

Tinyhack SSH's own code is MIT-licensed (see [LICENSE](LICENSE)). Some components
below are separate executables, while Ghostty is linked into the JNI library and
OpenSSL, protobuf, ncurses, and zlib are statically linked into the executables
identified below. Each component remains subject to its own license and notice
requirements; this notice does not attempt to reclassify linked components as
"mere aggregation."

## Components

| Component | Version | License | License text | Source |
|---|---|---|---|---|
| GNU Bash | 5.2.37 | GPLv3 | `licenses/bash-5.2.37.COPYING.GPLv3` | upstream tarball + `build-bash/` |
| BusyBox | 1.38.0 | GPLv2 **only** | `licenses/busybox-1.38.0.LICENSE.GPLv2` | upstream tarball + `build-busybox/` |
| rsync | 3.5.0 | GPLv3 | `licenses/rsync-3.5.0.COPYING.GPLv3` | upstream tarball + `build-rsync/` |
| cloudflared (Cloudflare Access tunnel client) | 2026.8.2 | Apache-2.0 plus vendored dependency licenses | `licenses/cloudflared-2026.8.2.LICENSE.Apache-2.0`, `licenses/cloudflared-2026.8.2-THIRD-PARTY-NOTICES.txt` | exact vendored source + `scripts/build-cloudflared-android.sh`, `patches/cloudflared-android.patch` |
| OpenSSH (ssh, scp, sftp, ssh-add, ssh-agent, ssh-keygen, ssh-keyscan) | 10.5p1 | BSD-style (permissive) | `licenses/openssh-10.5p1.LICENCE` | upstream tarball + `build-openssh/`, `scripts/build-openssh-android.sh`, `patches/` |
| OpenSSL (statically linked into OpenSSH and mosh binaries) | 3.5.8 LTS | Apache-2.0 | `licenses/openssl-3.5.8.LICENSE.Apache-2.0` | upstream tarball + `build-openssl/` |
| Mosh (mosh-client + native `mosh` launcher) | 1.4.0 | GPLv3 | `licenses/mosh-1.4.0.COPYING.GPLv3` | `mosh/` (vendored) + `scripts/build-mosh-android.sh`, `scripts/mosh-launcher.cc` |
| protobuf (statically linked into mosh-client) | 21.12 | BSD-3-Clause | `licenses/protobuf-21.12.LICENSE.BSD-3` | upstream tarball + `build-mosh/` |
| ncurses / tinfo (statically linked into mosh-client) | 6.5 | X11-style (permissive) | `licenses/ncurses-6.5.COPYING` | upstream tarball + `build-mosh/` |
| zlib (statically linked into mosh-client) | 1.3.1 | zlib license | `licenses/zlib-1.3.1.LICENSE` | upstream tarball + `build-mosh/` |
| Ghostty terminal core (`libghostty-vt.a`, linked into `libghostty-android.so`) | vendored snapshot | MIT | `licenses/ghostty-MIT.LICENSE` | `ghostty/` + generated `native/ghostty/` archive |
| terminfo entries (`xterm-kitty`, `xterm-ghostty`, `ghostty`, `kitty`, `xterm-256color`) | N/A | Ghostty/kitty/ncurses upstream licenses | Ghostty MIT above; `licenses/ncurses-6.5.COPYING`; GPLv3 text bundled with the GPL components | compiled assets plus reproducible source in `terminfo-sources/` |
| Cascadia Code and Cascadia Mono | bundled font versions | SIL OFL 1.1 | `licenses/font-cascadia.OFL-1.1` | Microsoft Cascadia Code project |
| Fira Code | bundled font version | SIL OFL 1.1 | `licenses/font-fira-code.OFL-1.1` | Fira Code project |
| Inconsolata | bundled font version | SIL OFL 1.1 | `licenses/font-inconsolata.OFL-1.1` | Google Fonts Inconsolata project |
| JetBrains Mono Nerd Font | bundled patched font version | SIL OFL 1.1 plus Nerd Fonts notices | `licenses/font-jetbrains-mono.OFL-1.1`, `licenses/font-nerd-fonts.LICENSE` | JetBrains Mono and Nerd Fonts projects |
| Noto Mono | bundled font version | SIL OFL 1.1 | `licenses/font-noto.OFL-1.1` | Google Noto Fonts project |
| Hack | bundled font version | MIT + Bitstream Vera terms | `licenses/font-hack.LICENSE` | Source Foundry Hack project |
| DejaVu Sans Mono | bundled font version | Bitstream Vera-derived permissive license | `licenses/font-dejavu.COPYRIGHT` | DejaVu Fonts project |
| Ubuntu Mono | bundled font version | Ubuntu Font Licence 1.0 | `licenses/font-ubuntu.UFL-1.0` | Canonical Ubuntu Fonts project |
| AndroidX / Material Components / androidx.biometric | N/A | Apache-2.0 | see AOSP/third-party metadata in each AAR | Gradle dependencies |

## GPL source offer (bash, BusyBox, rsync, mosh)

The complete corresponding source for the GPLv2/GPLv3 components -- the exact
upstream release tarballs plus the build scripts, configuration, and patches
used to produce the binaries shipped in this APK -- is available:

1. from the public source repository at
   <https://github.com/yohanes/tinyhack-ssh>, including corresponding-source
   archives published alongside app releases (generated with
   `scripts/bundle-release-source.sh`); and
2. on written request to `tinyhack-ssh@tinyhack.com`, for at least three years / as
   long as the APK is offered, per GPLv3 section 6 and GPLv2 section 3(b).

No additional restrictions beyond the GPL terms are imposed on the redistribution
of these components.

## Font license notes

The bundled fonts are redistributed under their respective licenses (OFL 1.1,
MIT/Bitstream Vera terms, the DejaVu license, and Ubuntu Font Licence 1.0).
The exact copyright notices and license texts are included in the APK under
`assets/licenses/` and are accessible from the in-app About screen. The original
font names are used for upstream fonts; the patched JetBrains font is identified
as a Nerd Font in both its embedded metadata and these notices.
