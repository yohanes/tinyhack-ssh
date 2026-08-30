#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." >/dev/null && pwd)
VERSION=$(awk -F'"' '/versionName/ {print $2; exit}' "$PROJECT_DIR/app/build.gradle")
OUTPUT_DIR=${TINYHACK_SSH_SOURCE_OUTPUT_DIR:-"$PROJECT_DIR/release"}
BUNDLE_NAME="tinyhack-ssh-$VERSION-source"
WORK_DIR=$(mktemp -d)
STAGE="$WORK_DIR/$BUNDLE_NAME"
trap 'rm -rf "$WORK_DIR"' EXIT

mkdir -p "$STAGE" "$OUTPUT_DIR"

# Archive the exact bytes of every tracked or non-ignored source file from the
# current working tree, including edits not yet committed, while excluding Git
# history, .env files, build outputs, and release artifacts via .gitignore.
(
    cd "$PROJECT_DIR"
    git ls-files -z --cached --others --exclude-standard | \
        tar --null --files-from=- -cf -
) | tar -C "$STAGE" -xf -

copy_source_tree() {
    local source=$1
    local destination=$2
    [[ -d "$source" ]] || { echo "Missing source tree: $source" >&2; exit 1; }
    mkdir -p "$destination"
    tar -C "$source" \
        --exclude='.git' --exclude='.zig-cache' --exclude='zig-out' \
        --exclude='*.o' --exclude='*.a' --exclude='*.so' --exclude='*.d' \
        --exclude='*.cmd' --exclude='*.log' --exclude='*.tmp' \
        --exclude='busybox' --exclude='busybox_unstripped*' \
        --exclude='bash' --exclude='rsync' --exclude='ssh' --exclude='scp' \
        --exclude='sftp' --exclude='ssh-add' --exclude='ssh-agent' \
        --exclude='ssh-keygen' --exclude='ssh-keyscan' \
        -cf - . | tar -C "$destination" -xf -
}

# Complete source trees corresponding to every native component distributed in
# the app. Generated configuration files are retained when they are source-like.
copy_source_tree "$PROJECT_DIR/ghostty" "$STAGE/corresponding-source/ghostty"
copy_source_tree "$PROJECT_DIR/build-bash/bash-5.2.37" "$STAGE/corresponding-source/bash-5.2.37"
copy_source_tree "$PROJECT_DIR/build-busybox/busybox-1.38.0" "$STAGE/corresponding-source/busybox-1.38.0"
copy_source_tree "$PROJECT_DIR/build-rsync/rsync-3.5.0" "$STAGE/corresponding-source/rsync-3.5.0"
copy_source_tree "$PROJECT_DIR/build-openssh/openssh-10.5p1" "$STAGE/corresponding-source/openssh-10.5p1"
copy_source_tree "$PROJECT_DIR/build-openssl/openssl-3.5.7" "$STAGE/corresponding-source/openssl-3.5.7"
copy_source_tree "$PROJECT_DIR/build-mosh/zlib-1.3.1" "$STAGE/corresponding-source/zlib-1.3.1"
copy_source_tree "$PROJECT_DIR/build-mosh/ncurses-6.5" "$STAGE/corresponding-source/ncurses-6.5"
copy_source_tree "$PROJECT_DIR/build-mosh/protobuf-21.12" "$STAGE/corresponding-source/protobuf-21.12"

{
    echo "Tinyhack SSH $VERSION corresponding source"
    echo "Created (UTC): $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "Tinyhack SSH commit: $(git -C "$PROJECT_DIR" rev-parse HEAD)"
    echo "Ghostty commit: $(git -C "$PROJECT_DIR/ghostty" rev-parse HEAD)"
    echo
    echo "Build entry points: scripts/rebuild-userland-16kb.sh and the Gradle build."
    echo "Contact: tinyhack-ssh@tinyhack.com"
} > "$STAGE/SOURCE-MANIFEST.txt"

ARCHIVE="$OUTPUT_DIR/$BUNDLE_NAME.tar.xz"
# Parallel level-3 compression keeps this large corresponding-source bundle
# practical to regenerate while remaining widely readable as .tar.xz.
tar -C "$WORK_DIR" -I 'xz -T0 -3' -cf "$ARCHIVE" "$BUNDLE_NAME"
sha256sum "$ARCHIVE" > "$ARCHIVE.sha256"
echo "Source archive: $ARCHIVE"
echo "Checksum:       $ARCHIVE.sha256"
