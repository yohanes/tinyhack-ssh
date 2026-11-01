#!/usr/bin/env bash
set -euo pipefail
PROJECT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." >/dev/null && pwd)
TEST_DIR=$(mktemp -d)
trap 'rm -rf "$TEST_DIR"' EXIT
javac -d "$TEST_DIR" \
    "$PROJECT_DIR/app/src/main/java/com/tinyhack/ssh/util/PasteSafety.java" \
    "$PROJECT_DIR/tests/PasteSafetyTest.java"
java -cp "$TEST_DIR" PasteSafetyTest
