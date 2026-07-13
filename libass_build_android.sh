#!/bin/bash
# Build libass and its dependencies for Android arm64-v8a.
# Outputs shared libs to app/src/main/jniLibs/arm64-v8a/
# and headers to app/src/main/cpp/include/ass/
#
# Dependencies built (in order):
#   expat -> freetype -> fribidi -> harfbuzz -> fontconfig -> libass
#
# Usage: ./libass_build_android.sh --ndk-path /path/to/ndk [--abi arm64-v8a]

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# ----- argument parsing -------------------------------------------------------
ABI="arm64-v8a"
NDK_PATH=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --abi)      ABI="$2";      shift 2 ;;
    --ndk-path) NDK_PATH="$2"; shift 2 ;;
    *)          shift ;;
  esac
done

if [[ -z "$NDK_PATH" ]]; then
  [[ -n "${ANDROID_NDK_ROOT:-}" ]] && NDK_PATH="$ANDROID_NDK_ROOT"
fi
if [[ -z "$NDK_PATH" || ! -d "$NDK_PATH" ]]; then
  if [[ -n "${ANDROID_HOME:-}" && -d "$ANDROID_HOME/ndk" ]]; then
    NDK_PATH=$(ls -d "$ANDROID_HOME/ndk"/* 2>/dev/null | head -1)
  fi
fi
if [[ -z "$NDK_PATH" || ! -d "$NDK_PATH" ]]; then
  echo "ERROR: NDK not found. Set ANDROID_NDK_ROOT or pass --ndk-path." >&2
  exit 1
fi

# ----- toolchain setup --------------------------------------------------------
TC_HOST="linux-x86_64"
TC="$NDK_PATH/toolchains/llvm/prebuilt/$TC_HOST/bin"
SYSROOT="$NDK_PATH/toolchains/llvm/prebuilt/$TC_HOST/sysroot"

case "$ABI" in
  arm64-v8a)
    API=28
    TRIPLE="aarch64-linux-android"
    ;;
  *)
    echo "ERROR: unsupported ABI '$ABI'" >&2; exit 1 ;;
esac

export CC="$TC/${TRIPLE}${API}-clang"
export CXX="$TC/${TRIPLE}${API}-clang++"
export AR="$TC/llvm-ar"
export RANLIB="$TC/llvm-ranlib"
export STRIP="$TC/llvm-strip"
export NM="$TC/llvm-nm"
export LD="$TC/ld"

CFLAGS="-fPIC -Os --sysroot=$SYSROOT -target ${TRIPLE}${API}"
export CFLAGS
export CXXFLAGS="$CFLAGS"
export LDFLAGS="--sysroot=$SYSROOT -target ${TRIPLE}${API}"

BUILD_DIR="$SCRIPT_DIR/libass_build_tmp"
PREFIX="$BUILD_DIR/prefix"
JNILIBS_DIR="$SCRIPT_DIR/app/src/main/jniLibs/$ABI"
HEADERS_DIR="$SCRIPT_DIR/app/src/main/cpp/include"

mkdir -p "$PREFIX/lib" "$PREFIX/include" "$JNILIBS_DIR" "$HEADERS_DIR/ass"

export PKG_CONFIG_PATH="$PREFIX/lib/pkgconfig"
export PKG_CONFIG_LIBDIR="$PREFIX/lib/pkgconfig"

# Versions
EXPAT_VER="2.6.4"
FREETYPE_VER="2.13.3"
FRIBIDI_VER="1.0.16"
HARFBUZZ_VER="10.2.0"
FONTCONFIG_VER="2.15.0"
LIBASS_VER="0.17.3"

cd "$BUILD_DIR"

# ------------------------------------------------------------------------------
echo "=== [1/6] Building expat $EXPAT_VER ==="
if [ ! -f "expat-${EXPAT_VER}.tar.gz" ]; then
  curl -fLo "expat-${EXPAT_VER}.tar.gz" \
    "https://github.com/libexpat/libexpat/releases/download/R_${EXPAT_VER//./_}/expat-${EXPAT_VER}.tar.gz"
fi
tar -xzf "expat-${EXPAT_VER}.tar.gz"
pushd "expat-${EXPAT_VER}"
./configure --host="$TRIPLE" --prefix="$PREFIX" \
  --enable-shared --disable-static \
  --without-docbook \
  CFLAGS="$CFLAGS" LDFLAGS="$LDFLAGS"
make -j$(nproc)
make install
popd

# ------------------------------------------------------------------------------
echo "=== [2/6] Building freetype $FREETYPE_VER ==="
if [ ! -f "freetype-${FREETYPE_VER}.tar.gz" ]; then
  curl -fLo "freetype-${FREETYPE_VER}.tar.gz" \
    "https://download.savannah.gnu.org/releases/freetype/freetype-${FREETYPE_VER}.tar.gz"
fi
tar -xzf "freetype-${FREETYPE_VER}.tar.gz"
pushd "freetype-${FREETYPE_VER}"
./configure --host="$TRIPLE" --prefix="$PREFIX" \
  --enable-shared --disable-static \
  --without-zlib --without-bzip2 --without-png \
  --with-harfbuzz=no \
  CFLAGS="$CFLAGS" LDFLAGS="$LDFLAGS"
make -j$(nproc)
make install
popd

# ------------------------------------------------------------------------------
echo "=== [3/6] Building fribidi $FRIBIDI_VER ==="
if [ ! -f "fribidi-${FRIBIDI_VER}.tar.xz" ]; then
  curl -fLo "fribidi-${FRIBIDI_VER}.tar.xz" \
    "https://github.com/fribidi/fribidi/releases/download/v${FRIBIDI_VER}/fribidi-${FRIBIDI_VER}.tar.xz"
fi
tar -xf "fribidi-${FRIBIDI_VER}.tar.xz"
pushd "fribidi-${FRIBIDI_VER}"
./configure --host="$TRIPLE" --prefix="$PREFIX" \
  --enable-shared --disable-static \
  --disable-docs \
  CFLAGS="$CFLAGS" LDFLAGS="$LDFLAGS"
make -j$(nproc)
make install
popd

# ------------------------------------------------------------------------------
echo "=== [4/6] Building harfbuzz $HARFBUZZ_VER ==="
if [ ! -f "harfbuzz-${HARFBUZZ_VER}.tar.gz" ]; then
  curl -fLo "harfbuzz-${HARFBUZZ_VER}.tar.gz" \
    "https://github.com/harfbuzz/harfbuzz/releases/download/${HARFBUZZ_VER}/harfbuzz-${HARFBUZZ_VER}.tar.xz"
fi
tar -xf "harfbuzz-${HARFBUZZ_VER}.tar.gz"
pushd "harfbuzz-${HARFBUZZ_VER}"
mkdir -p build && cd build
# HarfBuzz uses meson
pip3 install --quiet meson ninja
meson setup .. \
  --cross-file <(cat << EOF
[binaries]
c     = '$CC'
cpp   = '$CXX'
ar    = '$TC/llvm-ar'
strip = '$TC/llvm-strip'
pkgconfig = 'pkg-config'

[properties]
pkg_config_libdir = '$PREFIX/lib/pkgconfig'

[host_machine]
system     = 'android'
cpu_family = 'aarch64'
cpu        = 'aarch64'
endian     = 'little'
EOF
) \
  --prefix="$PREFIX" \
  --libdir=lib \
  --default-library=shared \
  -Dtests=disabled \
  -Ddocs=disabled \
  -Dbenchmark=disabled \
  -Dfreetype=enabled \
  -Dglib=disabled \
  -Dgobject=disabled \
  -Dintrospection=disabled \
  --buildtype=release
ninja -j$(nproc)
ninja install
popd

# ------------------------------------------------------------------------------
echo "=== [5/6] Building fontconfig $FONTCONFIG_VER ==="
if [ ! -f "fontconfig-${FONTCONFIG_VER}.tar.gz" ]; then
  curl -fLo "fontconfig-${FONTCONFIG_VER}.tar.gz" \
    "https://www.freedesktop.org/software/fontconfig/release/fontconfig-${FONTCONFIG_VER}.tar.gz"
fi
tar -xzf "fontconfig-${FONTCONFIG_VER}.tar.gz"
pushd "fontconfig-${FONTCONFIG_VER}"
./configure --host="$TRIPLE" --prefix="$PREFIX" \
  --enable-shared --disable-static \
  --disable-docs \
  --with-expat="$PREFIX" \
  CFLAGS="$CFLAGS" LDFLAGS="$LDFLAGS -L$PREFIX/lib" \
  FREETYPE_CFLAGS="-I$PREFIX/include/freetype2 -I$PREFIX/include" \
  FREETYPE_LIBS="-L$PREFIX/lib -lfreetype"
make -j$(nproc)
make install
popd

# ------------------------------------------------------------------------------
echo "=== [6/6] Building libass $LIBASS_VER ==="
if [ ! -f "libass-${LIBASS_VER}.tar.gz" ]; then
  curl -fLo "libass-${LIBASS_VER}.tar.gz" \
    "https://github.com/libass/libass/releases/download/${LIBASS_VER}/libass-${LIBASS_VER}.tar.gz"
fi
tar -xzf "libass-${LIBASS_VER}.tar.gz"
pushd "libass-${LIBASS_VER}"
./configure --host="$TRIPLE" --prefix="$PREFIX" \
  --enable-shared --disable-static \
  --disable-asm \
  --enable-fontconfig \
  CFLAGS="$CFLAGS -I$PREFIX/include -I$PREFIX/include/freetype2" \
  LDFLAGS="$LDFLAGS -L$PREFIX/lib" \
  FREETYPE_CFLAGS="-I$PREFIX/include/freetype2" \
  FREETYPE_LIBS="-L$PREFIX/lib -lfreetype" \
  FRIBIDI_CFLAGS="-I$PREFIX/include/fribidi" \
  FRIBIDI_LIBS="-L$PREFIX/lib -lfribidi" \
  HARFBUZZ_CFLAGS="-I$PREFIX/include/harfbuzz" \
  HARFBUZZ_LIBS="-L$PREFIX/lib -lharfbuzz" \
  FONTCONFIG_CFLAGS="-I$PREFIX/include/fontconfig" \
  FONTCONFIG_LIBS="-L$PREFIX/lib -lfontconfig"
make -j$(nproc)
make install
popd

# ------------------------------------------------------------------------------
echo "=== Copying .so files and headers ==="

LIBS=(libass libfontconfig libexpat libfreetype libfribidi libharfbuzz)
for lib in "${LIBS[@]}"; do
  so_file=$(find "$PREFIX/lib" -maxdepth 1 -name "${lib}.so" | head -n 1)
  if [ -f "$so_file" ]; then
    cp "$so_file" "$JNILIBS_DIR/"
    "$STRIP" --strip-all "$JNILIBS_DIR/${lib}.so" 2>/dev/null || true
    echo "  copied $lib.so"
  else
    echo "  WARNING: $lib.so not found in $PREFIX/lib" >&2
  fi
done

# Copy libass headers for JNI compilation
cp "$PREFIX/include/ass/ass.h"       "$HEADERS_DIR/ass/" 2>/dev/null || true
cp "$PREFIX/include/ass/ass_types.h" "$HEADERS_DIR/ass/" 2>/dev/null || true

echo "=== libass build complete ==="
