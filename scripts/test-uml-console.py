# /// script
# dependencies = []
# ///
"""On-device console regression: uv run scripts/test-uml-console.py ENVIRONMENT.

Use a stopped, disposable environment in the UML debug app. Boots it, types
without Enter, waits for the final echoed byte, then powers it off cleanly.
No Android renderer is involved. A missing final byte must not need another
keypress to wake the guest. Logs are written under build/uml/.
"""

import argparse
import json
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
parser.add_argument("--count", type=int, default=300)
parser.add_argument("--interval", type=float, default=0.002)
parser.add_argument("--timeout", type=float, default=1.0)
parser.add_argument("--label", default="console")
args = parser.parse_args()
if args.count < 1 or args.interval < 0 or args.timeout <= 0:
    parser.error("count and timeout must be positive; interval must be nonnegative")
if not args.label.replace("-", "").replace("_", "").isalnum():
    parser.error("label must contain only letters, digits, hyphens and underscores")

output = Path(__file__).resolve().parent.parent / "build/uml"
output.mkdir(parents=True, exist_ok=True)
home = f"/data/user/0/{args.package}/files/home"
command = (
    f"cd {shlex.quote(home)} && export HOME={shlex.quote(home)} "
    f"PATH={shlex.quote(home + '/../usr/bin')}:/system/bin TERM=xterm-256color "
    f"&& exec bash ../usr/libexec/uml-env start {shlex.quote(args.environment)}"
)
process = subprocess.Popen(
    [args.adb, "shell", "-tt", "run-as", args.package, "sh", "-c", shlex.quote(command)],
    stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
)
log = (output / f"{args.label}.log").open("wb", buffering=0)


def send(data):
    process.stdin.write(data)
    process.stdin.flush()


def receive(marker, timeout):
    data = bytearray()
    start = time.monotonic()
    deadline = start + timeout
    while (remaining := deadline - time.monotonic()) > 0:
        ready, _, _ = select.select([process.stdout], [], [], remaining)
        if not ready:
            break
        chunk = os.read(process.stdout.fileno(), 65536)
        if not chunk:
            raise RuntimeError("Console closed unexpectedly; inspect the log")
        data.extend(chunk)
        log.write(chunk)
        if marker and marker in data:
            return bytes(data), time.monotonic() - start, True
    return bytes(data), time.monotonic() - start, False


booted = False
failures = []
durations = []
try:
    # Default Debian user prompt; match the final reset rather than color codes
    # embedded between the username, hostname and working directory.
    _, _, booted = receive(b"\x1b[00m$ ", 60)
    if not booted:
        raise RuntimeError("No Debian shell prompt; inspect the log")
    send(b"PS1='ECHO_CHECK> '\r")
    _, _, ready = receive(b"\x1b[?2004hECHO_CHECK> ", 5)
    if not ready:
        raise RuntimeError("Test prompt was not installed")
    receive(None, 0.1)
    for index in range(args.count):
        send(b"\x15")  # Clear the previous unexecuted line.
        receive(None, 0.02)
        token = f"ls abc{index:06d}xyz".encode()
        for byte in token:
            send(bytes([byte]))
            time.sleep(args.interval)
        echoed, elapsed, complete = receive(token, args.timeout)
        durations.append(elapsed)
        if not complete:
            send(b" ")
            after_space, _, _ = receive(b" ", args.timeout)
            failures.append({"burst": index, "echo": echoed.hex(),
                             "after_space": after_space.hex()})
            print(f"Stalled burst {index}: {echoed!r}; after space: {after_space!r}", flush=True)
        if (index + 1) % 50 == 0:
            print(f"{index + 1}/{args.count}; stalls={len(failures)}", flush=True)
finally:
    if booted and process.poll() is None:
        send(b"\x15sudo poweroff\r")
        _, _, powered_off = receive(b"reboot: Power down", 30)
        if not powered_off:
            raise RuntimeError("Guest did not confirm poweroff; check the test environment")
    process.stdin.close()
    process.wait(timeout=10)
    log.close()

result = {"bursts": len(durations), "interval_seconds": args.interval,
          "max_echo_wait_seconds": max(durations), "stalls": failures}
(output / f"{args.label}.json").write_text(json.dumps(result, indent=2) + "\n")
print(json.dumps(result))
raise SystemExit(bool(failures))
