#!/usr/bin/env bash
# Publish UML environment templates as GitHub release assets.
#
#   scripts/publish-uml-template.sh debian|ubuntu|all          publish
#   scripts/publish-uml-template.sh --check debian|ubuntu|all  verify only
#   scripts/publish-uml-template.sh --stage debian|ubuntu|all  stage only
#
# The app does not bundle templates. uml-env downloads the file pinned in its
# template_info() from TEMPLATE_URL/uml-template-NAME/NAME.ext4.gzip and checks
# its SHA-256. Each template build gets its own release, never edited
# afterwards, so app versions that pin an older template keep working; the
# releases are not marked latest, so the app release stays the latest one.
#
# A release holds the image, SHA256SUMS, and NAME-source.tar with the image's
# corresponding source: the distribution source packages
# (scripts/fetch-debian-template-sources.py), the GNU libc sources and the
# BusyBox source and configuration of the static BusyBox in the image, and the
# build scripts.
#
# Publishing needs `gh` logged in with write access. --check downloads nothing
# but the headers; run it before every app release. --stage builds the
# release directory under build/uml/publish/ (a local test server can serve
# build/uml/publish as UML_TEMPLATE_URL).
set -euo pipefail
PROJECT=$(cd "$(dirname "$0")/.." >/dev/null && pwd)
UML_ENV="$PROJECT/app/src/main/assets/usr/libexec/uml-env"
WORK="$PROJECT/build/uml/env-template"
PUBLISH="$PROJECT/build/uml/publish"

mode=publish
case "${1:-}" in --check) mode=check; shift ;; --stage) mode=stage; shift ;; esac
[[ $# == 1 ]] || { sed -n '2,9p' "$0" | sed 's/^# \{0,1\}//' >&2; exit 2; }
case "$1" in all) types=(debian ubuntu) ;; debian|ubuntu) types=("$1") ;; *) echo "Unknown type: $1" >&2; exit 2 ;; esac

base_url=$(sed -n 's/^TEMPLATE_URL=\${UML_TEMPLATE_URL:-\(.*\)}$/\1/p' "$UML_ENV")
[[ $base_url =~ ^https://github\.com/([^/]+/[^/]+)/releases/download$ ]] ||
    { echo "Cannot read the GitHub repository from TEMPLATE_URL in $UML_ENV" >&2; exit 1; }
repo=${BASH_REMATCH[1]}

pin() {
    sed -n "s/^ *$1) echo '\\([^ ]*\\) \\([0-9a-f]\\{64\\}\\) \\([0-9]*\\)' ;;\$/\\1 \\2 \\3/p" "$UML_ENV"
}
label() {
    case "$1" in debian) echo 'Debian 13 (trixie)' ;; ubuntu) echo 'Ubuntu 26.04 LTS' ;; esac
}

# Last Content-Length after redirects; empty if the URL does not resolve.
remote_size() {
    { curl -sSfIL "$1" 2>/dev/null || true; } | tr -d '\r' |
        awk 'tolower($1) == "content-length:" { n = $2 } END { print n }'
}

stage() {
    local type=$1 file=$2 sum=$3 size=$4 dir=$5 name=${2%.ext4.gzip}
    local image="$WORK/$type.ext4.gz"
    [[ -f $image ]] || { echo "$image is missing; run scripts/build-uml-env-template.sh $type" >&2; exit 1; }
    [[ $(sha256sum "$image" | cut -d' ' -f1) == "$sum" && $(stat -c %s "$image") == "$size" ]] ||
        { echo "$image does not match the uml-env pin for $type ($file)" >&2; exit 1; }
    for tree in "build/uml/$type-sources" build/uml/glibc-sources build/uml/busybox-1.38.0 build/uml/busybox-build/.config; do
        [[ -e "$PROJECT/$tree" ]] || { echo "Missing $tree (see DEPLOY.md, UML sources)" >&2; exit 1; }
    done
    rm -rf "$dir"
    mkdir -p "$dir/src/$name-source/uml-busybox" "$dir/src/$name-source/build-recipe"
    ln "$image" "$dir/$file" 2>/dev/null || cp "$image" "$dir/$file"
    local src="$dir/src/$name-source"
    cp -a "$PROJECT/build/uml/$type-sources" "$src/$type-packages"
    cp -a "$PROJECT/build/uml/glibc-sources" "$src/uml-glibc"
    tar -C "$PROJECT/build/uml/busybox-1.38.0" --exclude='*.o' --exclude='*.a' --exclude='*.cmd' \
        --exclude='busybox' --exclude='busybox_unstripped*' -cf - . | tar -C "$src/uml-busybox" -xf -
    cp "$PROJECT/build/uml/busybox-build/.config" "$src/uml-busybox/.config"
    git -C "$PROJECT" archive HEAD scripts/build-uml-env-template.sh scripts/build-uml-rootfs.sh \
        scripts/build-uml-debian.sh scripts/fetch-debian-template-sources.py experiments/uml/debian \
        THIRD-PARTY-NOTICES.md | tar -C "$src/build-recipe" -xf -
    cat > "$src/SOURCE-MANIFEST.txt" <<EOF
Corresponding source of the Tinyhack SSH $(label "$type") UML template
Image: $file
SHA-256: $sum
Tinyhack SSH commit: $(git -C "$PROJECT" rev-parse HEAD)

$type-packages/  Source packages (and SHA256SUMS) of every package in the image.
uml-busybox/     BusyBox 1.38.0 source; .config builds the image's static /bin/busybox.
uml-glibc/       GNU libc sources linked into that static BusyBox.
build-recipe/    The scripts that build the image: scripts/build-uml-env-template.sh $type.

Packages in the image keep their copyright notices under /usr/share/doc.
Complete source of the app is attached to each Tinyhack SSH release at
https://github.com/$repo/releases.
Contact: tinyhack-ssh@tinyhack.com
EOF
    tar -C "$dir/src" -cf "$dir/$name-source.tar" "$name-source"
    rm -rf "$dir/src"
    (cd "$dir" && sha256sum "$file" "$name-source.tar" > SHA256SUMS)
    echo "Staged $dir"
}

failed=0
for type in "${types[@]}"; do
    read -r file sum size < <(pin "$type") || true
    [[ -n ${file:-} ]] || { echo "No template_info() entry for $type in $UML_ENV" >&2; exit 1; }
    name=${file%.ext4.gzip}
    tag="uml-template-$name"
    url="$base_url/$tag/$file"
    dir="$PUBLISH/$tag"
    case $mode in
    check)
        got=$(remote_size "$url")
        if [[ $got == "$size" ]]; then echo "ok   $url ($size bytes)"; else echo "FAIL $url (size '${got}', pinned $size)"; failed=1; fi
        ;;
    stage) stage "$type" "$file" "$sum" "$size" "$dir" ;;
    publish)
        if gh release view "$tag" -R "$repo" >/dev/null 2>&1; then
            echo "$tag is already published"
        else
            stage "$type" "$file" "$sum" "$size" "$dir"
            notes="$(label "$type") system image for Tinyhack SSH Linux environments (User-Mode Linux).
The app downloads it when it creates the first $(label "$type") environment and checks its SHA-256; it is not meant to be used on its own.

- \`$file\`: compressed 256 MB ext4 image, SHA-256 \`$sum\`
- \`$name-source.tar\`: its corresponding source (see SOURCE-MANIFEST.txt inside)

Packages in the image keep their copyright and license notices under \`/usr/share/doc\`. See THIRD-PARTY-NOTICES.md in the repository for the components and their licenses."
            gh release create "$tag" -R "$repo" --title "UML template: $(label "$type") ($name)" \
                --notes "$notes" --latest=false \
                "$dir/$file" "$dir/$name-source.tar" "$dir/SHA256SUMS"
        fi
        got=$(remote_size "$url")
        if [[ $got == "$size" ]]; then echo "ok   $url"; else echo "FAIL $url is not downloadable (size '${got}')"; failed=1; fi
        ;;
    esac
done
exit $failed
