#!/usr/bin/env bash
# Integration test for the remote file browser's SFTP client and ssh control
# master handling. Starts a throwaway, unprivileged sshd on 127.0.0.1 with its
# own host key and authorized_keys; nothing in ~/.ssh is read or changed.
set -euo pipefail
PROJECT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." >/dev/null && pwd)
SSHD=$(command -v sshd || echo /usr/sbin/sshd)
if [ ! -x "$SSHD" ] || ! command -v ssh >/dev/null || ! command -v scp >/dev/null; then
    echo "SKIP: OpenSSH server/client not installed"
    exit 0
fi
# Short path: Unix socket paths are limited to ~100 bytes.
TEST_DIR="$(mktemp -d /tmp/rbt.XXXXXX)"
SSHD_PID=
cleanup() {
    [ -n "$SSHD_PID" ] && kill "$SSHD_PID" 2>/dev/null || true
    rm -rf "$TEST_DIR"
}
trap cleanup EXIT
chmod 700 "$TEST_DIR"

ssh-keygen -q -t ed25519 -N '' -f "$TEST_DIR/host_key"
ssh-keygen -q -t ed25519 -N '' -f "$TEST_DIR/client_key"
cp "$TEST_DIR/client_key.pub" "$TEST_DIR/authorized_keys"
PORT=$(( 20000 + RANDOM % 20000 ))
cat > "$TEST_DIR/sshd_config" <<EOF
Port $PORT
ListenAddress 127.0.0.1
HostKey $TEST_DIR/host_key
AuthorizedKeysFile $TEST_DIR/authorized_keys
PasswordAuthentication no
KbdInteractiveAuthentication no
UsePAM no
StrictModes no
PidFile $TEST_DIR/sshd.pid
Subsystem sftp internal-sftp
EOF
"$SSHD" -D -e -f "$TEST_DIR/sshd_config" 2>"$TEST_DIR/sshd.log" &
SSHD_PID=$!
for _ in $(seq 50); do
    (echo > "/dev/tcp/127.0.0.1/$PORT") 2>/dev/null && break
    sleep 0.1
done

mkdir "$TEST_DIR/classes" "$TEST_DIR/work"
javac -d "$TEST_DIR/classes" \
    "$PROJECT_DIR/app/src/main/java/com/tinyhack/ssh/ssh/SshTarget.java" \
    "$PROJECT_DIR/app/src/main/java/com/tinyhack/ssh/files/SftpClient.java" \
    "$PROJECT_DIR/app/src/main/java/com/tinyhack/ssh/files/RemoteConnection.java" \
    "$PROJECT_DIR/tests/RemoteBrowserTest.java"
if ! java -cp "$TEST_DIR/classes" RemoteBrowserTest "$(command -v ssh)" "$PORT" "$(id -un)" \
        "$TEST_DIR/client_key" "$TEST_DIR/known_hosts" "$TEST_DIR/work"; then
    echo "--- sshd log ---"
    cat "$TEST_DIR/sshd.log"
    exit 1
fi
# Listing, transfers and channel reopen all multiplexed over the first login;
# the two later logins are the deliberate master restarts at the end.
LOGINS=$(grep -c "Accepted publickey" "$TEST_DIR/sshd.log" || true)
if [ "$LOGINS" != 3 ]; then
    echo "FAIL: expected 3 ssh logins (1 shared + 2 restarts), sshd saw $LOGINS"
    exit 1
fi
echo "ok  browser channel and scp shared one login"
