# Release notes

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
