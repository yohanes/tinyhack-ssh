# Tinyhack SSH

A high-performance Android terminal emulator powered by the native **Ghostty** terminal core, featuring full VT100/XTerm emulation, true color rendering, an embedded GNU/BSD userland with the complete OpenSSH suite, multi-session management, connection profiles, and a Termux-like touch UX.

Tinyhack SSH is an independent, unofficial Android port. It uses the open-source Ghostty VT library and is not affiliated with or endorsed by the Ghostty project.

[Download Tinyhack SSH on Google Play](https://play.google.com/store/apps/details?id=com.tinyhack.ssh)

> Package id is `com.tinyhack.ssh`; the user-facing app name is **Tinyhack SSH**.

---

## 🚀 Features

- **Ghostty Native Core**: Embedded libghostty terminal engine providing accurate VT sequence parsing, reflow, colors, and styling. Profiles use `TERM=xterm-256color` by default and can explicitly enable `TERM=xterm-kitty`; matching terminfo entries are bundled via `TERMINFO`.
- **Optional Kitty Graphics Protocol**: Per-profile opt-in for image placements composited onto bounded cached bitmaps by z-layer. It is disabled by default to limit memory and untrusted remote-output capabilities.
- **Embedded GNU Bash Shell (5.2.37)**: Full interactive shell with GNU Readline, history management, persistent `~/.bashrc`, aliases, auto-wrap multi-line prompts, and programmable tab-completion.
- **Embedded BusyBox Suite (1.38.0)**: Over 270 user-space POSIX command-line utilities (`grep`, `sed`, `awk`, `find`, `tar`, `gzip`, `bzip2`, `xz`, `unzip`, `vi`, `less`, `tree`, `cal`, `bc`, `kill`, `pgrep`, `pkill`, `top`, `ps`, `nc`, `wget`, `curl`, etc.) enabled for non-root execution.
- **Embedded OpenSSH Suite (10.5p1 + OpenSSL 3.5.8 LTS)**: Built-in `ssh`, `ssh-keygen`, `ssh-keyscan`, `ssh-agent`, `ssh-add`, `scp`, and `sftp` binaries tailored for Android Bionic. OpenSSL is statically linked — ECDSA and security-key support without a runtime `libcrypto.so` dependency.
- **Embedded rsync (3.5.0)**: Full `rsync` binary for local copies and over-SSH transfers; combined with Storage Access it can sync phone storage files.
- **Embedded Mosh (1.4.0)**: Roaming/low-latency remote terminal with local echo prediction (`mosh [user@]host`; UDP survives Wi-Fi changes and sleep). Ships with a native launcher replacing mosh.pl.
- **Embedded cloudflared (2026.8+)**: Cloudflare Tunnel client (`cloudflared access ssh --hostname`) for Zero Trust Access SSH. Works as `ProxyCommand` in `~/.ssh/config` (`Host x; ProxyCommand cloudflared access ssh --hostname %h`) and via connection-profile toggle "Use Cloudflare Access". Supports browser `cloudflared access login` and Service Token (`--id`/`--secret`) auth; CGO-enabled build fixes Android DNS.
- **SSH Key Management UI**:
  - Generate Ed25519, RSA (4096-bit), and ECDSA security keys (`id_ecdsa_sk`, backed by the Android Keystore with StrongBox/TEE and biometric authorization).
  - Import existing private/public keys.
  - View SHA256 fingerprints and public keys.
  - One-tap clipboard copy and sharing.
  - Automatic `0700` (`~/.ssh`) and `0600` (private key) permission management.
- **Embedded SSH Agent**:
  - Android abstract-Unix-socket agent with peer-UID validation; sessions get `SSH_AUTH_SOCK` injected automatically.
  - Signs with Ed25519/RSA identities and FIDO/OpenSSH `-sk` security keys from the Keystore (fingerprint/device-auth unlock, 5-minute authorization window).
- **Multi-Session & Connection Profiles**:
  - Drawer-based session list: switch, rename (long-press), close.
  - Connection profiles (LOCAL / SSH / MOSH) with host, port, username, key, shell, cwd, env, and extra args (ssh or mosh, e.g. `--predict=always`); one-tap connect into a new session.
  - SSH agent forwarding, Kitty graphics, and OSC 52 clipboard writes are separate per-profile opt-ins and default off.
  - Cloudflare Access option per SSH profile: toggle "Use Cloudflare Access", set Access hostname + optional Service Token ID/Secret and `--destination` for bastion mode. Mosh is not offered through this TCP tunnel because its data path requires direct UDP reachability.
- **Termux-like UX & Background Running**:
  - Three-finger tap menu, scrollback, selection mode with copy bar.
  - **Fullscreen mode** via the three-finger menu (hides the toolbar, status bar, and navigation bar); **Open drawer** menu entry keeps sessions/profiles reachable while fullscreen.
  - Closing the app keeps sessions running as a foreground service; a persistent notification reopens the terminal and offers an **Exit** action that fully terminates the app.
  - The F-Droid/direct-download build offers an optional **Storage Access** toggle that creates `~/storage -> /storage/emulated/0` so shells/rsync can reach phone files. The Google Play build omits both the permission and the toggle.
- **Clickable Hyperlinks (OSC 8)**:
  - URIs embedded as `ESC]8;;https://example.com ESC\` are underlined and tappable; tap opens the URL.
  - By default shows a confirmation dialog with the (truncated) URL and **Open** / **Copy Link** / **Cancel**. The **Confirm URL click** setting now directly reflects that behavior, and only HTTP(S)/mailto links can open.
- **Desktop Notifications (OSC 9 / OSC 777)**:
  - Programs that emit `ESC]9;message BEL` or `ESC]777;notify;title;body BEL` trigger an Android notification in channel *Desktop Notifications* that opens Tinyhack SSH on tap.
- **Styled Underlines & Underline Colors (SGR 4:x / 58)**:
  - Full `SGR 4:1` single, `4:2` double, `4:3` curly (wavy), `4:4` dotted, `4:5` dashed and `SGR 58:2::R:G:B` / `58:5:N` / `59` underline colors; hyperlink forces underline when none set.
- **Synchronized Output (DEC 2026)**:
  - `CSI ?2026h` suspends rendering and `CSI ?2026l` resumes with an atomic flip, eliminating screen tearing for fast full-screen redraws.
- **Semantic Shell Integration (OSC 133)**:
  - Bash is auto-wired via `~/.bashrc` to emit `OSC 133;A/B/C/D` prompt/input/output markers (`GHOSTTY_CELL_SEMANTIC_PROMPT/INPUT/OUTPUT`) with no Ghostty core changes.
  - **Command Navigation:** `Ctrl+Shift+Up` / `Ctrl+Shift+Down` (also overflow menu / 3-finger menu *Previous/Next prompt*) jumps the viewport to the previous/next prompt.
  - **Smart Resizing:** prompt blocks are kept intact on window resize/reflow.
  - **Output Capture:** *Copy last output* (overflow / 3-finger menu) copies only the last command's output without the prompt or command.
- **Monospace Typography & Font Picker (9 OFL families)**:
  - Bundled: JetBrains Mono (default), Hack, Fira Code, DejaVu Sans Mono, Cascadia Code/Mono, Noto Mono, Inconsolata, Ubuntu Mono (Regular/Bold/Italic/Bold-Italic each).
  - Pick via the **Settings** page (reachable from the toolbar overflow menu or the three-finger menu); family and size persist across restarts.
  - Monospace grid-anchored run scaling keeps box-drawing borders and cursors pixel-aligned.
- **Accessory Keyboard & Function Keys**:
  - Sticky modifier buttons: `ESC`, `TAB`, `CTRL`, `ALT`, `FN`.
  - Dedicated `FN` row exposing `F1`–`F12`, `HOME`, `END`, `PGUP`, `PGDN`, `INS`, `DEL`.
  - **Adaptive layout**: when the soft keyboard is hidden the bar switches to a compact row — *keyboard icon* (reopens the IME) | `ESC` | `ENTER` | `SPACE` | arrows; the full layout returns when the keyboard is shown.
  - Desktop-like `ESC` sequencing (350 ms timeout; `ESC` + `0` sends instant `F10` for Midnight Commander).
  - Proper Backspace standard (`0x7F` DEL) handling for local shells and remote Linux SSH sessions.
- **Settings Page**:
  - Dedicated screen with Font (family + size) and Confirm URL click. Debug builds additionally expose the HTTP Debug Server; F-Droid/direct-download builds expose Storage Access.
  - Opened from the toolbar overflow menu or the three-finger tap menu; all options persisted.
- **Debug & Automation HTTP Server (opt-in)**:
  - Port 8080 HTTP server for screen inspection and automated input.
  - **Disabled by default** — enable via Settings → "HTTP Debug Server" (persisted).

---

## 🏗️ Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                       Android App (UI)                      │
│  MainActivity  •  SshKeysActivity  •  ExtraKeysView (Toolbar)│
│                 TerminalView (Canvas)                │
└──────────────────────────────┬──────────────────────────────┘
                               │ JNI (NativeBridge.java)
┌──────────────────────────────▼──────────────────────────────┐
│                    ghostty_jni.cpp (C++)                    │
│  PTY Master/Slave Fork (forkpty) • Winsize IOCTL • VT Write │
│  Row Cells Iterator Buffer Mapping • Kitty Graphics Bridge  │
└──────────────────────────────┬──────────────────────────────┘
                               │ C API (ghostty.h)
┌──────────────────────────────▼──────────────────────────────┐
│               libghostty.a (Ghostty Core - Zig)             │
│  VT Engine • Render State • Color Palette • Key Encoding    │
└─────────────────────────────────────────────────────────────┘
```

---

## 🔧 Building & Installing

### Distribution variants

- `play`: Google Play build. It never declares `MANAGE_EXTERNAL_STORAGE` and cannot enable Storage Access.
- `fdroid`: F-Droid/direct-download build. It declares `MANAGE_EXTERNAL_STORAGE`, but access remains off until the user enables Storage Access and grants Android's All files access setting.

Both variants use package ID `com.tinyhack.ssh`. Use the same signing key for direct-download upgrades if users should be able to move between your builds without reinstalling. Official F-Droid builds may use a different F-Droid-controlled signing key.

### Prerequisites

- **Android SDK & NDK** (API 34, NDK 26+).
- **Java 17+**.
- **Android Device or Emulator** (`arm64-v8a`).

### Build Steps

```bash
# Set Android SDK path
export ANDROID_HOME=$HOME/Android/Sdk

# Build the development APK with Storage Access available
./gradlew assembleFdroidDebug

# Install on connected device
adb install -r app/build/outputs/apk/fdroid/debug/app-fdroid-debug.apk

# Launch App
adb shell am start -n com.tinyhack.ssh/.MainActivity
```

---

## 🛠️ Testing & Automation via Debug Server (development builds only)

The debug HTTP server is excluded from runtime use in release builds and is
**disabled by default** in debug builds. Enable it first: Settings (toolbar
overflow menu → Settings) → "HTTP Debug Server" (the setting persists). Then:

```bash
# Forward port 8080 to host
adb forward tcp:8080 tcp:8080
TOK=$(adb shell run-as com.tinyhack.ssh cat files/http_debug_token | tr -d '\r\n')

# Get screen text dump
curl -s -H "Authorization: Bearer $TOK" http://localhost:8080/text

# Send text / commands to terminal
curl -s -H "Authorization: Bearer $TOK" -X POST --data-binary $'ls -la\n' http://localhost:8080/input

# Session / profile / agent introspection and automation
curl -s -H "Authorization: Bearer $TOK" http://localhost:8080/sessions | python3 -m json.tool
curl -s -H "Authorization: Bearer $TOK" -X POST "http://localhost:8080/sessions/new?profileId=<id>"
curl -s -H "Authorization: Bearer $TOK" http://localhost:8080/profiles | python3 -m json.tool
curl -s -H "Authorization: Bearer $TOK" http://localhost:8080/ssh-agent/status | python3 -m json.tool
```

---

## ☁️ Cloudflare Access SSH

Tinyhack SSH bundles `cloudflared` (CGO-enabled, `GOOS=android`, correctly resolves Android DNS) as `~/usr/bin/cloudflared` (`libcloudflared.so`).

**Option 1 – Connection profile (recommended):**
1. Create SSH profile → enable **Use Cloudflare Access**.
2. Set **Cloudflare Hostname** to your Access app (e.g. `xaccess.example.com`; leave empty to use the SSH Host).
3. Optional: set **Service Token ID/Secret** to skip browser; otherwise run once `cloudflared access login https://<hostname>` in a local shell and open the printed URL (tap the OSC 8 hyperlink) to authenticate.
4. Optional **Destination** (`host:port`) for bastion/jump-host mode.
5. Save → tap **Connect**.

The app validates and shell-quotes every generated ProxyCommand value. Service-token credentials are supplied through cloudflared's `TUNNEL_SERVICE_TOKEN_ID` and `TUNNEL_SERVICE_TOKEN_SECRET` environment variables so they do not appear in the child process arguments.

**Option 2 – `~/.ssh/config` (desktop parity):**
```ssh-config
Host ssh-cf
  Hostname ssh.example.com
  ProxyCommand /data/data/com.tinyhack.ssh/files/usr/bin/cloudflared access ssh --hostname %h
  User ubuntu
```
Then `ssh ssh-cf` from any local shell works. For Service Tokens, export
`TUNNEL_SERVICE_TOKEN_ID` and `TUNNEL_SERVICE_TOKEN_SECRET` in that shell before
starting SSH; do not put credentials in `~/.ssh/config` or process arguments.

**Building `cloudflared`:**
```bash
./scripts/build-cloudflared-android.sh   # GOOS=android CGO_ENABLED=1, patches applied, outputs libcloudflared.so
./gradlew assembleFdroidDebug
```
