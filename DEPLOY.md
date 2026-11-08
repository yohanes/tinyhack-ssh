# Tinyhack SSH deployment guide

This runbook describes the two release distributions:

- `playRelease` produces the Google Play AAB.
- `fdroidRelease` produces the F-Droid/direct-download APK.

Both declare `MANAGE_EXTERNAL_STORAGE` for the built-in file manager and the
optional `~/storage` link. Access stays off until the user reads the in-app
explanation and grants All files access in Android Settings. The flavors keep
the `STORAGE_ACCESS_AVAILABLE` gate so the Play build can drop the permission
again (see section 10).

Both use package ID `com.tinyhack.ssh`; the manifest and runtime feature gate
are selected by the Gradle distribution flavor.

Tinyhack SSH is an independent, unofficial Android port using the open-source
Ghostty VT library. It is not affiliated with or endorsed by the Ghostty
project. Keep that statement in the store listing, website, and About screen.

## 1. Release prerequisites

Install or verify:

- OpenJDK 17
- Android SDK at `/home/yohanes/Android/Sdk`
- Android NDK `26.1.10909125`
- Gradle 9.8.2 via the checked-in `./gradlew` wrapper
- `keytool`, `jarsigner`, `readelf`, `xz`, and standard GNU build tools
- An arm64 Android 14+ device for final testing

Set the SDK location for the current shell:

```bash
export ANDROID_HOME=/home/yohanes/Android/Sdk
```

Before every release, confirm that the intended source and assets are committed
and that no secret or unrelated untracked file will enter the source archive:

```bash
git status --short
git diff --check
```

The source bundler includes tracked files and all non-ignored untracked files.
`.env.release`, Gradle outputs, and `release/` are ignored.

## 2. Version and identity

Edit `app/build.gradle` before uploading a new release:

```groovy
versionCode 11
versionName "1.3.0"
```

Rules:

- Every Play upload must have a `versionCode` greater than every previous
  upload, including rejected or testing releases.
- `versionName` is the user-facing version.
- The application ID is `com.tinyhack.ssh`. It cannot be changed after the
  first Play publication without creating a separate Play app.
- The current release supports only `arm64-v8a` and Android 14+ (`minSdk 34`).

## 3. Create and protect the signing key

For the first release only, create an RSA upload key outside the repository:

```bash
mkdir -p "$HOME/.android/tinyhack-ssh-keys"
chmod 700 "$HOME/.android/tinyhack-ssh-keys"

keytool -genkeypair -v \
  -keystore "$HOME/.android/tinyhack-ssh-keys/tinyhack-ssh-upload.jks" \
  -alias tinyhack-ssh-upload \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000
```

Back up the keystore and its passwords in at least two secure locations. Do not
commit the keystore or passwords. Losing the upload key requires a Play Console
key-reset process; losing a self-managed app-signing key can be unrecoverable.

Enroll the app in Play App Signing when Play Console asks. The upload key signs
the AAB sent to Google; the Play app-signing key signs APKs delivered to users.

Create the local signing environment:

```bash
cp .env.release.example .env.release
chmod 600 .env.release
```

Fill in `.env.release`:

```dotenv
TINYHACK_SSH_KEYSTORE_PATH=/absolute/path/to/tinyhack-ssh-upload.jks
TINYHACK_SSH_KEYSTORE_PASSWORD=replace-me
TINYHACK_SSH_KEY_ALIAS=tinyhack-ssh-upload
TINYHACK_SSH_KEY_PASSWORD=replace-me
```

## 4. Rebuild native userland for 16 KiB pages

When terminal-core code changes, run `scripts/build-ghostty-android.sh` with
Zig 0.17.x (the project toolchain in `build/toolchains`) and the pinned
libghostty-vt 1.0.0 source (tag `vt-1.0.0`). This applies the tracked OSC 99
patch and installs the archive/header used by the Gradle build. After updating
libghostty-vt, run `uv run scripts/test-libghostty-smoke.py` against a debug
build (an x86_64 emulator with ARM translation works) to exercise every API
path the Android bridge uses, then `uv run scripts/collect-libghostty-licenses.py`.
libghostty-vt links third-party code (Highway, simdutf, uucode, Wuffs,
translate-c, listed as `linked` in `ghostty/deps/libghostty-vt-third-party.json`);
the script writes their license files to `licenses/` and the APK assets, and
fails until `THIRD-PARTY-NOTICES.md` and the About screen list each one. Files a
package refers to but does not ship are kept in `license-sources/`.

Run this for the first release and whenever Bash, BusyBox, curl, rsync,
OpenSSH, OpenSSL, Mosh, protobuf, ncurses, zlib, the NDK, or their build flags
change:

```bash
GOTOOLCHAIN=go1.27.3 JOBS="$(nproc)" scripts/rebuild-userland-16kb.sh
```

The script rebuilds the bundled executables and verifies their ELF LOAD
alignment. Every line must be `OK`; LOAD alignment must be at least 16 KiB
and a multiple of 16 KiB (the UML kernel uses `0x10000`).

The Gradle CMake build applies the same 16 KiB linker setting to
`libghostty-android.so` and `libaskpass.so`.

Do not continue if any native file reports `0x1000`, `0x2000`, or `FAIL`.

## 5. Build and test on the connected device

UML now ships in both distributions. Rebuild it with
`scripts/build-uml-kernel.sh`, `scripts/build-uml-network.sh`,
`scripts/build-uml-shim.sh`, and `scripts/package-uml-runtime.sh` (NDK r27c for
UML; override with `UML_NDK`). Keep `shim.cpio` in
`app/src/main/assets/usr/share/uml/`. The Debian and Ubuntu templates are not in
the APK: `uml-env` downloads the files pinned in its `template_info()` from
GitHub releases. After rebuilding a template with
`scripts/build-uml-env-template.sh TYPE`, pin the entry it prints.
Run `uv run scripts/fetch-debian-template-sources.py debian` and `... ubuntu`,
and retain the exact glibc/cross-toolchain sources, before publishing a template
or generating the corresponding-source archive.
Publish each pinned template that has no release yet (section 7) before testing,
or test against a local copy with `UML_TEMPLATE_URL` (see
`experiments/uml/ENVIRONMENTS.md`).
In the app's **Linux environments** screen, create one Debian and one Ubuntu
environment with no manually installed template, start each, and check sudo,
APT, `/data` (the app home, owned by the environment user), sparse allocation,
**Add to profiles** launch, Settings, and Delete, and that a local network
host name resolves inside the guest (`getent hosts NAME`). Then run
`uv run scripts/test-uml-share-home.py NAME` and `uv run
scripts/test-uml-dns.py NAME --lan-name HOST --mdns-name HOST.local` on a
stopped, disposable one, with the phone awake and the app in the foreground.

Build lint and both debug APKs:

```bash
export ANDROID_HOME=/home/yohanes/Android/Sdk
./gradlew \
  clean lintPlayDebug lintFdroidDebug assemblePlayDebug assembleFdroidDebug
```

Install and start it:

```bash
adb -s 59HYAIAECAIFL7HM install -r \
  app/build/outputs/apk/fdroid/debug/app-fdroid-debug.apk
adb -s 59HYAIAECAIFL7HM shell am force-stop com.tinyhack.ssh
adb -s 59HYAIAECAIFL7HM shell am start -W \
  -n com.tinyhack.ssh/.MainActivity
```

Manually test at minimum:

1. Start a local shell and run ordinary commands.
2. Confirm `curl --version`, an HTTPS request, SSH, and Mosh work correctly.
3. Create, switch, and close terminal sessions.
4. Put the app in the background and reopen it from its notification.
5. Use the notification Exit action and confirm sessions close.
6. On a clean permission state, confirm Tinyhack SSH shows its explanatory dialog
   before Android's notification permission dialog.
7. Enable Storage Access in Settings: confirm the explanation dialog appears
   before Android's All files access screen, grant access, confirm `~/storage`
   works, then disable it again.
8. Confirm both `playRelease` and `fdroidRelease` merged manifests declare
   `MANAGE_EXTERNAL_STORAGE` exactly once and both show Storage Access.
9. Open Files on a clean permission state. Tap **Allow**, confirm the
   explanation dialog precedes Android's All files access screen, then browse
   phone storage. In a non-sensitive test folder, copy, move, rename, and
   delete files; upload a file and a folder with scp and rsync to a test
   server, download one back, and open an SFTP session. Confirm each transfer
   returns to Files after Enter.
10. Tap **Browse server** for a profile whose key has a passphrase or whose host
    key is unknown: the login opens in a terminal and the browser reopens when
    it succeeds. Open a folder, open a file, rename, delete, create a folder,
    download several items (scp and rsync), and pick an upload destination with
    **Browse…** in the upload dialog. None of these may prompt again. Cancel a
    login (Ctrl-C) and confirm the browser offers **Connect** instead of looping.
11. Open SSH Keys and confirm it is reachable only from inside Tinyhack SSH.
12. Check About, privacy-policy, project, and third-party-license links.
13. Paste short text containing CR, LF, ESC, or DEL: confirm a warning appears,
    Cancel writes nothing, and Paste targets only the original session. Test
    ordinary Unicode text and bracketed paste in Bash.

Host-side security regression checks:

```bash
bash scripts/test-paste-safety.sh
bash scripts/test-remote-transfer.sh
bash scripts/test-remote-browser.sh   # throwaway local sshd; needs openssh-server
bash scripts/audit-cloudflared.sh
cd ghostty
ZIG=../build/toolchains/zig-x86_64-linux-0.17.0/zig  # libghostty-vt 1.0 needs Zig 0.17.x
"$ZIG" build test-lib-vt -Demit-lib-vt=true -Dtarget=x86_64-linux-musl
```

The full upstream suite requires the default Debug configuration (its tracked-pin
tests require slow runtime safety). For optimized security-focused checks:

```bash
"$ZIG" build test-lib-vt -Demit-lib-vt=true -Dtarget=x86_64-linux-musl \
  -Doptimize=ReleaseSafe -Dtest-filter='OSC 99' -Dtest-filter=paste \
  -Dtest-filter=kitty -Dtest-filter='cursor page' -Dtest-filter='wide page'
```

Example non-sensitive storage test:

```bash
mkdir -p ~/storage/Download/Tinyhack SSH-test
printf 'Tinyhack SSH storage test\n' > ~/storage/Download/Tinyhack SSH-test/test.txt
cat ~/storage/Download/Tinyhack SSH-test/test.txt
```

Remove the test folder afterward.

Verify APK packaging and every packaged native ELF:

```bash
BUILD_TOOLS=$(find "$ANDROID_HOME/build-tools" -mindepth 1 -maxdepth 1 \
  -type d | sort -V | tail -1)

"$BUILD_TOOLS/zipalign" -c -P 16 -v 4 \
  app/build/outputs/apk/fdroid/debug/app-fdroid-debug.apk

for elf in app/build/intermediates/stripped_native_libs/fdroidDebug/\
stripFdroidDebugDebugSymbols/out/lib/arm64-v8a/*.so; do
  printf '%s: ' "$(basename "$elf")"
  readelf -lW "$elf" | awk '/ LOAD / {print $NF}' | sort -u
done
```

`zipalign` must report `Verification successful`; all ELF alignment values must
be at least `0x4000` and a multiple of `0x4000`.

## 6. Build and sign the Play AAB

Run:

```bash
scripts/sign-release-bundle.sh
```

This performs a clean `lintPlayRelease` and `bundlePlayRelease`, copies the bundle to
`release/tinyhack-ssh-play-release.aab`, signs it with the upload key, and verifies the
signature with `jarsigner`.

Run an additional verification and record a checksum:

```bash
jarsigner -verify -verbose -certs release/tinyhack-ssh-play-release.aab
sha256sum release/tinyhack-ssh-play-release.aab \
  > release/tinyhack-ssh-play-release.aab.sha256
```

Upload `release/tinyhack-ssh-play-release.aab`, not the unsigned Gradle intermediate.

## 6a. Build and sign the F-Droid/direct-download APK

For an APK distributed from your own website, run:

```bash
scripts/sign-fdroid-apk.sh
sha256sum release/tinyhack-ssh-fdroid-release.apk \
  > release/tinyhack-ssh-fdroid-release.apk.sha256
```

This runs `lintFdroidRelease` and `assembleFdroidRelease`, signs the APK using
the configured release key, and verifies it with `apksigner`. Publish the APK,
its checksum, privacy policy, and corresponding-source archive together.

For inclusion in the official F-Droid repository, submit the source and F-Droid
metadata/build recipe rather than the locally signed APK. F-Droid normally
builds from source and controls the repository signature. Users can switch
between distribution sources without reinstalling only when the installed APKs
share the same application ID and signing certificate.

## 7. Build and publish corresponding source

Tinyhack SSH distributes GPL software including Bash, BusyBox, rsync, and Mosh.
Generate the complete corresponding-source archive for the exact release:

```bash
scripts/bundle-release-source.sh
(cd release && sha256sum -c tinyhack-ssh-1.3.0-source.tar.xz.sha256)
```

The version in the filename follows `versionName`. Publish both files on the
Tinyhack SSH website or release-download page for as long as that binary release is
offered:

```text
release/tinyhack-ssh-1.3.0-source.tar.xz
release/tinyhack-ssh-1.3.0-source.tar.xz.sha256
```

Keep the written source offer at `tinyhack-ssh@tinyhack.com` working. Do not publish
an APK/AAB without also retaining its exact corresponding source.

### UML templates

The app downloads its Debian and Ubuntu templates from GitHub releases tagged
`uml-template-TYPE-HASH`, one per template build. Publish any pinned template
that has no release yet, then check that every pinned file downloads:

```bash
scripts/publish-uml-template.sh all          # skips templates already published
scripts/publish-uml-template.sh --check all  # must print ok for both
```

Each template release carries the image, `SHA256SUMS`, and the image's
corresponding source (`NAME-source.tar`: distribution source packages, the
BusyBox source and configuration, GNU libc sources, and the build scripts).
The releases are not marked latest. Never edit or delete one: released app
versions download the file they pin for as long as they are offered. A new
template gets a new release.

## 8. Publish the website first

Before submitting the Play release, deploy `website/` so these URLs work
without authentication and on mobile browsers:

- `https://tinyhack.com/tinyhack-ssh/`
- `https://tinyhack.com/tinyhack-ssh/privacy.html`
- A stable download URL for the corresponding-source archive

Check that the privacy policy accurately states:

- Tinyhack SSH does not send analytics or data to the developer.
- SSH, Mosh, scp, rsync, and similar connections go directly to endpoints the
  user chooses.
- Profiles, keys, preferences, terminal home files, and debug tokens are stored
  locally.
- All files access is optional, requested only after an in-app explanation,
  and used only for files the user browses, copies or chooses to transfer.
- Contact: `tinyhack-ssh@tinyhack.com`.

## 9. Prepare the Play store listing

Assets are under `store-assets/`:

```text
play-icon-512.png
feature-graphic-1024x500.png
screenshots/phone/01-terminal.png
screenshots/phone/02-settings.png
screenshots/phone/03-about.png
screenshots/phone/04-new-profile.png
```

The listing should prominently explain the app's independent status. Suggested
language:

> Tinyhack SSH is an independent, unofficial Android terminal emulator powered by
> the open-source Ghostty VT library. It is not affiliated with or endorsed by
> the Ghostty project. Tinyhack SSH provides a local GNU/BSD command-line
> environment, SSH and Mosh connections (including Cloudflare Access tunnels
> via the bundled cloudflared client), with secure per-connection controls for
> SSH agent forwarding, terminal clipboard writes, and Kitty graphics.

Add a sentence about the file manager, which is the core purpose behind the All
files access declaration, for example:

> A built-in file manager browses phone storage and your servers side by side:
> copy, move, rename and delete files, and upload or download them with SCP,
> SFTP or rsync over your saved SSH connections.

Add phone screenshots of Files (phone storage) and Browse server next to the
existing ones. Do not claim that Tinyhack SSH is an official Ghostty product.

## 10. Play Console declarations

Complete every applicable item under **Policy and programs > App content**.
Exact names can change in Play Console.

### Storage permission (All files access)

The `playRelease` AAB declares `MANAGE_EXTERNAL_STORAGE`, so Play Console
requires the **All files access** permission declaration before the release can
be reviewed (Policy and programs > App content). Choose the file-manager use
case. Suggested core-purpose text:

> Tinyhack SSH includes a file manager. Users browse, copy, move, rename and
> delete files on shared storage, and transfer the files they choose to or from
> their own servers with SCP, SFTP or rsync (Files > Browse server, Upload to
> server). These operations need to see every folder on shared storage,
> including folders created by other apps, which the Storage Access Framework
> and media permissions cannot provide for arbitrary folder trees and
> command-line tools. Access is requested only after an in-app explanation and
> is never used in the background.

Demonstration video (screen recording of the release build, about one minute):

1. Open the drawer and tap **Files**. Show the **Allow** banner, tap it, show
   the in-app explanation, tap **Continue**, and turn on the system toggle.
2. Return to Files, show phone storage, open a folder such as Download, and
   copy a file into another folder.
3. Select a file, tap **Upload to server**, use **Browse…** to choose the remote
   folder, and run the upload; show it completing.
4. Tap **Browse server**, open the uploaded folder, select a file and download
   it back into phone storage.

To ship a Play build without the permission instead, set
`STORAGE_ACCESS_AVAILABLE` to `false` for `play` in `app/build.gradle` and
remove the permission from `app/src/play/AndroidManifest.xml`; the file manager
then covers the app home only, and the declaration must not be submitted.

### Foreground service

Declare the `specialUse` foreground service accurately. Suggested purpose:

> Tinyhack SSH keeps active local terminal, SSH, and Mosh sessions alive while the
> user temporarily switches to another app or turns off the screen. A persistent
> notification identifies the running sessions and provides an explicit Exit
> action. Without background execution, active interactive connections may be
> disconnected.

Use the same functionality in any foreground-service demonstration video.

### Data safety

Complete Data Safety from the behavior of the exact release, not merely from
the absence of analytics. Account for user-directed network connections and
local handling of profiles, keys, files, and biometric-protected operations.
Tinyhack SSH currently has no developer analytics or developer-operated backend,
but the final answers must follow Play's current definitions for user-initiated
transfers and on-device processing.

Also complete:

- Ads declaration: no ads, if that remains true.
- App access: no login required for local-shell review; provide any special
  instructions needed to test optional SSH/Mosh features.
- Content rating questionnaire.
- Target audience and content.
- Privacy policy URL.
- Government/news/health/financial declarations as applicable.
- Countries/regions, pricing, and contact details.

## 11. Upload and staged testing

Recommended sequence:

1. Create the app in Play Console with package `com.tinyhack.ssh`.
2. Finish the main listing and required App content declarations.
3. Create an Internal testing release.
4. Upload `release/tinyhack-ssh-play-release.aab`.
5. Resolve every manifest, policy, 16 KiB, and pre-launch warning.
6. Install the Play-delivered build from the test track on a clean device.
7. Repeat the terminal, notification, SSH/Mosh, Files, Browse server, and Exit
   tests.
8. Submit the release for review.
9. Retain the uploaded AAB, checksum, source archive, mapping/native-symbol
   outputs if generated, reviewer video, and Play review correspondence.

Do not assume that a locally installed debug APK exactly represents Play's
split APKs. Always test the Play-delivered build before production rollout.

## 12. Distribution isolation

Keep `MANAGE_EXTERNAL_STORAGE` in the flavor manifests
(`app/src/play/AndroidManifest.xml` and `app/src/fdroid/AndroidManifest.xml`),
not in `src/main`, so either distribution can drop it independently. Before
every release, inspect both merged manifests and test the corresponding Files
and Settings UI. Keep Play listing text and screenshots free of features that
exist only in the F-Droid/direct build.

## Final release checklist

- [ ] Intended commit/tag selected and working tree reviewed
- [ ] `versionCode` incremented and `versionName` correct
- [ ] Upload key and `.env.release` backed up and protected
- [ ] Native LOAD alignment is at least 16 KiB and a multiple of 16 KiB
- [ ] Both flavor lint/builds succeed
- [ ] F-Droid debug APK installed and full device smoke test passes
- [ ] Notification pre-permission disclosure tested
- [ ] Storage Access enable/use/disable flow tested
- [ ] Files Allow flow, local operations and remote browser tested
- [ ] Play and F-Droid release manifests each contain `MANAGE_EXTERNAL_STORAGE` once
- [ ] Signed Play AAB and direct-download APK verify successfully
- [ ] AAB SHA-256 checksum saved
- [ ] Corresponding-source archive generated and checksum verified
- [ ] Website, privacy policy, and source download are publicly reachable
- [ ] Store listing includes independent/unofficial statement
- [ ] Store images uploaded
- [ ] Foreground-service declaration completed
- [ ] All files access declaration and demonstration video submitted
- [ ] Data Safety and all other App content forms completed
- [ ] Play internal-test build installed and tested
- [ ] Release artifacts and review records archived securely
