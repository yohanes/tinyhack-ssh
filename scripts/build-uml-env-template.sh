#!/usr/bin/env bash
# Build a compressed environment template: scripts/build-uml-env-template.sh [debian|ubuntu]
# The result is build/uml/env-template/TYPE.ext4.gz. The app does not bundle
# templates: pin the printed entry in template_info() in
# app/src/main/assets/usr/libexec/uml-env, then publish the file with
# scripts/publish-uml-template.sh TYPE.
set -euo pipefail
PROJECT=$(cd "$(dirname "$0")/.." >/dev/null && pwd)
TYPE=${1:-debian}
WORK="$PROJECT/build/uml/env-template"
mkdir -p "$WORK"
# The static BusyBox comes from the Debian rootfs builder.
[[ -f "$PROJECT/build/uml/rootfs/bin/busybox" ]] || "$PROJECT/scripts/build-uml-rootfs.sh"
case "$TYPE" in
debian)
    # Reuse the pinned Debian export from the Debian builder.
    [[ -f "$PROJECT/build/uml/debian/base.tar" ]] || "$PROJECT/scripts/build-uml-debian.sh"
    BASE_TAR="$PROJECT/build/uml/debian/base.tar" HOST=debian-uml REMOVE=''
    ;;
ubuntu)
    # Ubuntu 26.04 LTS (resolute), pinned by manifest-list digest.
    IMAGE='ubuntu@sha256:6a06111c3b0a8600feadb270aec771dc239bf407b25625f93f44ab00e11f4d3c'
    UWORK="$PROJECT/build/uml/ubuntu"
    mkdir -p "$UWORK"
    if [[ ! -f "$UWORK/base.tar" ]]; then
        docker pull --platform linux/arm64 "$IMAGE"
        [[ $(docker image inspect "$IMAGE" --format '{{.Architecture}}') == arm64 ]]
        CONTAINER=$(docker create --platform linux/arm64 "$IMAGE" /bin/true)
        trap 'docker rm "$CONTAINER" >/dev/null' EXIT
        docker export "$CONTAINER" -o "$UWORK/base.tar.partial"
        mv "$UWORK/base.tar.partial" "$UWORK/base.tar"
        docker image inspect "$IMAGE" --format '{{.Id}} {{.Architecture}} {{json .RepoDigests}}' > "$UWORK/base-image.txt"
    fi
    BASE_TAR="$UWORK/base.tar" HOST=ubuntu-uml REMOVE=ubuntu
    ;;
*) echo "Unknown template type: $TYPE (debian or ubuntu)" >&2; exit 1 ;;
esac
REMOVE_USERS=$REMOVE fakeroot bash "$PROJECT/experiments/uml/debian/make-image.sh" \
    "$BASE_TAR" "$PROJECT/build/uml/rootfs/bin/busybox" \
    "$PROJECT/experiments/uml/debian/overlay" "$WORK/$TYPE.ext4" 256M "$HOST"
e2fsck -fn "$WORK/$TYPE.ext4"
gzip -1 -c "$WORK/$TYPE.ext4" > "$WORK/$TYPE.ext4.gz"
(cd "$WORK" && sha256sum "$TYPE.ext4.gz" > "SHA256SUMS.$TYPE")
SUM=$(cut -d' ' -f1 "$WORK/SHA256SUMS.$TYPE")
SIZE=$(stat -c %s "$WORK/$TYPE.ext4.gz")
echo "Template: $WORK/$TYPE.ext4.gz"
echo "template_info() entry for uml-env:"
echo "        $TYPE) echo '$TYPE-${SUM:0:16}.ext4.gzip $SUM $SIZE' ;;"
