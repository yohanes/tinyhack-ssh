#!/usr/bin/env bash
set -euo pipefail
PROJECT=$(cd "$(dirname "$0")/.." >/dev/null && pwd)
WORK="$PROJECT/build/uml/debian"
BASE='debian@sha256:f6b08b09a05725f9431502d3b5714f5c4576e82c1a84fa1debe0c24a7e0c3ec4'
mkdir -p "$WORK"
docker pull --platform linux/arm64 "$BASE"
[[ $(docker image inspect "$BASE" --format '{{.Architecture}}') == arm64 ]]
CONTAINER=$(docker create --platform linux/arm64 "$BASE" /bin/true)
trap 'docker rm "$CONTAINER" >/dev/null' EXIT
docker export "$CONTAINER" -o "$WORK/base.tar"
docker image inspect "$BASE" --format '{{.Id}} {{.Architecture}} {{json .RepoDigests}}' > "$WORK/base-image.txt"
"$PROJECT/scripts/build-uml-rootfs.sh"
fakeroot bash "$PROJECT/experiments/uml/debian/make-image.sh" \
    "$WORK/base.tar" "$PROJECT/build/uml/rootfs/bin/busybox" \
    "$PROJECT/experiments/uml/debian/overlay" "$WORK/debian.ext4" "${UML_DEBIAN_SIZE:-4G}"
e2fsck -fn "$WORK/debian.ext4" > "$WORK/fsck.txt" 2>&1
gzip -1 -c "$WORK/debian.ext4" > "$WORK/debian.ext4.gz"
(cd "$WORK" && sha256sum debian.ext4 debian.ext4.gz > SHA256SUMS)
cat "$WORK/SHA256SUMS"
