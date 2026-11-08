# /// script
# dependencies = []
# ///
"""Collect license texts of the code linked into libghostty-vt.

usage: uv run scripts/collect-libghostty-licenses.py [--check]

libghostty-vt statically links a few third-party packages (SIMD and Unicode
libraries, an image decoder). Upstream lists them in
ghostty/deps/libghostty-vt-third-party.json; every entry marked "linked" ends
up in libghostty-android.so. For each one this writes
licenses/libghostty-<name>-<version>.LICENSE (and the same file in the APK's
assets/licenses/) from the package's own LICENSE/COPYING/NOTICE files and its
licenses/ folder.

Run it after scripts/build-ghostty-android.sh, which fetches the packages into
ghostty/zig-pkg. It then checks that THIRD-PARTY-NOTICES.md (root and APK copy)
and the About screen list every file, and that the two notices copies match,
so a libghostty update that adds a dependency fails here instead of shipping
without its notice. --check only verifies; it writes nothing.
"""

import argparse
import json
import sys
from pathlib import Path

root = Path(__file__).resolve().parent.parent
ghostty = root / "ghostty"
outputs = [root / "licenses", root / "app/src/main/assets/licenses"]
notices = [root / "THIRD-PARTY-NOTICES.md", root / "app/src/main/assets/licenses/THIRD-PARTY-NOTICES.md"]
about = root / "app/src/main/java/com/tinyhack/ssh/ui/AboutActivity.java"
PREFIX = "libghostty-"
# License files a package refers to but leaves out of its published archive,
# kept in license-sources/<name>-<version>/ (see the README there).
supplements = root / "license-sources"

parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
parser.add_argument("--check", action="store_true", help="verify only; do not write files")
args = parser.parse_args()


def package_dir(dep):
    if dep["kind"] == "vendored":
        return ghostty / dep["path"]
    for base in (ghostty / "zig-pkg", Path.home() / ".cache/zig/p"):
        if (base / dep["hash"]).is_dir():
            return base / dep["hash"]
    sys.exit(f"{dep['name']}: package {dep['hash']} not found; run scripts/build-ghostty-android.sh first")


def license_files(directory):
    names = ("licen", "copying", "notice")
    found = [p for p in sorted(directory.iterdir()) if p.is_file() and p.name.lower().startswith(names)]
    sub = directory / "licenses"
    if sub.is_dir():
        found += sorted(p for p in sub.iterdir() if p.is_file())
    return found


manifest = json.loads((ghostty / "deps/libghostty-vt-third-party.json").read_text())
linked = [d for d in manifest["third_party"] if d["use"] == "linked"]
wanted = {}
for dep in linked:
    directory = package_dir(dep)
    files = license_files(directory)
    if not files:
        sys.exit(f"{dep['name']}: no license file in {directory}")
    extra = supplements / f"{dep['name']}-{dep['version']}"
    if extra.is_dir():
        files += sorted(p for p in extra.iterdir() if p.is_file() and p.name != "README.md")
    elif any(s.name.startswith(f"{dep['name']}-") for s in supplements.iterdir() if s.is_dir()):
        sys.exit(f"{dep['name']}: license-sources has another version; refresh it for {dep['version']}")
    origin = dep.get("commit") or dep.get("path")
    parts = [
        f"{dep['name']} {dep['version']} ({origin}), linked into libghostty-vt\n"
        f"License: {dep['license']}\nSource: {dep['url']}\n"
    ]
    for f in files:
        label = f.relative_to(directory) if f.is_relative_to(directory) else f"licenses/{f.name}"
        parts.append(f"\n==== {label} ====\n\n{f.read_text(errors='replace').rstrip()}\n")
    wanted[f"{PREFIX}{dep['name']}-{dep['version']}.LICENSE"] = "".join(parts)
    print(f"{dep['name']:12} {dep['version']:16} {dep['license']:28} {len(files)} file(s)")

problems = []
for out in outputs:
    for name, text in wanted.items():
        path = out / name
        if args.check:
            if not path.exists() or path.read_text() != text:
                problems.append(f"{path.relative_to(root)} is missing or stale")
        else:
            path.write_text(text)
    for stale in out.glob(f"{PREFIX}*.LICENSE"):
        if stale.name not in wanted:
            if args.check:
                problems.append(f"{stale.relative_to(root)} is no longer linked")
            else:
                stale.unlink()
if notices[0].read_text() != notices[1].read_text():
    problems.append(f"{notices[1].relative_to(root)} differs from {notices[0].relative_to(root)}; copy the root file over it")
for doc in [*notices, about]:
    text = doc.read_text()
    problems += [f"{doc.relative_to(root)} does not mention {name}" for name in wanted if name not in text]

if problems:
    print("\n".join(problems))
    sys.exit(1)
print(f"{len(wanted)} libghostty-vt dependency licenses {'verified' if args.check else 'written and listed'}")
