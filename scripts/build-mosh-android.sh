#!/usr/bin/env bash
# Cross-build mosh-client (and the native `mosh` launcher) for Android arm64.
#
# Produces:
#   app/src/main/jniLibs/arm64-v8a/libmosh-client.so  (mosh 1.4.0 client)
#   app/src/main/jniLibs/arm64-v8a/libmosh.so         (native mosh.pl replacement)
#
# Dependency stack (all statically linked into the binaries):
#   zlib 1.3.2, ncurses 6.6 (tinfo only), protobuf 36.2 + Abseil (host protoc + arm64 lib)
set -euo pipefail

PROJECT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." >/dev/null && pwd)
NDK_DIR=${MOSH_NDK_DIR:-/home/yohanes/Android/Sdk/ndk/26.1.10909125}
TOOLCHAIN="$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64/bin"
BUILD="$PROJECT_DIR/build-mosh"
HOST_PREFIX="$BUILD/host-install-36.2"
TGT_PREFIX="$BUILD/install-36.2"
MOSH_SRC="$PROJECT_DIR/mosh"
JOBS=${JOBS:-$(nproc)}

ZLIB_VERSION=1.3.2
NCURSES_VERSION=6.6
PROTOBUF_VERSION=36.2
ABSEIL_VERSION=20250512.1

ZLIB_SHA256=bb329a0a2cd0274d05519d61c667c062e06990d72e125ee2dfa8de64f0119d16
NCURSES_SHA256=355b4cbbed880b0381a04c46617b7656e362585d52e9cf84a67e2009b749ff11
PROTOBUF_SHA256=3d9642a662d10e68ebae5e53f14dcce5105684212d5078f8e0d47d1ab3ae6b64
ABSEIL_SHA256=9b7a064305e9fd94d124ffa6cc358592eb42b5da588fb4e07d09254aa40086db

export PATH="$TOOLCHAIN:$PATH"
unset ANDROID_NDK_ROOT ANDROID_NDK_HOME
export ANDROID_NDK_ROOT="$NDK_DIR"
CC=aarch64-linux-android34-clang
CXX=aarch64-linux-android34-clang++

mkdir -p "$BUILD"

fetch() { # fetch <name> <sha256> <url>
    local file="$BUILD/$1"
    if [[ ! -f "$file" ]]; then
        echo "Downloading $1..."
        curl -fL "$3" -o "$file"
    fi
    printf '%s  %s\n' "$2" "$file" | sha256sum -c -
}

# ---------------------------------------------------------------- zlib (target)
if [[ ! -f "$TGT_PREFIX/lib/libz.a" ]]; then
    fetch "zlib-$ZLIB_VERSION.tar.gz" "$ZLIB_SHA256" \
        "https://github.com/madler/zlib/releases/download/v$ZLIB_VERSION/zlib-$ZLIB_VERSION.tar.gz"
    rm -rf "$BUILD/zlib-$ZLIB_VERSION"
    tar -xzf "$BUILD/zlib-$ZLIB_VERSION.tar.gz" -C "$BUILD"
    rm -rf "$BUILD/zlib-build"
    cmake -S "$BUILD/zlib-$ZLIB_VERSION" -B "$BUILD/zlib-build" \
        -DCMAKE_TOOLCHAIN_FILE="$NDK_DIR/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-34 \
        -DCMAKE_BUILD_TYPE=Release \
        -DBUILD_SHARED_LIBS=OFF -DZLIB_BUILD_EXAMPLES=OFF \
        -DCMAKE_INSTALL_PREFIX="$TGT_PREFIX"
    cmake --build "$BUILD/zlib-build" --parallel "$JOBS"
    cmake --install "$BUILD/zlib-build"
fi

# ------------------------------------------------------------ ncurses (target)
# tinfo only: mosh-client just needs tigetstr()/setupterm(). The terminfo
# database itself ships in app assets (files/usr/share/terminfo).
if [[ ! -f "$TGT_PREFIX/lib/libtinfo.a" ]]; then
    fetch "ncurses-$NCURSES_VERSION.tar.gz" "$NCURSES_SHA256" \
        "https://ftp.gnu.org/gnu/ncurses/ncurses-$NCURSES_VERSION.tar.gz"
    rm -rf "$BUILD/ncurses-$NCURSES_VERSION"
    tar -xzf "$BUILD/ncurses-$NCURSES_VERSION.tar.gz" -C "$BUILD"
    (
        cd "$BUILD/ncurses-$NCURSES_VERSION"
        CC="$CC" CXX="$CXX" AR=llvm-ar RANLIB=llvm-ranlib \
        CFLAGS='-O2 -fPIE' LDFLAGS='-pie -Wl,-z,max-page-size=16384' \
        ./configure --host=aarch64-linux-android --prefix="$TGT_PREFIX" \
            --disable-shared --enable-static --disable-widec \
            --with-termlib \
            --without-progs --without-tests --without-manpages --without-ada \
            --without-cxx --without-cxx-binding --without-gpm \
            --disable-db-install --without-debug \
            --with-default-terminfo-dir=/data/data/com.tinyhack.ssh/files/usr/share/terminfo \
            --with-fallbacks=xterm,xterm-color,vt100,vt220,linux \
            --enable-pc-files --with-pkg-config-libdir="$TGT_PREFIX/lib/pkgconfig"
        make -j"$JOBS" libs
        make install libs
    )
fi

# ---------------------------------------------------------- protobuf (host+arm)
# Modern protobuf uses CMake and statically links Abseil and utf8_range.
fetch "protobuf-$PROTOBUF_VERSION.tar.gz" "$PROTOBUF_SHA256" \
    "https://github.com/protocolbuffers/protobuf/releases/download/v$PROTOBUF_VERSION/protobuf-$PROTOBUF_VERSION.tar.gz"
fetch "abseil-cpp-$ABSEIL_VERSION.tar.gz" "$ABSEIL_SHA256" \
    "https://github.com/abseil/abseil-cpp/archive/refs/tags/$ABSEIL_VERSION.tar.gz"
[[ -d "$BUILD/protobuf-$PROTOBUF_VERSION" ]] || tar -xzf "$BUILD/protobuf-$PROTOBUF_VERSION.tar.gz" -C "$BUILD"
[[ -d "$BUILD/abseil-cpp-$ABSEIL_VERSION" ]] || tar -xzf "$BUILD/abseil-cpp-$ABSEIL_VERSION.tar.gz" -C "$BUILD"
protobuf_options=(
    -DCMAKE_BUILD_TYPE=Release -DCMAKE_CXX_STANDARD=17
    -Dprotobuf_BUILD_TESTS=OFF -Dprotobuf_BUILD_SHARED_LIBS=OFF
    -Dprotobuf_WITH_ZLIB=OFF -Dprotobuf_FORCE_FETCH_DEPENDENCIES=ON
    -DFETCHCONTENT_SOURCE_DIR_ABSL="$BUILD/abseil-cpp-$ABSEIL_VERSION"
    -DCMAKE_INSTALL_LIBDIR=lib
)
if [[ ! -x "$HOST_PREFIX/bin/protoc" ]]; then
    cmake -S "$BUILD/protobuf-$PROTOBUF_VERSION" -B "$BUILD/protobuf-host-36.2" \
        "${protobuf_options[@]}" -DCMAKE_INSTALL_PREFIX="$HOST_PREFIX"
    cmake --build "$BUILD/protobuf-host-36.2" --parallel "$JOBS"
    cmake --install "$BUILD/protobuf-host-36.2"
fi
if [[ ! -f "$TGT_PREFIX/lib/libprotobuf.a" ]]; then
    # NDK r26's Clang crashes on protobuf's statement-level always_inline
    # attribute. Disable that optional optimization in the runtime and users.
    cmake -S "$BUILD/protobuf-$PROTOBUF_VERSION" -B "$BUILD/protobuf-target-36.2" \
        "${protobuf_options[@]}" -DCMAKE_INSTALL_PREFIX="$TGT_PREFIX" \
        -DCMAKE_CXX_FLAGS=-DPROTOBUF_NO_INLINE_CALL \
        -DCMAKE_TOOLCHAIN_FILE="$NDK_DIR/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-34 \
        -DANDROID_STL=c++_static -Dprotobuf_BUILD_PROTOC_BINARIES=OFF \
        -Dprotobuf_BUILD_LIBPROTOC=OFF -DCMAKE_POSITION_INDEPENDENT_CODE=ON
    cmake --build "$BUILD/protobuf-target-36.2" --parallel "$JOBS"
    cmake --install "$BUILD/protobuf-target-36.2"
fi

# ---------------------------------------------------------------- mosh-client
OPENSSL_PREFIX="$PROJECT_DIR/build-openssl/install"   # shared with build-openssh-android.sh
(
    cd "$MOSH_SRC"
    if [[ -f "$PROJECT_DIR/patches/mosh-android.patch" ]]; then
        patch --forward -p1 < "$PROJECT_DIR/patches/mosh-android.patch" || true
    fi
    make distclean >/dev/null 2>&1 || true
    ./autogen.sh 2>/dev/null || true

    # Keep pkg-config away from the host's ncurses/protobuf/openssl
    export PKG_CONFIG="pkg-config"
    export PKG_CONFIG_LIBDIR="$TGT_PREFIX/lib/pkgconfig:$OPENSSL_PREFIX/lib/pkgconfig"
    unset PKG_CONFIG_PATH || true

    CC="$CC" CXX="$CXX" AR=llvm-ar RANLIB=llvm-ranlib STRIP=llvm-strip \
    PROTOC="$HOST_PREFIX/bin/protoc" \
    CFLAGS="-O2 -fPIE -D__ANDROID__ -I$OPENSSL_PREFIX/include" \
    CXXFLAGS="-O2 -fPIE -D__ANDROID__ -DPROTOBUF_NO_INLINE_CALL -I$OPENSSL_PREFIX/include" \
    LDFLAGS="-pie -Wl,-z,max-page-size=16384 -static-libstdc++ -L$TGT_PREFIX/lib -L$OPENSSL_PREFIX/lib -llog" \
    protobuf_CFLAGS="-I$TGT_PREFIX/include" \
    protobuf_LIBS="$(PKG_CONFIG_LIBDIR="$TGT_PREFIX/lib/pkgconfig" pkg-config --static --libs protobuf)" \
    ./configure --host=aarch64-linux-android \
        --prefix=/data/data/com.tinyhack.ssh/files/usr \
        --disable-server --disable-examples --disable-completion \
        --disable-ufw --disable-syslog --disable-hardening \
        --with-crypto-library=openssl \
        --enable-static-zlib --with-zlib="$TGT_PREFIX" \
        --with-curses="$TGT_PREFIX"

    # Regenerate all protocol messages with the matching host compiler.
    rm -f src/protobufs/*.pb.cc src/protobufs/*.pb.h
    make -j"$JOBS"

    OUT="$PROJECT_DIR/app/src/main/jniLibs/arm64-v8a"
    mkdir -p "$OUT"
    llvm-strip src/frontend/mosh-client -o "$OUT/libmosh-client.so"
)

# ---------------------------------------------------------------- mosh launcher
"$CXX" -O2 -fPIE -pie -Wl,-z,max-page-size=16384 -std=c++17 -static-libstdc++ -Wall \
    "$PROJECT_DIR/scripts/mosh-launcher.cc" \
    -o "$PROJECT_DIR/app/src/main/jniLibs/arm64-v8a/libmosh.so"
llvm-strip "$PROJECT_DIR/app/src/main/jniLibs/arm64-v8a/libmosh.so"

echo "Done:"
ls -la "$PROJECT_DIR/app/src/main/jniLibs/arm64-v8a/" | grep mosh
