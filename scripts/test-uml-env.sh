#!/usr/bin/env bash
set -euo pipefail
PROJECT=$(cd "$(dirname "$0")/.." >/dev/null && pwd)
TEST_HOME=$(mktemp -d)
trap 'rm -rf "$TEST_HOME"' EXIT
CLI="$PROJECT/app/src/main/assets/usr/libexec/uml-env"
reject() {
    if HOME="$TEST_HOME" bash "$CLI" "$@" > "$TEST_HOME/output" 2>&1; then
        echo "Unexpected success: $*" >&2
        exit 1
    fi
}
reject create -n ../escape
reject create -n good -u root
reject create -n good -u 'bad user'
reject create -n good -d 128M
reject create -n good -d 0G
reject create -n good -d 999999G
reject create -n good -d 1TB
reject create -n good -t alpine
reject create --unknown value
reject create -n
reject start ../escape
reject start missing
reject start one two
[[ ! -d "$TEST_HOME/uml" ]]
mkdir -p "$TEST_HOME/uml/envs/ready-env" "$TEST_HOME/uml/envs/incomplete"
touch "$TEST_HOME/uml/envs/ready-env/ready"
touch "$TEST_HOME/uml/envs/ready-env/disk.ext4"
[[ $(HOME="$TEST_HOME" bash "$CLI" list) == ready-env ]]
echo 'UML CLI validation tests passed.'
