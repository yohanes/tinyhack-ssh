# /// script
# dependencies = []
# ///
"""On-device test of the umnet DNS forwarder: uv run scripts/test-uml-dns.py ENVIRONMENT [options].

Use a stopped, disposable environment in the UML debug app (launch the app once
after installing it so its bundled files are current). Boots it once through
ADB under the app UID and checks, from inside the guest:

- /etc/resolv.conf names the virtual nameserver 10.0.2.3 (the boot shim
  replaces the old 1.1.1.1 default);
- internet names, local network names (--lan-name, served by the router's DNS)
  and multicast DNS names (--mdns-name, *.local) resolve to what Android sees;
- unknown names fail with NXDOMAIN rather than a timeout;
- an answer larger than the 1500-byte MTU arrives whole (IP fragments);
- many parallel lookups all succeed;
- TCP to the nameserver is refused at once.

DNS never touches passt, so these checks also work in ADB's run-as context,
where the guest has no other network access. Logs go to build/uml/dns.log.
"""

import argparse
import base64
import os
from pathlib import Path
import re
import select
import shlex
import subprocess
import time

parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
parser.add_argument("environment")
parser.add_argument("--adb", default="adb")
parser.add_argument("--package", default="com.tinyhack.ssh.uml")
parser.add_argument("--lan-name", help="a host name only the local network's DNS knows, e.g. nas")
parser.add_argument("--lan-addr", help="the IPv4 address --lan-name should resolve to")
parser.add_argument("--mdns-name", help="a multicast DNS name on the local network, e.g. nas.local")
parser.add_argument("--mdns-addr", help="the IPv4 address --mdns-name should resolve to")
parser.add_argument("--big-name", default="microsoft.com", help="a name with a TXT answer over 1500 bytes")
args = parser.parse_args()

output = Path(__file__).resolve().parent.parent / "build/uml"
output.mkdir(parents=True, exist_ok=True)
home = f"/data/user/0/{args.package}/files/home"
env_prefix = (
    f"cd {shlex.quote(home)} && export HOME={shlex.quote(home)} "
    f"PATH={shlex.quote(home + '/../usr/bin')}:/system/bin TERM=xterm-256color && "
)
log = (output / "dns.log").open("wb", buffering=0)
failures = []

# Sends one raw DNS query (no EDNS) over UDP and prints what came back.
QUERY_PL = r'''
use IO::Socket::INET;
my ($name, $type) = @ARGV;
my $s = IO::Socket::INET->new(PeerAddr => "10.0.2.3:53", Proto => "udp") or die "socket: $!\n";
my $q = pack("n6", 0x4242, 0x0100, 1, 0, 0, 0)
      . join("", map { chr(length) . $_ } split /\./, $name) . "\0" . pack("n2", $type, 1);
$s->send($q);
my $rin = ""; vec($rin, fileno($s), 1) = 1;
select($rin, undef, undef, 8) or die "timeout\n";
my $r; $s->recv($r, 65535);
printf "len=%d id=%04x tc=%d rcode=%d an=%d\n", length $r, unpack("n", $r),
    (unpack("x2C", $r) >> 1) & 1, unpack("x3C", $r) & 15, unpack("x6n", $r);
'''


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
        """Read until marker (bytes, or a compiled bytes regex) appears."""
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
            if (marker.search(data) if isinstance(marker, re.Pattern) else marker in data):
                return bytes(data), True
        return bytes(data), False

    def boot(self):
        _, ok = self.receive(b"\x1b[00m$ ", 90)
        if not ok:
            raise RuntimeError("No guest shell prompt; inspect build/uml/dns.log")
        self.send(b"PS1='> '; stty -echo\r")
        self.receive(b"> ", 5)

    def run(self, command, timeout=30):
        """Run a guest shell command; returns (exit status, output)."""
        self.counter += 1
        start, end = f"@@S{self.counter}@@", f"@@E{self.counter}@@"
        self.send(f"echo {start}; {command}; echo {end}$?\r".encode())
        # Any exit status ends the command, not only 0.
        data, _ = self.receive(re.compile(re.escape(end.encode()) + rb"\d+\r?\n"), timeout)
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


def first_address(text):
    """First IPv4 address in getent output (passt may log lines in between)."""
    match = re.search(r"^(\d+\.\d+\.\d+\.\d+)\s", text, re.M)
    return match[1] if match else ""


TIMED = "s=$(date +%s%N); {}; echo rc=$? ms=$((($(date +%s%N)-s)/1000000))"


def timed(command):
    """Run a command in the guest; returns (exit status, ms, output)."""
    _, out = guest.run(TIMED.format(command))
    match = re.search(r"rc=(\d+) ms=(\d+)", out)
    if not match:
        return 255, 99999, out
    return int(match[1]), int(match[2]), out[:match.start()].strip()


# Android blocks an app's network while the phone sleeps unless the app is in
# the foreground (or runs a foreground service); a run-as guest is neither, so
# wake the phone and bring the app to the front first.
subprocess.run([args.adb, "shell", "input", "keyevent", "KEYCODE_WAKEUP"], check=False)
subprocess.run([args.adb, "shell", "wm", "dismiss-keyguard"], check=False)
subprocess.run([args.adb, "shell", "monkey", "-p", args.package, "-c", "android.intent.category.LAUNCHER", "1"],
               capture_output=True, check=False)
time.sleep(2)

guest = Guest(args.environment)
try:
    guest.boot()
    status, conf = guest.run("cat /etc/resolv.conf")
    check(conf == "nameserver 10.0.2.3", f"resolv.conf uses the virtual nameserver ({conf!r})")

    status, ms, out = timed("getent ahostsv4 example.com")
    check(status == 0 and first_address(out), f"internet name resolves ({first_address(out)!r}, {ms} ms)")

    if args.lan_name:
        status, ms, out = timed(f"getent ahostsv4 {shlex.quote(args.lan_name)}")
        address = first_address(out)
        check(status == 0 and address and address == (args.lan_addr or address),
              f"local network name {args.lan_name} resolves ({address!r}, {ms} ms)")
    if args.mdns_name:
        status, ms, out = timed(f"getent ahostsv4 {shlex.quote(args.mdns_name)}")
        address = first_address(out)
        check(status == 0 and address and address == (args.mdns_addr or address),
              f"multicast DNS name {args.mdns_name} resolves ({address!r}, {ms} ms)")

    status, ms, _ = timed("getent hosts no-such-host.invalid")
    check(status == 2 and ms < 3000, f"unknown name fails as not found in {ms} ms (exit {status})")
    status, ms, _ = timed("getent ahosts no-such-host.local")
    check(status == 2 and ms < 10000, f"unknown .local name fails as not found in {ms} ms (exit {status})")

    script = base64.b64encode(QUERY_PL.encode()).decode()
    guest.run(f"echo {script} | base64 -d > /tmp/dnsq.pl")
    status, out = guest.run(f"perl /tmp/dnsq.pl {shlex.quote(args.big_name)} 16")
    fields = dict(f.split("=", 1) for f in out.split() if "=" in f)
    check(status == 0 and int(fields.get("len", "0")) > 1500 and fields.get("id") == "4242"
          and fields.get("tc") == "0" and int(fields.get("an", "0")) > 0,
          f"large TXT answer arrives whole over UDP ({out!r})")

    status, out = guest.run(
        "for i in $(seq 24); do getent ahostsv4 example.com > /tmp/dns.$i & done; wait; "
        "n=0; for i in $(seq 24); do [ -s /tmp/dns.$i ] && n=$((n+1)); done; echo $n")
    check(out.endswith("24"), f"24 parallel lookups all answered ({out.splitlines()[-1] if out else ''!r})")

    status, ms, out = timed("timeout 5 bash -c 'exec 3<>/dev/tcp/10.0.2.3/53' 2>&1")
    check("refused" in out and ms < 1000, f"TCP to the nameserver is refused at once ({ms} ms)")

    check(guest.poweroff(), "clean poweroff")
finally:
    if guest.process.poll() is None:
        guest.process.kill()
    log.close()

print("All DNS checks passed" if not failures else f"{len(failures)} check(s) failed")
raise SystemExit(bool(failures))
