# Bundled terminfo sources

These human-readable entries correspond to the compiled terminfo files under
`app/src/main/assets/usr/share/terminfo/`. Rebuild an entry with:

```sh
tic -x -o app/src/main/assets/usr/share/terminfo terminfo-sources/<name>.terminfo
```

The Ghostty entries also originate from `ghostty/src/terminfo/`. The Kitty
entries retain the upstream names and are distributed under Kitty's GPLv3
terms; `xterm-256color` is distributed under the ncurses license included in
`licenses/ncurses-6.5.COPYING`.
