# /// script
# dependencies = ["requests>=2.32,<3"]
# ///
"""Retain exact source packages for the pinned Debian filesystem template."""
import concurrent.futures
import hashlib
import lzma
from pathlib import Path
import re
import requests
import subprocess

root = Path(__file__).resolve().parent.parent
out = root / 'build/uml/debian-sources'
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

status = subprocess.check_output(['tar', '-xOf', str(root / 'build/uml/debian/base.tar'), 'var/lib/dpkg/status'], text=True)
needed = set()
for entry in paragraphs(status):
    source = entry.get('Source', entry['Package'])
    match = re.fullmatch(r'(\S+)(?: \(([^)]+)\))?', source)
    needed.add((match[1], match[2] or re.sub(r'\+b\d+$', '', entry['Version'])))

repositories = [('https://deb.debian.org/debian', 'trixie'), ('https://deb.debian.org/debian', 'trixie-updates'), ('https://deb.debian.org/debian-security', 'trixie-security')]
found = {}
for base, suite in repositories:
    cache = out / (suite + '-Sources.xz')
    if not cache.exists():
        response = requests.get(f'{base}/dists/{suite}/main/source/Sources.xz', timeout=120)
        response.raise_for_status()
        cache.write_bytes(response.content)
    for entry in paragraphs(lzma.decompress(cache.read_bytes()).decode()):
        key = (entry['Package'], entry['Version'])
        if key in needed:
            found[key] = (base, entry)
missing = needed - found.keys()
for name, version in sorted(missing):
    directory = f'pool/main/{name[:4] if name.startswith("lib") else name[0]}/{name}'
    filename = f'{name}_{version.split(":")[-1]}.dsc'
    for base, _ in repositories:
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
