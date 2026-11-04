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
reject create -n good -t ubuntu --share-home maybe
reject create -n good --share-home
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
[[ $(HOME="$TEST_HOME" bash "$CLI" config ready-env) == $'Memory: 512M\nShare home at /data: on' ]]
HOME="$TEST_HOME" bash "$CLI" config ready-env --memory 4G > "$TEST_HOME/output"
[[ $(cat "$TEST_HOME/uml/envs/ready-env/memory") == 4096M ]]
[[ $(HOME="$TEST_HOME" UML_MEMORY=8G bash "$CLI" config ready-env | head -1) == 'Memory: 4096M' ]]
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
# /data sharing: on by default, saved per environment.
HOME="$TEST_HOME" bash "$CLI" config ready-env --share-home off > "$TEST_HOME/output"
[[ $(cat "$TEST_HOME/uml/envs/ready-env/share-home") == off ]]
HOME="$TEST_HOME" bash "$CLI" config ready-env --memory 1G --share-home on > "$TEST_HOME/output"
[[ $(cat "$TEST_HOME/uml/envs/ready-env/share-home") == on && $(cat "$TEST_HOME/uml/envs/ready-env/memory") == 1024M ]]
for invalid in '' maybe 'on;id'; do
    reject config ready-env --share-home "$invalid"
    [[ $(cat "$TEST_HOME/uml/envs/ready-env/share-home") == on ]]
done
reject config ready-env --share-home
# status lists every environment folder for the manager screen.
printf 'ubuntu\n' > "$TEST_HOME/uml/envs/ready-env/type"
printf 'dev\n' > "$TEST_HOME/uml/envs/ready-env/user"
[[ $(HOME="$TEST_HOME" bash "$CLI" status | grep '^ready-env' | cut -f1-6) == $'ready-env\tready\tubuntu\tdev\t1024M\ton' ]]
[[ $(HOME="$TEST_HOME" bash "$CLI" status | grep '^incomplete' | cut -f2) == incomplete ]]
(
    exec 9>"$TEST_HOME/uml/envs/ready-env/run.lock"
    flock 9
    [[ $(HOME="$TEST_HOME" bash "$CLI" status | grep '^ready-env' | cut -f2) == running ]]
    reject delete ready-env --yes
    [[ -d "$TEST_HOME/uml/envs/ready-env" ]]
)
# delete: needs --yes without a terminal, refuses bad or missing names.
reject delete incomplete < /dev/null
reject delete ../escape --yes
reject delete missing --yes
reject delete
HOME="$TEST_HOME" bash "$CLI" delete incomplete --yes > "$TEST_HOME/output"
[[ ! -e "$TEST_HOME/uml/envs/incomplete" && -d "$TEST_HOME/uml/envs/ready-env" ]]
printf 'broken\n' > "$TEST_HOME/uml/envs/ready-env/memory"
reject config ready-env
echo 'UML CLI validation tests passed.'
