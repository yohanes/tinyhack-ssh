# Named environment validation — 2026-11-01

## Console wakeup regression — 2026-11-02

On Moto G45 / Android 15, the 1.1.0 kernel sometimes left the final typed byte
waiting until another keypress. An isolated ADB PTY test reproduced five stalls
in 150 bursts, without involving the Android renderer. Guest Bash under strace
also reproduced it: after displaying `ls abc0044xy`, its blocking `pselect6`
did not finish until another space arrived; Bash then read `z` and the space
and displayed both. See [the trace excerpt](console-wakeup-trace.txt).

Sending UML SIGIO, without supplying any input, also immediately released the
queued final byte. The host tty can deliver SIGIO before its poll wakeup puts
the event on UML's epoll ready list. The handler drains an empty list and the
later ready event has no signal left to wake the guest.

`patches/uml-console-wakeup.patch` makes the existing SIGIO helper watch the
IRQ epoll descriptor on bionic. Its wakeup is therefore ordered after the IRQ
ready event, with no periodic polling. The normal runtime was rebuilt and the
separate UML debug APK updated in place, preserving existing environments.

The patched kernel passed 1,000 bursts at 2 ms between bytes with **zero
stalls**, versus the one-second failure threshold. Maximum time waiting for
the completed echo after sending a burst was 61 ms. See
[the result](console-wakeup-fixed.json). Reproduce with:

```sh
uv run scripts/test-uml-console.py releasecheck --adb /usr/bin/adb \
  --count 1000 --label epoll-fix-2ms
```

Also booted the same environment through the app UI, typed `ls abc`, confirmed
the full line appeared without another key, and powered off cleanly. The main
release app was not replaced during these tests.

## Environment creation and persistence

Tested through the experimental app's terminal on Moto G45 / Android 15.

- Created `devenv` with `-t debian -d 1024M -u user`; logged in as UID 1000
  with group `sudo`. `sudo -n id` returned root.
- Created separate 512 MB and 1 GB environments with `alice` and `developer`.
  Guest `df` confirmed their distinct capacities. Alice's environment did not
  contain the file created in the user's environment (`ISOLATED_OK`).
- Installed and ran Debian `hello` as the non-root developer using sudo.
- Saved `UML_ENV_PERSIST_OK` in the user's home, powered off, and reopened it
  through a newly created LOCAL profile with `devenv` selected. The file survived.
- Reinstalled the experimental APK, then launched the saved `Debian-dev`
  profile again. Its environment choice, user, sudo access, and file persisted.
- A second start against the running environment failed with
  `This environment is already running.`
- Unsupported image types, unsafe names, root/invalid usernames, invalid or
  undersized disks, missing arguments, and missing environments were rejected.
- The final template sets the hostname with Debian's hostname executable;
  the final tests have no sudo hostname-resolution warnings. Early logs record
  the issue found and fixed during development.

## Sparse images

The final CLI uses the bundled `libuml-sparse-image.so` helper to skip zero
blocks during extraction and extend with `ftruncate`. On the phone, a newly
created 1 GiB image had 256328 allocated 512-byte blocks (about 125 MiB) after
package installation: see `sparse-allocation.txt`. It booted as `user`, allowed
sudo, and saved `SPARSE_PERSIST_OK` in the user's home. Creation and boot logs
are `sparse-create.txt` and `sparse-start.txt`.
`sparse-persistence.txt` confirms the saved file survived shutdown and reboot.
All guests were shut down cleanly; no UML or passt processes remained.

The template SHA-256 was checked on the phone:
`05a1e473306ab6ed65c56134e39e6f1124e01cf93adc6f9df6673ba0a337e8e8`.

## Checks and evidence

Memory configuration was tested on the connected Moto G45 (Android 15) on
2026-11-02 using the experimental debug app. A saved Local/UML profile with
4096 MiB retained its value when reopened and booted with `MemTotal: 4166192 kB`.
The profile dialog rejected zero. Separately, `uml-env config releasecheck
--memory 2G` persisted `2048M`, and a CLI start without an override reported
`MemTotal: 2079280 kB`. Both guests reported CPU `0` as the only online CPU and
were powered off cleanly. Local evidence is in
`build/uml/memory-profile-4g.log`, `build/uml/memory-profile.png`, and
`build/uml/memory-config-2g.log`. The build and lint passed, as did CLI validation
for memory bounds, malformed input, and corrupt saved configuration.

- `scripts/test-uml-env.sh`: passed input validation and ready/incomplete listing.
- `scripts/test-uml-sparse-image.sh`: passed content equality, logical capacity,
  allocated-block bound, refusal to overwrite, oversized input, and empty input.
- Experimental APK build and `lintPlayDebug`: passed.
- `git diff --check` and shell syntax checks: passed.

Raw terminal logs retain control sequences. `profile-after-update.txt` records
profile persistence; `final-create.txt` and `final-start.txt` record the final
512 MB template test (subsequently renamed from `finaltest` to `second`).
`workenv-packages.txt` records sudo package installation. Screenshots accompany
the logs. Tests use Debian's normal repository selection; packages requiring
other kernel, hardware, or service capabilities may need additional setup.
