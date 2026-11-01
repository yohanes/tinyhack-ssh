# Named UML environments

Tinyhack SSH 1.1.0 provides `uml-env` in its Bash local shells. Each
named environment has an independent writable Debian disk, user account,
console log, and temporary directory. Existing `~/uml/debian.ext4` and the
original BusyBox experiment are preserved.

## Install

Install Tinyhack SSH and open a Local Shell. The UML kernel and compressed
Debian template are bundled in the app; ADB and a separate template download
are not required. UML remains an experimental feature.

## Rebuild from source

Build the UML kernel/network helpers and experimental app as described in
[README.md](README.md), then build and install the environment template:

```sh
scripts/build-uml-env-template.sh
scripts/package-uml-debug.sh
adb install -r build/uml/tinyhack-uml-debug.apk
scripts/install-uml-env.sh
```

The template builder reuses the pinned Debian export and static BusyBox from
`scripts/build-uml-debian.sh`, invoking that builder if they are missing.
The template is a compressed 256 MB ext4 filesystem (about 32 MB compressed);
it is distinct from the
4 GB standalone Debian image. Installing a template never replaces an existing
environment. Open a new Local Shell after updating the app.

## Create and enter

```sh
uml-env create -t debian -n devenv -d 1024M -u user
uml-env create -t debian -n workenv -d 2G -u developer
uml-env list
uml-env start devenv
```

Defaults: Debian, 1024M disk, username `user`. The name is required.
Long options are `--type`, `--name`, `--disk-size`, and `--user`.
Only Debian is supported currently. Disk sizes are integer M/G values from
512M through 1024G. Files are sparse: the requested size is the guest's maximum
backing disk size, while Android storage is consumed as data is written.
Filesystem metadata and reserved blocks reduce usable capacity.
The bundled sparse-image helper skips zero-filled template blocks while
extracting and extends the file with `ftruncate`; creation never writes the
requested disk capacity as zeros. Check actual host allocation with
`du -h ~/uml/envs/devenv/disk.ext4` (ordinary `ls -l` shows logical capacity).

Creation boots the template, downloads sudo and e2fsprogs from the signed Debian
repositories, expands ext4 to the requested size, creates the user, and shuts
down. Internet access is required for this first setup. Subsequent starts do
not reinstall packages or require internet access just to log in.

The selected user logs in automatically and has **passwordless sudo** inside
this app-private guest. This does not grant Android root access. For example:

```sh
sudo apt-get update
sudo apt-get install git vim-tiny
vi ~/my-config
sudo poweroff
```

Use `sudo poweroff` to shut down cleanly before closing the session. Files,
packages, and user settings persist across starts. The same environment cannot
be started twice concurrently; different environments use separate disks.
To change guest RAM, run `UML_MEMORY=1G uml-env start devenv` (default: 512M).
Debian package hardware/kernel/service limitations remain as described in the
[Debian instructions](debian/README.md).

## Local profiles

In **Manage Profiles → Add Profile**, select **LOCAL**, then choose the name in
**Local environment**. Save and connect. **Android shell** retains the usual
local-shell behavior. A saved environment selection survives app restarts and
profile export/import. Create environments before opening the profile editor.

## Storage and troubleshooting

Environments live in `~/uml/envs/NAME/`: `disk.ext4`, `user`, `type`, `ready`,
`console.log`, `run.lock`, and `tmp/`. Templates live in `~/uml/templates/`.
Back up a disk only after shutting down its guest. Reusing a name is rejected
so an existing disk cannot be overwritten by `create`.

Failed creation retains its disk and log for diagnosis and does not appear in
`list` or the profile picker. Inspect `console.log`, resolve the problem, and
create with a new name. You may remove the failed directory after verifying
that its guest has stopped. Never delete or replace a running guest's disk.

The bundled template lives under `~/../usr/share/uml/` with a content-addressed
filename. An explicitly installed `~/uml/templates/debian.ext4.gz` takes
precedence for development. The `.gzip` suffix prevents Android's asset packager
from automatically expanding the compressed image.

The interactive command is a Bash function that invokes a script through the
bundled interpreter, as required by Android's executable-file restrictions.
For noninteractive automation, use:

```sh
bash "$HOME/../usr/libexec/uml-env" list
```

Host-side tests: `scripts/test-uml-env.sh` and
`scripts/test-uml-sparse-image.sh`. Device test
results are recorded in [env-results](env-results/validation.md).
