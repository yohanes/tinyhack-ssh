# Tinyhack SSH deployment guide

This runbook describes the current first-release process for Google Play. The
current build intentionally requests `MANAGE_EXTERNAL_STORAGE`. Google Play
must approve that permission before the app can be published with it.

Tinyhack SSH is an independent, unofficial Android port using the open-source
Ghostty VT library. It is not affiliated with or endorsed by the Ghostty
project. Keep that statement in the store listing, website, and About screen.

## 1. Release prerequisites

Install or verify:

- OpenJDK 17
- Android SDK at `/home/yohanes/Android/Sdk`
- Android NDK `26.1.10909125`
- Gradle 8.13 at `/home/yohanes/apps/gradle-8.13/bin/gradle`
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
versionCode 1
versionName "1.0.0"
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

Run this for the first release and whenever Bash, BusyBox, rsync, OpenSSH,
OpenSSL, Mosh, protobuf, ncurses, zlib, the NDK, or their build flags change:

```bash
JOBS="$(nproc)" scripts/rebuild-userland-16kb.sh
```

The script rebuilds the bundled executables and verifies their ELF LOAD
alignment. Every line in the final report must say `OK` with `0x4000`.

The Gradle CMake build applies the same 16 KiB linker setting to
`libghostty-android.so` and `libaskpass.so`.

Do not continue if any native file reports `0x1000`, `0x2000`, or `FAIL`.

## 5. Build and test on the connected device

Build lint and the debug APK:

```bash
export ANDROID_HOME=/home/yohanes/Android/Sdk
/home/yohanes/apps/gradle-8.13/bin/gradle clean lintDebug assembleDebug
```

Install and start it:

```bash
adb -s 59HYAIAECAIFL7HM install -r \
  app/build/outputs/apk/debug/app-debug.apk
adb -s 59HYAIAECAIFL7HM shell am force-stop com.tinyhack.ssh
adb -s 59HYAIAECAIFL7HM shell am start -W \
  -n com.tinyhack.ssh/.MainActivity
```

Manually test at minimum:

1. Start a local shell and run ordinary commands.
2. Confirm SSH and Mosh start correctly.
3. Create, switch, and close terminal sessions.
4. Put the app in the background and reopen it from its notification.
5. Use the notification Exit action and confirm sessions close.
6. On a clean permission state, confirm Tinyhack SSH shows its explanatory dialog
   before Android's notification permission dialog.
7. Open Settings, enable Storage Access, grant All files access in Android
   Settings, and confirm `~/storage` works from the terminal.
8. Disable Storage Access and confirm the symlink is removed/disabled.
9. Open SSH Keys and confirm it is reachable only from inside Tinyhack SSH.
10. Check About, privacy-policy, project, and third-party-license links.

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
  app/build/outputs/apk/debug/app-debug.apk

for elf in app/build/intermediates/stripped_native_libs/debug/\
stripDebugDebugSymbols/out/lib/arm64-v8a/*.so; do
  printf '%s: ' "$(basename "$elf")"
  readelf -lW "$elf" | awk '/ LOAD / {print $NF}' | sort -u
done
```

`zipalign` must report `Verification successful`; all ELF alignment values must
be `0x4000`.

## 6. Build and sign the Play AAB

Run:

```bash
scripts/sign-release-bundle.sh
```

This performs a clean `lintRelease` and `bundleRelease`, copies the bundle to
`release/tinyhack-ssh-release.aab`, signs it with the upload key, and verifies the
signature with `jarsigner`.

Run an additional verification and record a checksum:

```bash
jarsigner -verify -verbose -certs release/tinyhack-ssh-release.aab
sha256sum release/tinyhack-ssh-release.aab \
  > release/tinyhack-ssh-release.aab.sha256
```

Upload `release/tinyhack-ssh-release.aab`, not the unsigned Gradle intermediate.

## 7. Build and publish corresponding source

Tinyhack SSH distributes GPL software including Bash, BusyBox, rsync, and Mosh.
Generate the complete corresponding-source archive for the exact release:

```bash
scripts/bundle-release-source.sh
sha256sum -c release/tinyhack-ssh-1.0.0-source.tar.xz.sha256
```

The version in the filename follows `versionName`. Publish both files on the
Tinyhack SSH website or release-download page for as long as that binary release is
offered:

```text
release/tinyhack-ssh-1.0.0-source.tar.xz
release/tinyhack-ssh-1.0.0-source.tar.xz.sha256
```

Keep the written source offer at `tinyhack-ssh@tinyhack.com` working. Do not publish
an APK/AAB without also retaining its exact corresponding source.

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
- Storage Access is optional and user-enabled.
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

The listing should prominently explain both the app's independent status and
why shared-storage management is a core user-facing terminal feature. Suggested
language:

> Tinyhack SSH is an independent, unofficial Android terminal emulator powered by
> the open-source Ghostty VT library. It is not affiliated with or endorsed by
> the Ghostty project. Tinyhack SSH provides a local GNU/BSD command-line
> environment, SSH and Mosh connections, and user-enabled access for terminal
> tools to create, inspect, edit, copy, synchronize, and manage files in shared
> phone storage.

Do not claim that Tinyhack SSH is an official Ghostty product. Do not describe it as
a general-purpose file manager unless the UI and listing genuinely make that a
core purpose.

## 10. Play Console declarations

Complete every applicable item under **Policy and programs > App content**.
Exact names can change in Play Console.

### All files access

The uploaded AAB declares:

```text
android.permission.MANAGE_EXTERNAL_STORAGE
```

Complete the All files access / Permissions Declaration Form. The declaration
must match the actual app and listing. A concise starting explanation is:

> Tinyhack SSH is a standalone terminal environment with bundled native GNU/BSD
> command-line programs. A core feature is user-directed creation, inspection,
> editing, copying, synchronization, and management of shared-storage files
> using Bash, BusyBox, rsync, scp, tar, find, and similar pathname-based tools.
> These native POSIX tools require filesystem paths and directory traversal and
> cannot operate on Storage Access Framework content URIs. Access is disabled
> by default, enabled explicitly by the user, used only for commands the user
> initiates, and never used for analytics or automatic developer uploads.

Prepare a short review video showing:

1. Launching Tinyhack SSH.
2. Opening Settings.
3. Enabling Storage Access.
4. The Android All files access settings page.
5. Returning to Tinyhack SSH.
6. Creating and reading a harmless file under `~/storage/Download`.
7. Disabling Storage Access.

Use a clean test device/profile with no private filenames, hosts, keys, or
notifications visible. Provide reviewer instructions describing the same path.

Google limits this permission to approved core uses and exceptions. Approval is
not guaranteed. Current policy:

<https://support.google.com/googleplay/android-developer/answer/10467955>

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
4. Upload `release/tinyhack-ssh-release.aab`.
5. Resolve every manifest, policy, 16 KiB, and pre-launch warning.
6. Install the Play-delivered build from the test track on a clean device.
7. Repeat the terminal, notification, SSH/Mosh, Storage Access, and Exit tests.
8. Submit the permission declaration and release for review.
9. Retain the uploaded AAB, checksum, source archive, mapping/native-symbol
   outputs if generated, reviewer video, and Play review correspondence.

Do not assume that a locally installed debug APK exactly represents Play's
split APKs. Always test the Play-delivered build before production rollout.

## 12. If All files access is rejected

Do not repeatedly resubmit unchanged declarations. Save the rejection text and
identify whether Google rejected the explanation, listing, demonstration, or
the use case itself.

If the use case is ineligible:

1. Remove `MANAGE_EXTERNAL_STORAGE` from the Play manifest.
2. Hide/disable the current raw-path Storage Access toggle.
3. Increase `versionCode`.
4. Publish a build without shared-storage access, or implement a SAF-based
   choose-folder plus import/export workflow.
5. Update the listing, privacy policy, Data Safety, and permission declaration
   so they describe the new build exactly.

The website-distributed build can retain All files access, but that should be
implemented as a separate build flavor before maintaining two distributions.

## Final release checklist

- [ ] Intended commit/tag selected and working tree reviewed
- [ ] `versionCode` incremented and `versionName` correct
- [ ] Upload key and `.env.release` backed up and protected
- [ ] Native rebuild reports only `0x4000`
- [ ] Debug lint/build succeeds
- [ ] Debug APK installed and full device smoke test passes
- [ ] Notification pre-permission disclosure tested
- [ ] Storage Access enable/use/disable flow tested
- [ ] Signed release AAB verifies successfully
- [ ] AAB SHA-256 checksum saved
- [ ] Corresponding-source archive generated and checksum verified
- [ ] Website, privacy policy, and source download are publicly reachable
- [ ] Store listing includes independent/unofficial statement
- [ ] Store images uploaded
- [ ] All files access declaration and review video prepared
- [ ] Foreground-service declaration completed
- [ ] Data Safety and all other App content forms completed
- [ ] Play internal-test build installed and tested
- [ ] Release artifacts and review records archived securely
