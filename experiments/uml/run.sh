#!/bin/bash
set -euo pipefail
NATIVE=$(dirname "$(readlink /proc/$$/exe)")
cd "$HOME/uml"
mkdir -p tmp
export TMPDIR="$HOME/uml/tmp"
{
    echo "Android host: $(uname -r)"
    echo "Host security context: $(tr -d '\000' < /proc/self/attr/current)"
    id
} > "$HOME/uml/host.txt"
cat "$HOME/uml/host.txt"
ROOTFS=${UML_ROOTFS:-$HOME/uml/rootfs.ext4}
MEMORY=${UML_MEMORY:-256M}
[[ -f "$ROOTFS" ]] || { echo "Missing guest disk: $ROOTFS" >&2; exit 1; }
echo "Starting ARM64 UML guest ($MEMORY RAM): $ROOTFS"
command=("$NATIVE/libuml.so" "mem=$MEMORY" "ubd0=$ROOTFS" root=/dev/ubda rw init=/init con=null con0=fd:0,fd:1 "stub_exe=$NATIVE/libuml-stub.so")
if [[ ${UML_NETWORK:-1} == 1 && -f "$NATIVE/libuml-net.so" ]]; then
    # Android already supplies the app sandbox; passt cannot create namespaces.
    export PASST_NO_SANDBOX=1
    command=("$NATIVE/libuml-net.so" --passt "$NATIVE/libuml-passt.so" -- "${command[@]}")
fi
printf -v quoted '%q ' "${command[@]}"
script -q -c "$quoted" "${UML_CONSOLE_LOG:-$HOME/uml/console.log}"
