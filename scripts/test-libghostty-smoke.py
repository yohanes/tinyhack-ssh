# /// script
# dependencies = []
# ///
"""Smoke test of the app's libghostty-vt integration: uv run scripts/test-libghostty-smoke.py

Run it after updating libghostty-vt. It drives a local shell session of an
installed debug build through the debug HTTP server and checks every path of
app/src/main/cpp/ghostty_jni.cpp that talks to libghostty:

- the key encoder, including Kitty keyboard flags read from the terminal;
- paste encoding with bracketed paste (through the app's own Paste action);
- the mouse encoder with SGR mouse reporting;
- the formatter (plain screen text, VT output with styles, selection text,
  last command output);
- selection, scrolling, hyperlinks (OSC 8), the title, synchronized output;
- OSC 9 and OSC 99 (Kitty) notifications;
- render state: styles, colors, graphemes and a Kitty graphics image are
  drawn; a screenshot is saved to build/libghostty-smoke.png to inspect.

Setup: install a debug build, enable the debug server (Settings, or the
http_debug_enabled preference) and open the app with a local shell.

An x86_64 emulator image with ARM translation (API 35 google_apis) works. Its
translator resolves argv[0] against the working directory, so the shell only
starts after a link in the app home (emulator only, never on a device):

    adb shell "run-as com.tinyhack.ssh ln -s ../usr/bin/bash files/home/bash"

The checks use bash builtins only, so busybox applets, which the translator
cannot start, are not needed.
"""

import argparse
import base64
import json
import re
import subprocess
import sys
import time
import urllib.parse
import urllib.request
import zlib
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
parser.add_argument("--adb", default="adb")
parser.add_argument("--package", default="com.tinyhack.ssh")
parser.add_argument("--port", type=int, default=28080, help="local port forwarded to the app's port 8080")
args = parser.parse_args()

root = Path(__file__).resolve().parent.parent
failures = []


def adb(*cmd, **kw):
    return subprocess.run([args.adb, *cmd], capture_output=True, text=True, **kw)


def run_as(command, data=None):
    proc = subprocess.run([args.adb, "shell", f"run-as {args.package} sh -c '{command}'"],
                          input=data, capture_output=True)
    return proc.stdout.decode(errors="replace").strip()


adb("forward", f"tcp:{args.port}", "tcp:8080")
token = run_as("cat files/http_debug_token")
if not re.fullmatch(r"[0-9a-f]{16,}", token):
    sys.exit("No debug token; enable the debug server and open the app first")


def api(path, method="GET", body=None, **params):
    url = f"http://127.0.0.1:{args.port}{path}"
    if params:
        url += "?" + urllib.parse.urlencode(params)
    request = urllib.request.Request(url, method=method, data=body.encode() if body is not None else None,
                                     headers={"Authorization": f"Bearer {token}"})
    with urllib.request.urlopen(request, timeout=15) as response:
        return response.read().decode(errors="replace")


def check(ok, what):
    print(("ok   " if ok else "FAIL ") + what, flush=True)
    if not ok:
        failures.append(what)


def screen():
    return api("/dump")


def wait_for(pattern, timeout=10):
    deadline = time.monotonic() + timeout
    text = ""
    while time.monotonic() < deadline:
        text = screen()
        if re.search(pattern, text):
            return text
        time.sleep(0.3)
    return None


def shell(command):
    """Type a command as the soft keyboard would and press Return."""
    api("/type", text=command, post_return="1")


# Commands below use bash builtins only (printf, read, $(<file), brace
# loops). On an x86_64 emulator the ARM translator cannot start busybox
# applets, which find themselves through argv[0]; builtins work everywhere.
def clear():
    """Clear the screen and scrollback, so screen rows equal viewport rows.

    /dump numbers rows from the top of the scrollback while /selection and
    /hyperlink take viewport rows; they agree only on a cleared terminal.
    """
    shell("printf '\\033[H\\033[2J\\033[3J'")
    if wait_for(r"\A\s*\S*\$\s*\Z", 5) is None:
        sys.exit(f"clear failed; screen is:\n{screen()}")


def read_raw(count, setup="", teardown=""):
    """Read `count` raw bytes from the terminal and print them with %q."""
    shell(f"{setup}read -rsn {count} x; {teardown}printf 'GOT:%q\\n' \"$x\"")
    time.sleep(1)


def find_row(marker):
    """Last screen row containing marker (output comes after the command)."""
    found = (None, None)
    for row, line in enumerate(screen().split("\n")):
        if marker in line:
            found = (row, line.index(marker))
    return found


def ui_tap(label):
    """Tap the first on-screen view whose text is label; False if absent."""
    subprocess.run([args.adb, "shell", "uiautomator", "dump", "/sdcard/ghostty-smoke-ui.xml"], capture_output=True)
    dump = adb("shell", "cat", "/sdcard/ghostty-smoke-ui.xml").stdout
    adb("shell", "rm", "-f", "/sdcard/ghostty-smoke-ui.xml")
    match = re.search(rf'text="{re.escape(label)}"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', dump)
    if not match:
        return False
    x1, y1, x2, y2 = map(int, match.groups())
    adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
    return True


def kitty_image():
    """A 48x48 RGB gradient as Kitty graphics escape chunks."""
    width = height = 48
    pixels = bytes(v for y in range(height) for x in range(width)
                   for v in (x * 5, y * 5, 255 - x * 5))
    data = base64.b64encode(zlib.compress(pixels)).decode()
    chunks = [data[i:i + 4096] for i in range(0, len(data), 4096)]
    out = []
    for i, chunk in enumerate(chunks):
        more = 1 if i < len(chunks) - 1 else 0
        keys = f"a=T,f=24,o=z,s={width},v={height},m={more}" if i == 0 else f"m={more}"
        out.append(f"\x1b_G{keys};{chunk}\x1b\\")
    return "".join(out).encode()


# Files the guest shell reads: a style sheet and an image.
STYLES = (
    "\x1b[1mBOLD\x1b[0m \x1b[3mITALIC\x1b[0m \x1b[2mFAINT\x1b[0m \x1b[7mINVERSE\x1b[0m "
    "\x1b[9mSTRIKE\x1b[0m\n"
    "\x1b[4mUL\x1b[0m \x1b[4:2mDOUBLE\x1b[0m \x1b[4:3mCURLY\x1b[0m \x1b[4:4mDOTTED\x1b[0m "
    "\x1b[4:5;58;2;0;255;0mDASHED-GREEN\x1b[0m\n"
    "\x1b[38;2;255;0;0mTRUERED\x1b[0m \x1b[38;5;45mPAL45\x1b[0m \x1b[48;5;22mBG22\x1b[0m\n"
    "GRAPHEMES: e\u0301 \U0001F44D\U0001F3FD \U0001F1EE\U0001F1E9 \u6f22\u5b57 END\n"
)
run_as("cat > files/home/.ghostty-smoke-styles", STYLES.encode())
run_as("cat > files/home/.ghostty-smoke-image", kitty_image())

check(wait_for(r"\$\s*$", 15) is not None, "local shell prompt is visible")

# Formatter (plain) and IME text.
clear()
shell("echo smoke-$((40+2))")
check(wait_for(r"smoke-42") is not None, "typed command runs and plain screen text is read back")

# Key encoder: history recall with the Up arrow, then Return.
api("/key", k="UP")
api("/key", k="ENTER")
check(wait_for(r"smoke-42[\s\S]*smoke-42") is not None, "Up/Return through the key encoder")

# Key encoder reads Kitty keyboard flags from the terminal: with
# "disambiguate" on, Escape is sent as CSI 27 u.
clear()
read_raw(5, "printf '\\033[>1u'; ", "printf '\\033[<u'; ")
api("/key", k="ESC")
check(wait_for(r"GOT:\$'\\E\[27u'") is not None, "Escape uses the Kitty keyboard encoding when the app enables it")

# Mouse encoder with SGR reporting.
clear()
# A click is a press (final M) and a release (final m); read through the
# release so no report bytes are left over for the shell.
shell("printf '\\033[?1000h\\033[?1006h'; read -rs -d m -t 5 x; printf '\\033[?1000l\\033[?1006l'; "
      "printf 'GOT:%q\\n' \"$x\"")
time.sleep(1)
api("/mouse", x="25", y="45", cw="10", ch="20", sw="1080", sh="2000")
check(wait_for(r"GOT:\$'\\E\[<0;3;3M\\E\[<0;3;3'") is not None,
      "mouse click is encoded as SGR press and release reports")

# Styles, colors and graphemes: VT formatter output keeps them.
clear()
shell("printf '%s\\n' \"$(<~/.ghostty-smoke-styles)\"")
wait_for(r"GRAPHEMES")
vt = api("/vt")
check("BOLD" in vt and re.search(r"\x1b\[[0-9;:]*38;2;255;0;0", vt) is not None,
      "VT formatter output contains styled text and a true-color SGR")
text = screen()
check("\u6f22\u5b57" in text and "END" in text, "wide characters reach the screen text")

# Selection and its text, then copy to the clipboard.
row, col = find_row("TRUERED")
api("/selection", action="set", sc=str(col), sr=str(row), ec=str(col + 6), er=str(row))
selected = api("/selection", action="get")
check(selected.strip() == "TRUERED", f"selection text ({selected.strip()!r})")
copied = api("/copy")
clip = api("/clipboard")
check("copied" in copied and clip.strip() == "TRUERED", f"copy puts the selection on the clipboard ({clip.strip()!r})")

# Paste through the app's paste path (the selection bar's Paste button, shown
# after a long-press). The reader enables bracketed paste, so the clipboard
# text must arrive wrapped.
clear()
read_raw(19, "printf '\\033[?2004h'; ", "printf '\\033[?2004l'; ")
adb("shell", "input", "swipe", "120", "330", "120", "330", "900")
time.sleep(1.5)
if ui_tap("Paste"):
    check(wait_for(r"GOT:\$'\\E\[200~TRUERED\\E\[201~'") is not None,
          "paste is encoded as a bracketed paste")
else:
    check(False, "Paste button on the selection bar")
    api("/input", body="\x03")

# Hyperlinks (OSC 8).
clear()
shell("printf '\\033]8;;https://example.com/ghostty-smoke\\033\\\\LINK\\x54EXT\\033]8;;\\033\\\\\\n'")
wait_for(r"LINKTEXT")
row, col = find_row("LINKTEXT")
link = api("/hyperlink", col=str(col + 2), row=str(row))
check("https://example.com/ghostty-smoke" in link, f"OSC 8 hyperlink URI ({link})")

# Title (OSC 2) and synchronized output (mode 2026).
shell("printf '\\033]2;smoke-title-42\\007'")
time.sleep(1)
check('"title":"smoke-title-42"' in api("/json"), "OSC 2 sets the session title")
shell("printf '\\033[?2026h'")
time.sleep(1)
active = api("/sync")
shell("printf '\\033[?2026l'")
time.sleep(1)
check('"syncActive":true' in active and '"syncActive":false' in api("/sync"), "synchronized output mode is reported")

# Scrolling the viewport.
clear()
shell("for i in {1..300}; do echo $i; done")
wait_for(r"\n300\n")
top = api("/scroll", type="0", delta="0")
check(re.search(r"(?m)^1$", top) is not None, "scroll to top shows the first line")
api("/scroll", type="1", delta="0")
check(re.search(r"(?m)^300$", screen()) is not None, "scroll to bottom shows the last line")

# Last command output (formatter on a range, when there are no prompt marks).
shell("echo last-output-77")
wait_for(r"\nlast-output-77")
last = api("/lastOutput")
check("last-output-77" in last, f"last command output ({last[:80]!r})")

# OSC 99 (Kitty notifications, the patched callback). Terminal notifications
# are opt-in per profile, so run it in a temporary local profile.
created = json.loads(api("/profiles/create", method="POST",
                         body=json.dumps({"name": "ghostty-smoke", "type": "LOCAL",
                                          "terminalNotificationsEnabled": True})))
profile = created["profile"]["id"]
session = json.loads(api("/profiles/connect", method="POST", id=profile))["sessionId"]
try:
    check(wait_for(r"\$\s*$", 15) is not None, "notification profile session starts")
    shell("printf '\\033]99;i=smoke:d=0;Smoke title\\033\\\\\\033]99;i=smoke:p=body;Smoke body 99\\033\\\\'")
    deadline = time.monotonic() + 8
    notes = ""
    while time.monotonic() < deadline and "Smoke body 99" not in notes:
        time.sleep(0.5)
        notes = api("/notifications")
    check("Smoke title" in notes and "Smoke body 99" in notes, "OSC 99 chunks are assembled into one notification")
    # A capability query is answered through the callback's return value.
    shell("printf '\\033]99;i=q:p=?;\\033\\\\'; read -rsn 30 -t 3 x; printf 'GOT:%q\\n' \"$x\"")
    check(wait_for(r"GOT:\$'\\E\]99;i=q:p=\?;p=title", 8) is not None, "OSC 99 capability query is answered")
finally:
    api("/sessions/close", method="POST", id=session)
    api("/profiles/delete", method="POST", id=profile)
    time.sleep(1)

# Render state: draw everything and save a screenshot to inspect.
clear()
shell("printf '%s\\n%s' \"$(<~/.ghostty-smoke-styles)\" \"$(<~/.ghostty-smoke-image)\";"
      " printf '\\033[4 q'; echo; echo cursor-is-underline")
time.sleep(3)
png = subprocess.run([args.adb, "exec-out", "screencap", "-p"], capture_output=True).stdout
out = root / "build/libghostty-smoke.png"
out.parent.mkdir(exist_ok=True)
out.write_bytes(png)
print(f"screenshot: {out}")
shell("printf '\\033[2 q'")
clear()
run_as("rm -f files/home/.ghostty-smoke-styles files/home/.ghostty-smoke-image")

print("All libghostty smoke checks passed" if not failures else f"{len(failures)} check(s) failed")
raise SystemExit(bool(failures))
