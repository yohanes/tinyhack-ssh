# /// script
# dependencies = []
# ///
"""Close a UML console and reopen it twice on a connected device.

Use a stopped, disposable environment in the UML debug app. Closing a console
terminates the guest; use sudo poweroff for a clean shutdown of valuable work.
"""
import argparse
import os
from pathlib import Path
import select
import shlex
import subprocess
import time

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('environment')
parser.add_argument('--adb', default='adb')
parser.add_argument('--package', default='com.tinyhack.ssh.uml')
args = parser.parse_args()
home = f'/data/user/0/{args.package}/files/home'
command = (f'cd {shlex.quote(home)} && export HOME={shlex.quote(home)} '
           f'PATH={shlex.quote(home + "/../usr/bin")}:/system/bin TERM=xterm-256color '
           f'&& exec bash ../usr/libexec/uml-env start {shlex.quote(args.environment)}')
output = Path(__file__).resolve().parent.parent / 'build/uml/console-close.log'
output.parent.mkdir(parents=True, exist_ok=True)


def receive(process, marker, timeout, log):
    data = bytearray()
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if not select.select([process.stdout], [], [], min(1, deadline - time.monotonic()))[0]:
            continue
        chunk = os.read(process.stdout.fileno(), 65536)
        if not chunk:
            break
        data.extend(chunk)
        log.write(chunk)
        if marker in data:
            return
    raise RuntimeError(f'Did not receive {marker!r}; inspect {output}')


with output.open('wb', buffering=0) as log:
    for attempt in range(3):
        process = subprocess.Popen(
            [args.adb, 'shell', '-tt', 'run-as', args.package, 'sh', '-c', shlex.quote(command)],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        try:
            receive(process, b'\x1b[00m$ ', 60, log)
            print(f'Boot {attempt + 1}: reached shell', flush=True)
            if attempt == 2:
                process.stdin.write(b'sudo poweroff\r')
                process.stdin.flush()
                receive(process, b'reboot: Power down', 30, log)
                process.wait(timeout=10)
            else:
                # Disconnect the outer PTY, as closing an app terminal does.
                process.terminate()
                process.wait(timeout=10)
                time.sleep(3)
        finally:
            if process.poll() is None:
                process.terminate()
                process.wait(timeout=10)
            process.stdin.close()
            process.stdout.close()
print('Two console closes and subsequent boots passed; final guest powered off.')
