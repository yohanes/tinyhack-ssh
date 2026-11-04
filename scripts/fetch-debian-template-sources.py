# /// script
# dependencies = ["requests>=2.32,<3"]
# ///
"""Retain exact source packages for a pinned filesystem template.

Usage: uv run scripts/fetch-debian-template-sources.py [debian|ubuntu]
Reads the template's dpkg status from build/uml/TYPE/base.tar and stores every
source package (with SHA256SUMS and PACKAGES lists) in build/uml/TYPE-sources.
"""
import concurrent.futures
import hashlib
import lzma
from pathlib import Path
import re
import requests
import subprocess
import sys

root = Path(__file__).resolve().parent.parent
distro = sys.argv[1] if len(sys.argv) > 1 else 'debian'
if distro == 'debian':
    repositories = [('https://deb.debian.org/debian', 'trixie'),
                    ('https://deb.debian.org/debian', 'trixie-updates'),
                    ('https://deb.debian.org/debian-security', 'trixie-security')]
    components = ['main']
elif distro == 'ubuntu':
    # Source packages for every architecture are on the primary archive.
    repositories = [('https://archive.ubuntu.com/ubuntu', suite)
                    for suite in ('resolute', 'resolute-updates', 'resolute-security')]
    components = ['main', 'universe', 'restricted', 'multiverse']
else:
    raise SystemExit('Usage: fetch-debian-template-sources.py [debian|ubuntu]')
out = root / f'build/uml/{distro}-sources'
out.mkdir(parents=True, exist_ok=True)

def paragraphs(text):
    for paragraph in text.split('\n\n'):
        fields = {}
        key = None
        for line in paragraph.splitlines():
            if line.startswith(' ') and key:
                fields[key] += '\n' + line.strip()
            elif ':' in line:
                key, value = line.split(':', 1)
                fields[key] = value.lstrip()
        if fields:
            yield fields

status = subprocess.check_output(['tar', '-xOf', str(root / f'build/uml/{distro}/base.tar'), 'var/lib/dpkg/status'], text=True)
needed = set()
for entry in paragraphs(status):
    source = entry.get('Source', entry['Package'])
    match = re.fullmatch(r'(\S+)(?: \(([^)]+)\))?', source)
    needed.add((match[1], match[2] or re.sub(r'\+b\d+$', '', entry['Version'])))

found = {}
for base, suite in repositories:
    for component in components:
        # Debian keeps its historical cache name for main.
        cache = out / (f'{suite}-Sources.xz' if component == 'main' else f'{suite}-{component}-Sources.xz')
        if not cache.exists():
            response = requests.get(f'{base}/dists/{suite}/{component}/source/Sources.xz', timeout=120)
            response.raise_for_status()
            cache.write_bytes(response.content)
        for entry in paragraphs(lzma.decompress(cache.read_bytes()).decode()):
            key = (entry['Package'], entry['Version'])
            if key in needed:
                found[key] = (base, entry)
missing = needed - found.keys()
# Fallback for versions superseded in the indexes: fetch the .dsc from the pool.
for name, version in sorted(missing):
    filename = f'{name}_{version.split(":")[-1]}.dsc'
    prefix = name[:4] if name.startswith('lib') else name[0]
    candidates = [(base, f'pool/{component}/{prefix}/{name}')
                  for base, _ in repositories for component in components]
    for base, directory in dict.fromkeys(candidates):
        response = requests.get(f'{base}/{directory}/{filename}', timeout=60)
        if response.status_code != 200:
            continue
        entries = [entry for entry in paragraphs(response.text) if 'Checksums-Sha256' in entry]
        if entries:
            entry = entries[0]
            entry['Directory'] = directory
            entry['Checksums-Sha256'] += f'\n{hashlib.sha256(response.content).hexdigest()} {len(response.content)} {filename}'
            found[(name, version)] = (base, entry)
            break
missing = needed - found.keys()
if missing:
    raise SystemExit(f'Exact source packages missing from indexes: {sorted(missing)}')

jobs = {}
for key, (base, entry) in sorted(found.items()):
    for item in entry['Checksums-Sha256'].splitlines():
        if not item.strip():
            continue
        digest, size, name = item.split()
        jobs[name] = (f"{base}/{entry['Directory']}/{name}", digest, int(size))

def fetch(item):
    name, (url, digest, size) = item
    path = out / name
    if not path.exists() or hashlib.sha256(path.read_bytes()).hexdigest() != digest:
        response = requests.get(url, timeout=180)
        response.raise_for_status()
        data = response.content
        if len(data) != size or hashlib.sha256(data).hexdigest() != digest:
            raise ValueError(f'Source checksum mismatch: {name}')
        path.write_bytes(data)
    return f'{digest}  {name}\n'

with concurrent.futures.ThreadPoolExecutor(max_workers=4) as executor:
    hashes = list(executor.map(fetch, sorted(jobs.items())))
(out / 'SHA256SUMS').write_text(''.join(hashes))
(out / 'PACKAGES').write_text(''.join(f'{name} {version}\n' for name, version in sorted(needed)))
print(f'Retained {len(needed)} source packages ({len(jobs)} files) in {out}')
