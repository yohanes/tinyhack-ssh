#!/usr/bin/env bash
# Plain-JVM regression tests for the file manager's scp/rsync/sftp command builders.
set -euo pipefail
PROJECT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." >/dev/null && pwd)
TEST_DIR="$(mktemp -d)"
trap 'rm -rf "$TEST_DIR"' EXIT
javac -d "$TEST_DIR" \
    "$PROJECT_DIR/app/src/main/java/com/tinyhack/ssh/ssh/SshTarget.java" \
    "$PROJECT_DIR/app/src/main/java/com/tinyhack/ssh/files/RemoteTransfer.java" \
    "$PROJECT_DIR/tests/RemoteTransferTest.java"
java -cp "$TEST_DIR" RemoteTransferTest
