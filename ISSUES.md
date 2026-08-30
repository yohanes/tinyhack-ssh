# Tinyhack SSH Security Review — Issues

Status legend: `[ ]` open · `[x]` fixed · `[~]` partial

---

## HIGH

### H1. Debug HTTP server binds 0.0.0.0:8080 with zero authentication
- **File:** `app/src/main/java/com/tinyhack/ssh/debug/DebugHttpServer.java:50` (`new ServerSocket(port)`)
- **Impact:** When the opt-in debug server is enabled, any device on the same Wi‑Fi network can:
  - read the terminal screen (`/text` — may contain passwords/secrets typed by the user)
  - inject keystrokes / commands (`/input`, `/key`, `/type`) → arbitrary code execution as the app UID
  - read the clipboard, dump profiles (incl. stored SSH passwords), open arbitrary URIs, manage sessions
- **Fix:**
  1. Bind loopback only: `new ServerSocket(port, 50, InetAddress.getLoopbackAddress())` — `adb forward` still works.
  2. Require a bearer token on every request (incl. the HTML page). Token: 32 random bytes (hex), persisted in `filesDir/http_debug_token` (0600), retrievable via `adb shell run-as`. Accepted via `Authorization: Bearer <t>` header or `?token=<t>` query param. Constant-time compare.
- **Status:** [x]

### H2. Built-in biometric bypass `?force=true` on debug endpoints
- **File:** `DebugHttpServer.java` — `/ssh-agent/add` (~line 592), `/ssh-agent/addAll` (~line 614), `/ssh-agent/unlock` (~line 637); HTML buttons (~line 1254)
- **Impact:** Combined with H1 this is a *remote* agent unlock / key-load. Even after loopback binding, a bypass flag defeats the biometric gate by design.
- **Fix:** Remove the `force` parameter handling entirely. `/ssh-agent/add`, `/ssh-agent/addAll` always return 403 while biometric+locked; `/ssh-agent/unlock` returns 403 whenever biometric is enabled (unlock must happen in the app UI where a BiometricPrompt can be shown). Update the HTML buttons accordingly (drop the Unlock button, drop `?force=true`).
- **Status:** [x]

---

## MEDIUM

### M1. "Lock" button doesn't lock — agent keeps signing
- **Files:** `SshAgentServer.java` (no `locked` check anywhere), `SshAgentActivity.java:139` (Lock only flips a pref), `SshAgentManager.setLocked`
- **Impact:** Once keys are loaded they remain usable (`SSH_AGENTC_SIGN_REQUEST` succeeds) until process death or manual Clear. The lock gives users a false sense of security.
- **Fix:** Enforce server-side: when locked, `REQUEST_IDENTITIES`/`SIGN_REQUEST`/`ADD_IDENTITY` return FAILURE; `SshAgentActivity` Lock button should also evict keys (or call a `lock()` on the server).
- **Fixed:** `SshAgentServer.setLocked()` — while locked every agent operation answers FAILURE (OpenSSH semantics); `SshAgentManager.setLocked()` propagates to the running Java server; `startAgent()` syncs lock state. Verified on device: `ssh-add -l` → "agent refused operation" while locked; lists/signs normally after unlock. Also fixed `listKeys()` parsing garbage error lines as fake keys (keyCount was 1 while locked).
- **Status:** [x]

### M2. Shell injection in askpass script + useless env leak
- **File:** `SshAgentManager.java:394` (`echo "<passphrase>"` — `$(...)`/backticks execute inside double quotes), `:410` (`SSH_ASKPASS_PASS` is not an OpenSSH mechanism; leaks passphrase into child env)
- **Fix:** Write passphrase to a 0600 temp file; askpass script `cat`s it; delete + ideally zero it after. Remove `SSH_ASKPASS_PASS`.
- **Fixed:** Passphrase now lives only in a 0600 file (`files/tmp/askpass.pass.*`), delivered by a tiny native helper `libaskpass.so` (shipped in `nativeLibraryDir`) referenced via `SSH_ASKPASS` + `GHOSTTY_ASKPASS_FILE` env. **Note:** the intermediate askpass-*script* approach turned out to be non-functional on-device — targetSdk 29+ SELinux denies `execve()` of files in the app data dir ("Permission denied"), and neither ssh-add nor ssh-keygen read passphrases from piped stdin. The native helper is the only working headless mechanism; `SSH_ASKPASS_PASS` env leak removed. Verified on device: ssh-add + ssh-keygen with passphrase both exit 0 and the resulting key is genuinely encrypted (wrong passphrase rejected).
- **Status:** [x]

### M3. Profile SSH passwords stored in plaintext
- **File:** `ConnectionProfile.java:59` (`// stored plaintext`), persisted to `files/profiles/profiles.json`, also served over debug server `/profiles`
- **Fix:** Wrap password with an Android Keystore key (or EncryptedSharedPreferences); keep the debug endpoint from ever returning the password field.
- **Fixed:** New `model/ProfileCrypto.java` (AndroidKeyStore AES-256-GCM, alias `tinyhack.profile.passwords`). `ConnectionProfile.toJson()` serializes only `passwordEnc` ("v1:iv:ct") — plaintext never hits disk, SharedPreferences, or HTTP responses; `fromJson()` decrypts, with legacy plaintext migration on input. Verified on device: profiles.json contains ciphertext only; encrypt→decrypt→re-encrypt round-trip works.
- **Status:** [x]

### M4. Agent offers SHA-1 `ssh-rsa` signatures
- **File:** `SshAgentServer.java:332` (flags=0 → `SHA1withRSA`)
- **Impact:** Weak crypto; modern OpenSSH servers reject ssh-rsa anyway.
- **Fix:** Default to rsa-sha2-256 when flags=0, or refuse bare ssh-rsa.
- **Fixed:** flags==0 for RSA now throws → agent answers FAILURE ("ssh-rsa (SHA-1) signatures refused"). rsa-sha2-256/512 and ed25519 paths verified on device via `ssh-keygen -Y sign -U` (both exit 0).
- **Status:** [x]

---

## LOW / hardening

### L1. SK signature unconditionally asserts USER_PRESENT; 300s auth window + DEVICE_CREDENTIAL
- **Files:** `SshAgentServer.java:350` (`flags = SK_USER_PRESENT` always), `SshKeyManager.java:33` (`SK_AUTH_SECONDS = 300`), `:235` (`AUTH_DEVICE_CREDENTIAL`)
- One biometric/credential event opens a 5-minute window in which any same-UID process can get signatures — the signature *claims* per-use user presence that isn't verified per use.
- **Fixed (partial):** `AUTH_DEVICE_CREDENTIAL` removed from SK key generation — a PIN/pattern unlock no longer opens a signing window; only strong biometric does (affects newly generated SK keys). Remaining gap: the 300s window means one fingerprint authorizes multiple signatures; a per-signature BiometricPrompt (surfaced on `UserNotAuthenticatedException`) would be the full fix — deferred as future UX work since the agent can't currently raise UI.
- **Status:** [~]

### L2. SK counter is a SharedPreferences counter
- **File:** `SshAgentServer.java:384` — resettable by same-UID code; weakens server-side clone detection.
- **ACCEPTED RISK:** within the threat model (same-UID = trusted; anything running in the terminal can also use the agent directly), a tamper-proof counter adds no real protection — any same-UID attacker could simply request signatures themselves. Server-side clone detection is advisory only.
- **Status:** [x] (accepted, documented)

### L3. Passphrase/private-key input via plain EditText
- **File:** `SshKeysActivity.java:134, 230` — third-party IMEs can log keystrokes; no autofill exclusion.
- **Fixed:** passphrase field is now `TYPE_TEXT_VARIATION_PASSWORD`; passphrase + private-key paste fields excluded from autofill. (IMEs can still observe typed text — inherent to Android; pasting is the recommended flow for keys.)
- **Status:** [x]

### L4. Key filename not sanitized (`../` traversal out of ~/.ssh)
- **Files:** `SshKeyManager.generateKeyPair/importKey/deleteKey`
- **Fixed:** `SshKeyManager.isSafeKeyName()` (must match `[A-Za-z0-9][A-Za-z0-9._+-]*`, ≤128 chars) enforced in generate/import/delete; the generate + import dialogs show an explicit toast for invalid names.
- **Status:** [x]

### L5. Passphrase passed to ssh-keygen via `-N` argv (visible in /proc cmdline briefly)
- **File:** `SshKeyManager.java:139`.
- **Fixed:** generation with a passphrase no longer uses `-N`; the passphrase goes through the native `libaskpass.so` helper + 0600 file (`GHOSTTY_ASKPASS_FILE`), zeroed and deleted after. Verified on-device: generated key is genuinely passphrase-encrypted (wrong passphrase rejected, correct accepted) with no passphrase on any argv.
- **Status:** [x]

### L6. Misc
- `importKey` doesn't validate pasted key format nor pub/priv match → **fixed**: every import is validated via `ssh-keygen -y`; a provided pub key must match the private key or the import is rejected and files removed.
- `removeKey` by fingerprint silently no-ops → **fixed**: resolves a `SHA256:...` fingerprint to its key file in `~/.ssh` and removes via `ssh-add -d`.
- `listKeys` shells out to ssh-keygen once per key per refresh → **fixed**: fingerprints computed in process (SHA-256 over the wire blob, verified identical to `ssh-keygen -lf` on device).
- No connection/rate limit on agent socket → **fixed**: `SshAgentServer` caps concurrent clients at 16 (excess same-UID connections rejected); 20-way burst test passes.
- `addKey` for SK keys returns false silently when the native agent is in use → **fixed:** explicit warning log + early return.
- **Status:** [x]

---

## Design decision (from review)

### W1. busybox vi "Hit return" input wedge (Android pty) — OPEN
- **Symptom:** in bundled busybox vi, after any wrapped status message (e.g. `:w` →
  `'...vi_test.txt' 2L, 17C[Hit return to continue]`), **all further input stops reaching
  vi** — Enter, Ctrl-C (ISIG), arrow keys, everything. Output still renders; SIGWINCH
  (rotation) still redraws. Permanent for the session's vi. Reproduces with debug writes,
  real IME typing, and the key-bar buttons; with and without bash/job control; with vi
  as the direct session command.
- **Evidence gathered (instrumented native + Java write paths):**
  - `write(master_fd, "\r", 1)` returns 1, errno 0, correct fd (verified fd↔pts mapping);
    50 back-to-back writes all succeed (input queue never fills)
  - termios constant and raw during the wedge (iflag=0x4000, lflag=0x8a31, VINTR=3)
  - vi alive, 0% CPU, blocked in `poll` (do_sys_poll)
  - Ctrl-C (0x03) with ISIG on has no effect → bytes never reach the line discipline
  - App writes nothing extra to the pty (ghostty VT `on_write_pty` probe empty)
  - TIOCSTI fallback: EPERM (modern kernel restriction)
  - Same busybox 1.38.0 binary + identical byte pattern + same initial termios + bash in
    the middle + concurrent master reader: **cannot reproduce on a Linux desktop host** —
    every combination works. Android-kernel-specific pty wakeup/drop behavior.
- **User impact:** local busybox vi is effectively unusable past the first wrapped
  message (this is what "local vi :w doesn't work" actually was). Remote vi/vim over
  ssh/mosh is unaffected.
- **Workarounds for users:** use a wider font/terminal, or vi files via shorter paths,
  so messages don't wrap; or run vi remotely.
- **Next steps if revisited:** get device kernel version + pty driver details; try a
  newer Android kernel device; strace via a debuggable system image; check if the
  vendored busybox was built with unusual vi/termios feature flags vs. defconfig.
- **Status:** [ ] OPEN

### W2. Leaked ptm fd on session close — OPEN
- While investigating W1: the app process held **two** open `/dev/ptmx` fds (pts/27,
  pts/29) with only one live session — an earlier closed session's master was never
  closed. Repeated session churn leaks fds one per session until exhaustion.
- **Status:** [ ] OPEN

The add-gate protects nothing: file keys are readable by any same-UID process (anything the user runs in the terminal), and SK keys are non-exportable with Keystore-enforced auth at signing. Prerequisites before making this change: fix M1 (server-side lock enforcement) and L1 (per-signature confirmation story). Termux-style auto-load of passphrase-less keys at agent start is the recommended end state.

**IMPLEMENTED:** biometric load gate removed entirely (pref `agent_use_biometric` cleaned up; UI switch/biometric prompts removed from SshAgentActivity; debug endpoints gate on `locked` only; `/ssh-agent/unlock` no longer requires the app UI). Agent now starts **unlocked** and auto-loads passphrase-less keys in the background (`SshAgentManager.scheduleAutoLoad`). Manual Lock/Unlock retained — Lock refuses all agent operations server-side (M1). SK keys keep per-use Keystore biometric enforcement (unchanged). Verified on device: fresh start → locked=false, keyCount=1 without any unlock; lock → `ssh-add -l` refused + addAll 403; unlock → works again.
- **Status:** [x]

---

## Licensing (compliance tasks)

### LIC1. No LICENSE file in the repo
- Add the project's own MIT LICENSE.
- **Fixed:** `LICENSE` added (MIT, "Copyright (c) 2026 Yohanes" — adjust the holder name if desired).
- **Status:** [x]

### LIC2. No third-party notices
- **Fixed:** `THIRD-PARTY-NOTICES.md` + `licenses/` directory with the actual license texts copied from the vendored trees (bash GPLv3, busybox GPLv2-only, rsync GPLv3, OpenSSH LICENCE, OpenSSL Apache-2.0), GPL source-offer statement (build dirs + written offer), and font license notes (OFL / MIT / Ubuntu Font Licence). Notes for app integration: consider surfacing these in an in-app "About → Licenses" screen.
- Note: `libssh.so` is NOT LGPL libssh — it's the renamed OpenSSH `ssh` client binary (fine).
- **No need to switch away from bash** — it's a separate process, not linked.
- **Status:** [x]
