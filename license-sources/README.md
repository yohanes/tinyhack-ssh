# Supplementary license texts

Some packages linked into libghostty-vt refer to license files that their
published package archive leaves out. The copies here come from each project's
repository at the exact commit pinned in
`ghostty/deps/libghostty-vt-third-party.json`;
`scripts/collect-libghostty-licenses.py` adds them to the bundled license file.

- `uucode-0.2.0/` — uucode at `1fb73433bba5d93366c57f23ff2e9d7939746500`
  (https://github.com/jacobsandlund/uucode/tree/1fb73433bba5d93366c57f23ff2e9d7939746500/licenses).
  `LICENSE.md` names both files: the Unicode data license (its tables are
  generated from the Unicode Character Database) and Bjoern Hoehrmann's UTF-8
  decoder license (`src/utf8.zig`).

When a package's version changes, fetch the files again from the new commit;
the collector refuses to run while the folder names an older version.
