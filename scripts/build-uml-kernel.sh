#!/usr/bin/env bash
# Build the experimental ARM64 UML runtime bundled with the app.
set -euo pipefail
PROJECT=$(cd "$(dirname "$0")/.." >/dev/null && pwd)
WORK="$PROJECT/build/uml"
COMMIT=32e5e10b81d8882bdaf77b71e4106a941840c750
TREE="$WORK/linux"
if [[ ! -d "$TREE/.git" ]]; then
    mkdir -p "$WORK"
    git init "$TREE"
    git -C "$TREE" remote add origin https://github.com/zalexdev/linux-um-arm64.git
    git -C "$TREE" fetch --depth 1 origin "$COMMIT"
    git -C "$TREE" checkout --detach FETCH_HEAD
fi
[[ $(git -C "$TREE" rev-parse HEAD) == "$COMMIT" ]] || {
    echo "UML source must be pinned to $COMMIT" >&2; exit 1;
}
PATCH="$PROJECT/patches/uml-vector-napi-budget.patch"
if git -C "$TREE" apply --check "$PATCH" 2>/dev/null; then
    git -C "$TREE" apply "$PATCH"
else
    git -C "$TREE" apply --reverse --check "$PATCH"
fi
export TREE
PATCH="$PROJECT/patches/uml-console-wakeup.patch"
if git -C "$TREE" apply --check "$PATCH" 2>/dev/null; then
    git -C "$TREE" apply "$PATCH"
else
    git -C "$TREE" apply --reverse --check "$PATCH"
fi
PATCH="$PROJECT/patches/uml-stub-page-alignment.patch"
if git -C "$TREE" apply --check "$PATCH" 2>/dev/null; then
    git -C "$TREE" apply "$PATCH"
else
    git -C "$TREE" apply --reverse --check "$PATCH"
fi
export NDK=${UML_NDK:-$HOME/Android/Sdk/ndk/27.2.12479018}
export API=34
export JOBS=${JOBS:-12}
export O="$WORK/kernel-build"
export ART="$WORK/artifacts"
bash "$TREE/tools/um-arm64/harness/build-bionic.sh"
file "$ART/linux-bionic" "$ART/stub_exe_bionic"
sha256sum "$ART/linux-bionic" "$ART/stub_exe_bionic"
