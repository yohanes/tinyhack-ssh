# /// script
# dependencies = []
# ///
"""On-device test of the boot shim and /data sharing: uv run scripts/test-uml-share-home.py ENVIRONMENT.

Use a stopped, disposable environment in the UML debug app (launch the app once
after installing it so its bundled files are current). Boots the environment
twice through ADB under the app UID:

1. With sharing on: /data is the app home, owned by the environment user;
   files written there by the guest user appear on Android; chown to another
   user is refused while a no-op chown (cp -a) works; sudo can write too.
2. With sharing off: /data is not a mount point.

The environment's setting is restored afterwards. Logs go to build/uml/.
"""

import argparse
import os
from pathlib import Path
import select
import shlex
import subprocess
import time

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("environment")
parser.add_argument("--adb", default="adb")
parser.add_argument("--package", default="com.tinyhack.ssh.uml")
args = parser.parse_args()

output = Path(__file__).resolve().parent.parent / "build/uml"
output.mkdir(parents=True, exist_ok=True)
home = f"/data/user/0/{args.package}/files/home"
env_prefix = (
    f"cd {shlex.quote(home)} && export HOME={shlex.quote(home)} "
    f"PATH={shlex.quote(home + '/../usr/bin')}:/system/bin TERM=xterm-256color && "
)
log = (output / "share-home.log").open("wb", buffering=0)
failures = []


def host(command):
    """Run a command as the app user on the phone; returns stdout."""
    full = env_prefix + command
    result = subprocess.run([args.adb, "shell", "run-as", args.package, "sh", "-c", shlex.quote(full)],
                            capture_output=True, text=True, timeout=60)
    log.write(f"$ {command}\n{result.stdout}{result.stderr}".encode())
    return result.stdout


def uml_env(*words):
    return host("bash ../usr/libexec/uml-env " + " ".join(shlex.quote(w) for w in words))


def check(ok, what):
    print(("ok   " if ok else "FAIL ") + what, flush=True)
    if not ok:
        failures.append(what)


class Guest:
    def __init__(self, name):
        command = env_prefix + f"exec bash ../usr/libexec/uml-env start {shlex.quote(name)}"
        self.process = subprocess.Popen(
            [args.adb, "shell", "-tt", "run-as", args.package, "sh", "-c", shlex.quote(command)],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        self.counter = 0

    def send(self, data):
        self.process.stdin.write(data)
        self.process.stdin.flush()

    def receive(self, marker, timeout):
        data = bytearray()
        deadline = time.monotonic() + timeout
        while (remaining := deadline - time.monotonic()) > 0:
            ready, _, _ = select.select([self.process.stdout], [], [], remaining)
            if not ready:
                break
            chunk = os.read(self.process.stdout.fileno(), 65536)
            if not chunk:
                break
            data.extend(chunk)
            log.write(chunk)
            if marker in data:
                return bytes(data), True
        return bytes(data), False

    def boot(self):
        _, ok = self.receive(b"\x1b[00m$ ", 90)
        if not ok:
            raise RuntimeError("No guest shell prompt; inspect build/uml/share-home.log")
        self.send(b"PS1='> '; stty -echo\r")
        self.receive(b"> ", 5)

    def run(self, command, timeout=20):
        """Run a guest shell command; returns (exit status, output)."""
        self.counter += 1
        start, end = f"@@S{self.counter}@@", f"@@E{self.counter}@@"
        self.send(f"echo {start}; {command}; echo {end}$?\r".encode())
        data, ok = self.receive(end.encode() + b"0", timeout)
        if not ok:
            more, _ = self.receive(b"> ", 3)
            data += more
        text = data.decode(errors="replace").replace("\r", "")
        body = text.split(start + "\n", 1)[-1]
        out, _, rest = body.partition(end)
        status = int("".join(c for c in rest[:3] if c.isdigit()) or "255")
        return status, out.strip()

    def poweroff(self):
        self.send(b"sudo poweroff\r")
        _, ok = self.receive(b"reboot: Power down", 40)
        self.process.stdin.close()
        self.process.wait(timeout=15)
        return ok


name = args.environment
original = "on" if "Share home at /data: on" in uml_env("config", name) else "off"
user = host(f"cat uml/envs/{shlex.quote(name)}/user").strip()
marker = f"uml-share-test-{os.getpid()}"
try:
    uml_env("config", name, "--share-home", "on")
    guest = Guest(name)
    guest.boot()
    status, mounts = guest.run("grep ' /data ' /proc/mounts")
    check(status == 0 and "hostfs" in mounts, f"/data is a hostfs mount ({mounts!r})")
    status, owner = guest.run("stat -c '%U:%G %a' /data")
    check(status == 0 and owner.startswith(f"{user}:{user} "), f"/data owned by {user} ({owner!r})")
    status, listing = guest.run("ls -1 --color=never /data")
    check(status == 0 and "uml" in listing.split(), "app home content visible (uml folder)")
    status, _ = guest.run(f"echo written-by-guest > /data/{marker}.txt")
    check(status == 0, "guest user can create a file in /data")
    status, _ = guest.run(f"mkdir /data/{marker}.d && echo nested > /data/{marker}.d/a")
    check(status == 0, "guest user can create folders in /data")
    status, _ = guest.run(f"cp -a /etc/hostname /data/{marker}.d/hostname")
    check(status == 0, "cp -a (no-op chown to own uid) works")
    status, err = guest.run(f"sudo chown root /data/{marker}.txt")
    check(status != 0, f"chown to another user is refused ({err!r})")
    status, _ = guest.run(f"sudo sh -c 'echo by-root >> /data/{marker}.txt'")
    check(status == 0, "root (sudo) can write in /data")
    status, rm_out = guest.run(f"rm -r /data/{marker}.d")
    check(status == 0, "guest user can delete a folder tree in /data")
    check(guest.poweroff(), "clean poweroff")
    content = host(f"cat {shlex.quote(marker)}.txt")
    check(content == "written-by-guest\nby-root\n", f"file visible on Android with both writes ({content!r})")
    check(host(f"ls -d {shlex.quote(marker)}.d 2>&1").find("No such") >= 0, "deleted folder gone on Android")
    host(f"rm -f {shlex.quote(marker)}.txt")

    uml_env("config", name, "--share-home", "off")
    guest = Guest(name)
    guest.boot()
    status, mounts = guest.run("grep ' /data ' /proc/mounts")
    check(status != 0, "sharing off: /data is not mounted")
    check(guest.poweroff(), "clean poweroff (sharing off)")
finally:
    uml_env("config", name, "--share-home", original)
    log.close()

print("All share-home checks passed" if not failures else f"{len(failures)} check(s) failed")
raise SystemExit(bool(failures))
