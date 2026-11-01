# Named environment validation — 2026-11-01

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
