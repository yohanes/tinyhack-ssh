#!/usr/bin/env bash
set -euo pipefail
PROJECT=$(cd "$(dirname "$0")/.." >/dev/null && pwd)
TEST_DIR=$(mktemp -d)
trap 'rm -rf "$TEST_DIR"' EXIT
cc -O2 -Wall -Wextra -Werror "$PROJECT/experiments/uml/tools/sparse-image.c" -o "$TEST_DIR/sparse-image"
# Data before, between and after zero-filled regions, plus a partial final block.
{ printf 'start'; head -c 1048576 /dev/zero; printf 'middle'; head -c 1048576 /dev/zero; printf 'end'; } > "$TEST_DIR/input"
"$TEST_DIR/sparse-image" "$TEST_DIR/output" 1073741824 < "$TEST_DIR/input"
cmp -n "$(stat -c %s "$TEST_DIR/input")" "$TEST_DIR/input" "$TEST_DIR/output"
[[ $(stat -c %s "$TEST_DIR/output") == 1073741824 ]]
(( $(stat -c %b "$TEST_DIR/output") * 512 < 1048576 ))
# Existing files must not be overwritten, and oversized/empty input must fail.
if "$TEST_DIR/sparse-image" "$TEST_DIR/output" 1 < "$TEST_DIR/input" 2>/dev/null; then exit 1; fi
if "$TEST_DIR/sparse-image" "$TEST_DIR/too-small" 1 < "$TEST_DIR/input" 2>/dev/null; then exit 1; fi
if "$TEST_DIR/sparse-image" "$TEST_DIR/empty" 1024 < /dev/null 2>/dev/null; then exit 1; fi
echo 'Sparse image tests passed (contents, capacity, allocation, overwrite protection).'
