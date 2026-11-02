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
[[ $(HOME="$TEST_HOME" bash "$CLI" config ready-env) == 'Memory: 512M' ]]
HOME="$TEST_HOME" bash "$CLI" config ready-env --memory 4G > "$TEST_HOME/output"
[[ $(cat "$TEST_HOME/uml/envs/ready-env/memory") == 4096M ]]
[[ $(HOME="$TEST_HOME" UML_MEMORY=8G bash "$CLI" config ready-env) == 'Memory: 4096M' ]]
for invalid in 0G 128M 65G 999999G 1.5G 4GB 4096 '' '4G init=/bin/sh'; do
    reject config ready-env --memory "$invalid"
    [[ $(cat "$TEST_HOME/uml/envs/ready-env/memory") == 4096M ]]
    reject start ready-env --memory "$invalid"
done
reject config ../escape --memory 4G
reject config incomplete --memory 4G
reject config missing --memory 4G
reject config ready-env --memory
reject config ready-env --cpu 4
reject start ready-env --memory
HOME="$TEST_HOME" bash "$CLI" config ready-env --memory 256M > "$TEST_HOME/output"
[[ $(cat "$TEST_HOME/uml/envs/ready-env/memory") == 256M ]]
HOME="$TEST_HOME" bash "$CLI" config ready-env --memory 64G > "$TEST_HOME/output"
[[ $(cat "$TEST_HOME/uml/envs/ready-env/memory") == 65536M ]]
printf 'broken\n' > "$TEST_HOME/uml/envs/ready-env/memory"
reject config ready-env
echo 'UML CLI validation tests passed.'
