# Release notes

## 1.4.0 (version code 12) — libghostty-vt 1.0, downloaded Linux images, 2026-11-08

- Update the terminal core to libghostty-vt 1.0.0 (tag `vt-1.0.0`,
  `0fa3d577a5614150ff8e2c152de5fb9d5c6d2dec`), the first release with a stable
  C API and ABI. It needs Zig 0.17.x; the build uses the project toolchain.
- Port the Android bridge to the 1.0 API: style flags, the sized grapheme
  buffer, Kitty image pixels as a borrowed string, borrowed key text, byte
  encoder buffers, option-based formatters (selection text and command output
  no longer change the user's selection), and the renamed enums.
- Rebase the OSC 99 patch. Its terminal option moves to 10000, away from
  upstream values, which now continue past 48; the callback type is
  registered for the library's type metadata.
- Add `scripts/test-libghostty-smoke.py`, a debug-build check of every
  libghostty path the app uses (keys, Kitty keyboard, mouse, bracketed paste,
  formatter, selection, hyperlinks, scrolling, synchronized output, OSC 99,
  rendering).
- Download the Debian and Ubuntu system images instead of bundling them, which
  shrinks the app by about 75 MB. The first environment of each system downloads
  its image (35–45 MB) from the project's GitHub releases, checks it against a
  SHA-256 pinned in `uml-env`, and keeps it in `~/uml/cache/` for later ones. An
  interrupted download resumes; a corrupt one is discarded. The templates bundled
  by earlier versions are removed after the update. Each template release
  carries the image's corresponding source
  (`scripts/publish-uml-template.sh` publishes and checks them).
- List the third-party code linked into libghostty-vt in the notices and the
  About screen: Highway 1.2.0, simdutf 9.0.0, uucode 0.2.0 (with Unicode data
  and Bjoern Hoehrmann's UTF-8 decoder), Wuffs 0.4.0-alpha.10, and translate-c
  2.0.0, each with its license text. `scripts/collect-libghostty-licenses.py`
  regenerates them from Ghostty's dependency manifest.
- Bring the APK's copy of `THIRD-PARTY-NOTICES.md` up to date; 1.3.0 shipped
  it without the Ubuntu template, boot shim, and hostfs patch entries.

## 1.3.0 (version code 11) — Linux environment manager, Ubuntu, shared home, local DNS, 2026-11-04

- Add a **Linux environments** screen (drawer). It lists every UML environment
  with its system, user, memory, disk size and actual use, and whether it is
  running. Create a Debian or Ubuntu environment, start it, change its memory
  and home sharing, view its console log, add a one-tap connection profile, or
  delete it. A running environment cannot be deleted. Creation and the guest
  console run in terminal sessions and return to the screen when they finish.
- Add an Ubuntu 26.04 LTS template (`uml-env create -t ubuntu ...`), built from
  the official arm64 image pinned by digest, with the image's default `ubuntu`
  account removed so the environment user gets UID 1000.
- Share the app home (the Android shell's `~`) at `/data` inside environments,
  on by default and switchable per environment
  (`uml-env config NAME --share-home on|off`, or `--share-home` at creation).
  Files appear owned by the environment user, so ordinary commands can use
  them; changing their owner is refused.
- Boot every environment through a small initramfs shim that mounts the disk,
  adds `/data`, and hands over to the disk's own init. Existing environments get
  the new features without being recreated.
- Patch the UML kernel's hostfs with `uid=`/`gid=` mount options
  (`patches/uml-hostfs-owner.patch`); the legacy path-only option is unchanged.
- Resolve DNS through Android inside environments, so local network names
  work (for example a router's DHCP host names, and `*.local` multicast DNS
  names). Guests use the virtual nameserver `10.0.2.3`, which the network
  launcher (`umnet`) answers itself with Android's resolver: it follows Wi-Fi,
  mobile data, VPN and Private DNS changes while the guest runs. The boot shim
  moves existing environments from the old `1.1.1.1` default; a nameserver you
  set yourself is kept (`patches/uml-network-dns.patch`).
- `uml-env` gains `delete NAME [--yes]` and a tab-separated `status` listing.
- Remove bundled templates left behind by an app update.

## 1.2.0 (version code 10) — File manager and remote browser, 2026-11-03

- Add a Files screen (drawer, overflow menu, and quick menu) for browsing the
  app home and, when All files access is granted, phone storage, SD cards, and
  Downloads. Copy, move, rename, delete, share, open with another app, edit in
  `vi`, create folders, sort, show hidden files, and jump to any path.
- Upload selected files/folders to a saved SSH profile with `scp` or `rsync`,
  download a remote file or folder into the current folder, or open an
  interactive `sftp` session whose local side starts in that folder. Transfers
  run in a terminal session (so password, passphrase, and host-key prompts work)
  and return to Files when finished. They reuse the profile's port, key,
  Cloudflare Access, and transfer-safe extra ssh options; agent forwarding is
  always off for transfers.
- Add a remote file browser (**Browse server** in Files). It logs in once
  (silently when the key needs no prompt, otherwise in a terminal session) and
  keeps an OpenSSH control master for 15 idle minutes. Listing, opening,
  renaming, deleting and creating folders use a built-in SFTP client over that
  connection; downloads, uploads, `sftp` and **Open SSH terminal here** reuse
  it without logging in again. The upload dialog's **Browse…** picks the remote
  destination folder. **Disconnect** and the notification Exit action log out.
- Patch the bundled OpenSSH so control masters work on Android: SELinux forbids
  the hard link `ssh -M` uses to publish its socket, so fall back to `rename()`
  after checking that no socket already exists
  (`patches/openssh-mux-no-hardlink.patch`).
- The Google Play build now also declares `MANAGE_EXTERNAL_STORAGE` for the file
  manager. Access stays off until the user taps **Allow** in Files (or enables
  Storage Access in Settings), reads the in-app explanation, and grants All
  files access in Android Settings.
- The explicit `-S`/`-e` ssh path keeps scp, sftp, and rsync working under
  development package IDs, whose data directory differs from the built-in path.

## 1.1.3 (version code 9) — UML console-close cleanup, 2026-11-02

- Clean up UML and its network helper when its terminal closes, so the
  environment can be started again instead of remaining locked by an orphan.

## 1.1.2 (version code 8) — Configurable UML memory, 2026-11-02

- Add saved UML memory configuration (`uml-env config NAME --memory 4G`),
  creation/start overrides, and a Memory (MiB) field for local UML profiles.
  Existing environments keep the 512 MiB default unless configured otherwise.

## 1.1.1 (version code 7) — UML console wakeup fix, 2026-11-02

- Fix UML console input occasionally waiting for another keypress before the
  final typed character appears. Wake the Android UML kernel after its epoll
  event queue becomes ready, avoiding a race with host tty SIGIO delivery.

## 1.1.0 (version code 6) — UML Debian environments, 2026-11-01

- Bundle the experimental ARM64 User-Mode Linux kernel and Debian template in
  the main app; no root or ADB setup is required.
- Add `uml-env create -t debian -n NAME -d SIZE -u USER`, `uml-env start NAME`,
  and `uml-env list`. Environments have independent persistent sparse disks.
- Create the selected Linux user with passwordless sudo; use normal Debian
  APT repositories to install packages. Initial creation requires internet.
- Choose named environments in LOCAL connection profiles.
- Preserve zero-filled template regions as sparse holes; a tested 1 GiB disk
  used about 125 MiB after initial Debian and sudo setup.
- Align the UML stub for Android's 16 KiB page-size packaging requirements.
- Include the security refresh below, which was prepared as 1.0.4 but not published.

UML remains experimental. Packages requiring additional kernel features,
hardware, or systemd services may need additional setup.

## 1.0.4 (version code 5) — security refresh, 2026-11-01

- Update Ghostty's terminal core to v1.4.0, pinned to
  `83fb95624b0439ee660c2679b43b38c8b63769cd`, including its October 27
  terminal/graphics security fixes. Port the OSC 99 notification callback to
  the new API and ship matching C headers.
- Update cloudflared to 2026.10.7, rebuilt with Go 1.27.3
  (CVE-2026-111943 certificate-authority mitigation).
- Override cloudflared's golang.org/x/net dependency with v0.62.0
  (CVE-2026-97045 and CVE-2026-78661). Keep the exact patched, vendored
  dependency sources in the corresponding-source archive.
- Update Abseil to 20260817.0 and rebuild Mosh/protobuf against it.
- Update AndroidX Core to 1.19.1, AGP to 9.5.0, Gradle to 9.8.2, and the
  compile SDK to 37. Target SDK remains 36; minimum Android version remains 14.
- Require confirmation for pasted CR/LF and other control characters, even
  in short clipboard contents. Encode pasted text through Ghostty, respecting
  bracketed paste and filtering unsafe control bytes.
- Refresh build scripts, dependency notices, and corresponding-source packaging.

### Security audit scope

Reviewed upstream releases and advisories on 2026-11-01. Bash 5.3 + patches
001–020, BusyBox 1.38.0, curl 8.23.0, rsync 3.5.1, OpenSSH 10.6p1,
OpenSSL 3.5.9 LTS, Mosh 1.4.0, protobuf 36.2, zlib 1.3.2, and ncurses 6.6
remain at their existing upstream release versions. OpenSSL stays on its
supported LTS branch. Remaining AndroidX/Material dependencies stay on their
latest stable versions; prereleases are not adopted.

The Ghostty update includes fixes for
GHSA-w9q4-5jvv-mcv4 (screen clearing memory corruption),
GHSA-qjfx-r533-6vx3 (scrollback use-after-free),
GHSA-jqr7-69mm-v35h (wide-page resize overflow),
GHSA-3r5j-c6rj-xq2v (clipboard alias memory growth),
GHSA-mhff-mv4q-fp63 (Kitty graphics handling), and
GHSA-4hwh-wx3w-3fqg (carriage-return paste protection).
macOS UI, zsh integration, and Ghostty's tmux control-mode application
advisories are not claimed as Android-specific vulnerabilities.

The Android/arm64 cloudflared source scan with govulncheck v1.9.0 reports no
reachable vulnerabilities. Its stripped-binary scan conservatively flags
GO-2026-5932 against the x/crypto module; the affected OpenPGP packages are
not in this Android executable's import graph. This is not a claim that all
third-party software is free of undiscovered vulnerabilities.

References:

- [Ghostty advisories](https://github.com/ghostty-org/ghostty/security/advisories)
- [Go 1.27.3](https://go.dev/doc/devel/release#go1.27.3)
- [Go CA mitigation](https://go.dev/issue/82345)
- [x/net HTML rendering advisory](https://pkg.go.dev/vuln/GO-2026-6866)
- [x/net HTML parsing advisory](https://pkg.go.dev/vuln/GO-2026-6860)
- [OpenPGP advisory](https://pkg.go.dev/vuln/GO-2026-5932)
- [cloudflared releases](https://github.com/cloudflare/cloudflared/releases)
- [curl security](https://curl.se/docs/security.html)
- [OpenSSL security](https://openssl-library.org/news/vulnerabilities/)
- [OpenSSH releases](https://www.openssh.com/releasenotes.html)
