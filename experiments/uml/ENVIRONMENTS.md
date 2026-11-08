# Named UML environments

Tinyhack SSH 1.1.0 provides `uml-env` in its Bash local shells. Each
named environment has an independent writable Debian or Ubuntu disk, user
account, console log, and temporary directory. Existing `~/uml/debian.ext4` and
the original BusyBox experiment are preserved. The **Linux environments**
screen in the drawer manages the same environments without typing commands.

## Install

Install Tinyhack SSH and open a Local Shell. The UML kernel is bundled in the
app. The compressed Debian 13 and Ubuntu 26.04 LTS templates are published as
GitHub release assets (`uml-template-TYPE-HASH`); `uml-env create` downloads
the one it needs the first time, checks it against the SHA-256 pinned in
`uml-env`, and keeps it in `~/uml/cache/`. ADB is not required. UML remains an
experimental feature.

## Rebuild from source

Build the UML kernel/network helpers and experimental app as described in
[README.md](README.md), then build and install the environment template:

```sh
scripts/build-uml-shim.sh                      # boot shim (after the kernel)
scripts/build-uml-env-template.sh              # Debian template
scripts/build-uml-env-template.sh ubuntu
scripts/package-uml-debug.sh
adb install -r build/uml/tinyhack-uml-debug.apk
scripts/install-uml-env.sh
```

The Debian template builder reuses the pinned Debian export and static BusyBox
from `scripts/build-uml-debian.sh`, invoking that builder if they are missing.
The Ubuntu template is exported from the official arm64 `ubuntu` image pinned by
digest (Docker required). The builder prints the template's `template_info()`
entry (content-addressed file name, SHA-256, size); pin it in
`assets/usr/libexec/uml-env`. Retain exact source packages with
`uv run scripts/fetch-debian-template-sources.py [debian|ubuntu]`, then publish
the pinned files with `scripts/publish-uml-template.sh TYPE` (see DEPLOY.md).
Before publishing, test against a local copy: `scripts/publish-uml-template.sh
--stage all` lays out `build/uml/publish/` like the release downloads; serve it
with an HTTP server that supports range requests and run `uml-env create` with
`UML_TEMPLATE_URL=http://HOST:PORT`.
The template is a compressed 256 MB ext4 filesystem (32–40 MB compressed);
it is distinct from the
4 GB standalone Debian image. Installing a template never replaces an existing
environment. Open a new Local Shell after updating the app.

## Create and enter

```sh
uml-env create -t debian -n devenv -d 1024M -u user
uml-env create -t ubuntu -n workenv -d 4G -u developer --memory 1G
uml-env list
uml-env status          # all environments, tab-separated, for scripts
uml-env start devenv
```

Defaults: Debian, 1024M disk, username `user`, home sharing on. The name is
required. Long options are `--type`, `--name`, `--disk-size`, `--user`,
`--memory`, and `--share-home on|off`. Types are `debian` (Debian 13 trixie)
and `ubuntu` (Ubuntu 26.04 LTS resolute). Disk sizes are integer M/G values from
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
Closing the console terminates the guest and releases its lock; it does not
detach a session that can later be reattached. Prefer `sudo poweroff` so guest
applications and filesystems can finish their writes.
To save guest RAM for future starts, run:

```sh
uml-env config devenv --memory 4G
uml-env config devenv                  # show saved memory
uml-env start devenv
```

Memory accepts whole-number `M` (MiB) or `G` (GiB), from 256 MiB to 64 GiB;
the initial default is 512 MiB. Leave RAM for Android and other apps. Changes
apply on the next guest start; they do not resize a running guest or its disk.
`uml-env create ... --memory 4G` also saves memory at creation time.
For one boot, use `uml-env start devenv --memory 2G` or the existing
`UML_MEMORY=2G uml-env start devenv`. Precedence is explicit `--memory`, then
`UML_MEMORY`, then the environment's saved memory, then 512 MiB.

In a LOCAL profile, selecting a Debian environment shows **Memory (MiB)**.
For example, enter `4096` for 4 GiB or `8192` for 8 GiB. This profile override
is saved and included in profile exports. Leave it blank to inherit the
environment setting (or `UML_MEMORY` in the profile's environment variables).

The current bundled kernel provides **one virtual CPU per environment**
(`CONFIG_SMP` disabled, `CONFIG_NR_CPUS=1`). Guest programs may use multiple
threads, but those threads share one CPU. The ARM64 UML source supports SMP
in its seccomp execution mode; the ptrace mode used by the tested Android
build forces one CPU even in an SMP-enabled kernel. A CPU-count option alone
would therefore not enable parallel execution in this release.
Debian package hardware/kernel/service limitations remain as described in the
[Debian instructions](debian/README.md).

## Linux environments screen

Open the drawer and tap **Linux environments**. Each card shows the system,
user, saved memory, maximum disk size and the storage it actually uses, whether
the app home is shared, and whether the guest is running.

- **+** creates an environment: choose Debian or Ubuntu, a name, user, disk
  size, memory, and home sharing. Creation runs in a terminal session (it needs
  internet) and returns to the list when you press Enter.
- **Start** opens the guest console in a new session. After `sudo poweroff`
  the app returns to the list.
- **Settings** changes memory and home sharing for the next start.
- **⋮** views the console log, adds a LOCAL connection profile for one-tap
  access from the drawer, or deletes the environment. Deleting asks for
  confirmation and is refused while the guest runs.

An environment whose creation failed is listed as **Creation did not finish**;
view its log, then delete it.

## Shared app home at /data

With sharing on (the default), the app home folder, the Android shell's `~`,
is mounted at `/data` inside the guest. Files there appear owned by the
environment user, so ordinary commands read and write them without sudo, and
changes are visible to the Android shell, the file manager, and transfers right
away. Changing a file's owner there is refused (copying with `cp -a` or
`rsync -a` still works). The mount uses the UML hostfs filesystem; file
permissions are those of the app's Android account.

The home also contains `uml/`, including the environment disks themselves. Do
not modify a running environment's `uml/envs/NAME/disk.ext4` through `/data`.

```sh
uml-env config devenv --share-home off   # takes effect on the next start
```

If the disk already has a non-folder at `/data`, the share is skipped with a
message on the console. Creation itself never mounts `/data`.

## DNS

Guests resolve names through Android, so they see the same names the phone
does, including local network names served by the router (`getent hosts nas`)
and multicast DNS names (`nas.local`). `/etc/resolv.conf` names `10.0.2.3`, a
nameserver that exists only inside the network launcher: it passes each query
to Android's resolver, which follows Wi-Fi, mobile data, VPN and Private DNS
changes while the guest runs. DNS over TCP and `ping 10.0.2.3` are not
answered.

The boot shim switches disks that still name the old `1.1.1.1` default (or have
no `resolv.conf`) to `10.0.2.3` at each start. Any other content is yours and
is kept: to use a public resolver instead, write it with a comment line, for
example `printf '# mine\nnameserver 1.1.1.1\n' | sudo tee /etc/resolv.conf`.
If lookups fail, the console shows `umnet: DNS lookup failed: ...` once per
kind of error; Android blocks an app's network while the phone sleeps unless
the app is in the foreground or runs its terminal service.

## Boot shim

Each start passes a small initramfs (`~/../usr/share/uml/shim.cpio`, built by
`scripts/build-uml-shim.sh`) to the kernel. It mounts the environment disk,
adds `/data`, updates the default nameserver (see DNS), and runs the disk's
own `/init` with `switch_root`, so disks
created by earlier releases boot unchanged and gain the share without being
recreated. If the shim cannot mount the disk it prints `UML_SHIM_FAILED` and
powers off.

## Local profiles

In **Manage Profiles → Add Profile**, select **LOCAL**, then choose the name in
**Local environment**. Save and connect. **Android shell** retains the usual
local-shell behavior. A saved environment selection survives app restarts and
profile export/import. Create environments before opening the profile editor.

## Storage and troubleshooting

Environments live in `~/uml/envs/NAME/`: `disk.ext4`, `user`, `type`, `ready`,
`memory`, `share-home`, `console.log`, `run.lock`, and `tmp/`. Downloaded templates live in
`~/uml/cache/`; delete them to free space (the next creation downloads again).
Back up a disk only after shutting down its guest. Reusing a name is rejected
so an existing disk cannot be overwritten by `create`.

Failed creation retains its disk and log for diagnosis and does not appear in
`list` or the profile picker. Inspect `console.log`, resolve the problem, and
create with a new name. Remove it with `uml-env delete NAME` (or the screen's
Delete), which refuses while the guest runs. Never delete or replace a running
guest's disk by hand.

Downloaded templates have content-addressed filenames; a template update
removes the cached older version of that type when it downloads the new one,
and an interrupted download resumes on the next attempt. Versions up to 1.3.0
bundled the templates in `~/../usr/share/uml/`; the app removes those after an
update. An explicitly installed `~/uml/templates/TYPE.ext4.gz` (for example
`debian.ext4.gz`) takes precedence for development.

The interactive command is a Bash function that invokes a script through the
bundled interpreter, as required by Android's executable-file restrictions.
For noninteractive automation, use:

```sh
bash "$HOME/../usr/libexec/uml-env" list
```

Host-side tests: `scripts/test-uml-env.sh` and
`scripts/test-uml-sparse-image.sh`. On a device, `uv run
scripts/test-uml-share-home.py NAME` checks `/data` sharing on a stopped,
disposable environment of the UML debug app (it boots it twice), and `uv run
scripts/test-uml-dns.py NAME --lan-name HOST --mdns-name HOST.local` checks DNS
(internet, local network and `.local` names, NXDOMAIN, fragmented large
answers, parallel lookups). Guests started through `adb shell run-as` share
the app's network access: keep the phone awake and the app in the foreground,
or Android blocks it. Device test
results are recorded in [env-results](env-results/validation.md).

Console wakeup regression on a connected device (use a stopped, disposable
environment in the separate UML debug app, and launch that app once after
installing its APK):

```sh
uv run scripts/test-uml-console.py releasecheck --count 1000
uv run scripts/test-uml-close.py releasecheck
```

This boots the environment through ADB under the app UID, types rapid bursts
without executing them, checks the complete echo before sending another key,
and powers the guest off. Logs and JSON results go to `build/uml/`. It exercises
the console independently of the Android renderer and does not create a disk.
The close regression disconnects the console twice and verifies that each
subsequent start reaches the guest shell, then powers off the final guest.
