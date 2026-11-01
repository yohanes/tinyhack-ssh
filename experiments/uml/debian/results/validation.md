# Debian validation, 2026-11-01

Verified through the experimental terminal app on Moto G45 / Android 15:

- Booted Debian 13.7 ARM64 with UML, a writable ext4 disk, and BusyBox PID 1.
- `apt-get update` downloaded and verified 10.1 MB of repository indexes.
- `apt-get install -y vim-tiny ca-certificates jq python3` completed, including
  dependencies, maintainer scripts, certificate generation, and dpkg triggers.
- `jq --version`: jq-1.7. `python3 --version`: Python 3.13.5.
- `dpkg --audit` produced no findings.
- Edited and saved a file using the initial BusyBox vi.
- After installing vim-tiny, edited `/root/debian-editor-test.conf` through
  `vi`; reading it printed `UML_DEBIAN_EDITOR_OK=1`.
- `poweroff` ran shutdown hooks, remounted ext4 read-only, and exited UML.

Raw evidence: `first-install-console.txt` (terminal control sequences retained).

The local build script was rerun successfully after fixes. The final seed passes
`e2fsck -fn`, gzip integrity verification, and checksum verification. Fixes include
using tty0 for the login console and removing Docker's /dev placeholders before
creating device nodes, avoiding duplicate console entries in the ext4 directory.

## Reboot and final clean seed verification

Recovered the stopped disk over the local network after bulk USB transfers
failed. Verified transfer checksums, repaired the older disk's /dev entries,
and applied the tty0 console fix. Kept a local backup of the original disk.
Rebooting the corrected disk preserved jq, Python, and the saved editor file;
`dpkg --audit` remained clean and `tty` returned `/dev/tty0` without bash
job-control warnings. Evidence: `persistence-console.txt`, `persistence.png`,
and `device-fsck.txt`.

Transferred the final clean seed with SHA-256
`0c53783f0b780ac70b9848b8690d8d62e7f394a0f2f119ccddfd32e466d67a13`
and verified the checksum on the phone before decompression. Booted it as a
separate disk in the experimental app, then verified:

- `apt-get update && apt-get install -y hello` completed successfully.
- The installed `hello` command ran successfully.
- Built-in BusyBox vi saved `/root/clean-seed.conf`; reading the file returned
  `DEBIAN_CLEAN_SEED_OK=1`.
- `dpkg --audit` produced no findings; `tty` returned `/dev/tty0`.
- The root filesystem had 3.8 GB available.
- `poweroff` remounted the disk read-only and exited; no UML or passt processes
  remained on the phone.

Evidence: `clean-seed-console.txt`, `clean-seed.png`, `seed-fsck.txt`, and
`SHA256SUMS`. Logs retain terminal control sequences. The default on-device
`debian.ext4` is the corrected persistent disk with the optional packages;
the downloadable seed remains minimal. Package installation uses normal Debian
repositories without an allowlist; these tests do not imply every package's
hardware, kernel, or service requirements are supported.
