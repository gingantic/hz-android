#!/bin/bash
# Build FFmpeg for Android arm64-v8a (or x86_64 for emulator).
# Outputs shared libs to app/src/main/jniLibs/<abi>/
#
# Usage: ./ffmpeg_build_android.sh --ndk-path /path/to/ndk [--abi arm64-v8a]

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# ----- args --------------------------------------------------------------------
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
  # Fallback: find highest NDK under ANDROID_HOME (GitHub runner)
  if [[ -n "${ANDROID_HOME:-}" && -d "$ANDROID_HOME/ndk" ]]; then
    NDK_PATH=$(ls -d "$ANDROID_HOME/ndk"/* 2>/dev/null | sort -V | tail -n 1)
  fi
fi
if [[ -z "$NDK_PATH" || ! -d "$NDK_PATH" ]]; then
  echo "ERROR: NDK not found. Set ANDROID_NDK_ROOT or pass --ndk-path." >&2
  exit 1
fi

# ----- toolchain host detection ------------------------------------------------
if [[ -n "${WSL_DISTRO_NAME:-}" || -f /proc/sys/fs/binfmt_misc/WSLInterop ]]; then
  TC_HOST="windows-x86_64"
else
  TC_HOST="linux-x86_64"
fi
NDK_BASE="$NDK_PATH"
TC="$NDK_BASE/toolchains/llvm/prebuilt/$TC_HOST/bin"
SYSROOT="$NDK_BASE/toolchains/llvm/prebuilt/$TC_HOST/sysroot"

# ----- per-abi -----------------------------------------------------------------
case "$ABI" in
  arm64-v8a) TARGET_ARCH=arm64;  CROSS="aarch64-linux-android-"; CLANG="aarch64-linux-android28-clang"; CLANGPP="aarch64-linux-android28-clang++" ;;
  x86_64)    TARGET_ARCH=x86_64; CROSS="x86_64-linux-android-";  CLANG="x86_64-linux-android28-clang";  CLANGPP="x86_64-linux-android28-clang++"  ;;
  *) echo "ERROR: unsupported ABI '$ABI' (use arm64-v8a or x86_64)" >&2; exit 1 ;;
esac

# ----- WSL wrappers (Windows ld.lld can't handle Linux SONAME symlinks) --------
if [[ "$TC_HOST" == "windows-x86_64" ]]; then
  WRAPPER_DIR="/tmp/ndk-wrappers"; mkdir -p "$WRAPPER_DIR"
  clang_real="$TC/$CLANG"
  clangpp_real="$TC/$CLANGPP"
  cat > "$WRAPPER_DIR/$CLANG" << WRAPEOF
#!/bin/bash
ARGS=()
for a in "\$@"; do
  if [[ "\$a" == *=/mnt/c/* ]]; then
    prefix="\${a%%=*}"; path="\${a#*=}"; converted="\$(wslpath -w "\$path")"; ARGS+=("\$prefix=\$converted")
  elif [[ "\$a" == /mnt/c/* ]]; then
    ARGS+=("\$(wslpath -w "\$a")")
  else
    ARGS+=("\$a")
  fi
done
exec "$clang_real" "\${ARGS[@]}"
WRAPEOF
  # clang++ wrapper (same logic)
  cat > "$WRAPPER_DIR/$CLANGPP" << WRAPEOF
#!/bin/bash
ARGS=()
for a in "\$@"; do
  if [[ "\$a" == *=/mnt/c/* ]]; then
    prefix="\${a%%=*}"; path="\${a#*=}"; converted="\$(wslpath -w "\$path")"; ARGS+=("\$prefix=\$converted")
  elif [[ "\$a" == /mnt/c/* ]]; then
    ARGS+=("\$(wslpath -w "\$a")")
  else
    ARGS+=("\$a")
  fi
done
exec "$clangpp_real" "\${ARGS[@]}"
WRAPEOF
  for tool in aarch64-linux-android-{as,ld,objcopy,objdump,readelf} llvm-{ar,nm,strip,ranlib,objcopy,objdump,readobj}; do
    real="$TC/$tool"; [[ -f "$real" ]] && ln -sf "$real" "$WRAPPER_DIR/$tool" 2>/dev/null || true
  done
  ln -sf "$WRAPPER_DIR/$CLANG" "$WRAPPER_DIR/aarch64-linux-android-ld" 2>/dev/null || true
  chmod +x "$WRAPPER_DIR/"*
  export PATH="$WRAPPER_DIR:$PATH"
else
  export PATH="$TC:$PATH"
fi

# ccache wrapper (guarded so local builds without ccache still work).
CCACHE_BIN="$(command -v ccache || true)"
CC_LAUNCHER="${CCACHE_BIN:+ccache }"

# ----- Build dav1d dependency --------------------------------------------------
DAV1D_VER="1.5.1"
BUILD_TMP="$SCRIPT_DIR/ffmpeg_build_tmp"
PREFIX="$BUILD_TMP/prefix"
mkdir -p "$PREFIX/lib/pkgconfig" "$PREFIX/include" "$BUILD_TMP"

echo "=== Building dav1d $DAV1D_VER for $ABI ==="
DAV1D_DIR="$BUILD_TMP/dav1d-$DAV1D_VER"
if [[ ! -d "$DAV1D_DIR" ]]; then
  DAV1D_TAR="$BUILD_TMP/dav1d-$DAV1D_VER.tar.gz"
  if [[ ! -f "$DAV1D_TAR" ]]; then
    curl -sSfL "https://code.videolan.org/videolan/dav1d/-/archive/$DAV1D_VER/dav1d-$DAV1D_VER.tar.gz" -o "$DAV1D_TAR"
  fi
  tar -xzf "$DAV1D_TAR" -C "$BUILD_TMP"
fi

cat > "$BUILD_TMP/cross_$ABI.meson" <<EOF
[binaries]
c = '$TC/$CLANG'
cpp = '$TC/$CLANGPP'
ar = '$TC/llvm-ar'
strip = '$TC/llvm-strip'
pkg-config = 'pkg-config'

[host_machine]
system = 'android'
cpu_family = '$TARGET_ARCH'
cpu = 'armv8-a'
endian = 'little'
EOF

cd "$DAV1D_DIR"
rm -rf build
meson setup build \
  --cross-file "$BUILD_TMP/cross_$ABI.meson" \
  --prefix "$PREFIX" \
  --libdir "$PREFIX/lib" \
  --buildtype release \
  --default-library both \
  -Denable_tools=false \
  -Denable_tests=false \
  -Denable_examples=false \
  -Denable_asm=true

ninja -C build install

# ----- FFmpeg source -----------------------------------------------------------
# CI sets FFMPEG_SRC_DIR (git clone); local fallback downloads tarball.
if [[ -n "${FFMPEG_SRC_DIR:-}" && -d "$FFMPEG_SRC_DIR" ]]; then
  FFMPEG_DIR="$(cd "$FFMPEG_SRC_DIR" && pwd)"
  echo "=== Using FFmpeg source from $FFMPEG_DIR ==="
else
  FFMPEG_VER="7.1"
  FFMPEG_DIR="$SCRIPT_DIR/ffmpeg-${FFMPEG_VER}"
  FFMPEG_TAR="$SCRIPT_DIR/ffmpeg-${FFMPEG_VER}.tar.xz"
  if [[ ! -d "$FFMPEG_DIR" ]]; then
    if [[ ! -f "$FFMPEG_TAR" ]]; then
      echo "=== Downloading FFmpeg $FFMPEG_VER ==="
      curl -fLo "$FFMPEG_TAR" \
        "https://ffmpeg.org/releases/ffmpeg-${FFMPEG_VER}.tar.xz"
    fi
    echo "=== Extracting FFmpeg $FFMPEG_VER ==="
    tar -xf "$FFMPEG_TAR" -C "$SCRIPT_DIR"
  fi
fi

cd "$FFMPEG_DIR"
export TMPDIR=/tmp
# Only clean on explicit force; otherwise keep prior .o files so `make` is
# incremental across CI runs (ffmpeg-src is cached with its build objects).
[ -n "${FFMPEG_FORCE_REBUILD:-}" ] && make clean 2>/dev/null || true

export PKG_CONFIG_PATH="$PREFIX/lib/pkgconfig"
export PKG_CONFIG_LIBDIR="$PREFIX/lib/pkgconfig"

echo "=== Configuring FFmpeg for $ABI ==="
./configure \
  --target-os=android --arch=$TARGET_ARCH \
  --enable-shared --disable-static \
  --disable-programs --disable-doc \
  --disable-avdevice --disable-avfilter --disable-network \
  --enable-small --enable-pic \
  --disable-encoders --disable-hwaccels --disable-muxers \
  --enable-libdav1d \
  --enable-decoder=h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1,libdav1d \
  --enable-demuxer=matroska,mov,avi,mp4,mpegts,flv \
  --enable-protocol=file --enable-swscale --enable-swresample \
  --cross-prefix=$CROSS \
  --cc="${CC_LAUNCHER}${CLANG}" \
  --cxx="${CC_LAUNCHER}${CLANGPP}" \
  --ar=llvm-ar --nm=llvm-nm \
  --strip=llvm-strip --ranlib=llvm-ranlib \
  --sysroot="$SYSROOT" \
  --pkg-config=pkg-config \
  --pkg-config-flags="--static" \
  --extra-cflags="-I$PREFIX/include -fPIC -Os" \
  --extra-ldflags="-L$PREFIX/lib" 2>&1 | tail -20

echo "=== Building FFmpeg ==="
make -j$(nproc) 2>&1 || true

# Ensure unversioned .so files exist (FFmpeg make creates symlinks;
# Android packager needs real files, and WSL can't follow symlinks).
echo "=== Ensuring unversioned .so files ==="
for libdir in libavutil libavcodec libavformat libswscale libswresample; do
  # Find the most recent versioned .so file (e.g. libavutil.so.60)
  ver_so=$(ls -t "$libdir"/lib*.so.* 2>/dev/null | head -1)
  if [[ -n "$ver_so" && ! -f "$libdir/lib$(basename $libdir).so" ]]; then
    cp "$ver_so" "$libdir/lib$(basename $libdir).so" && echo "  $libdir/lib$(basename $libdir).so created"
  fi
done

# ----- copy to jniLibs ---------------------------------------------------------
echo "=== Copying .so files to jniLibs/$ABI ==="
JNILIBS_DIR="$SCRIPT_DIR/app/src/main/jniLibs/$ABI"
HEADERS_DIR="$SCRIPT_DIR/app/src/main/cpp/include"
mkdir -p "$JNILIBS_DIR" "$HEADERS_DIR/dav1d"

# Copy dav1d
cp -f "$PREFIX/lib/libdav1d.so" "$JNILIBS_DIR/"
cp -rf "$PREFIX/include/dav1d/"* "$HEADERS_DIR/dav1d/"

for lib in libavutil/libavutil.so libavcodec/libavcodec.so \
           libavformat/libavformat.so libswscale/libswscale.so \
           libswresample/libswresample.so; do
  if [[ -f "$lib" ]]; then
    cp "$lib" "$JNILIBS_DIR/"
    echo "  copied $(basename $lib)"
  else
    echo "  WARNING: $lib not found" >&2
  fi
done

if command -v llvm-strip &>/dev/null; then
  for f in "$JNILIBS_DIR"/*.so; do
    [[ -f "$f" ]] && llvm-strip --strip-all "$f" 2>/dev/null && echo "  stripped $(basename $f)" || true
  done
fi

# ----- copy headers to project include dir ------------------------------------
echo "=== Copying FFmpeg headers ==="
for subdir in libavformat libavcodec libavutil libswscale libswresample; do
  src="$FFMPEG_DIR/$subdir"
  if [[ -d "$src" ]]; then
    mkdir -p "$HEADERS_DIR/$subdir"
    find "$src" -maxdepth 1 -name '*.h' -exec cp {} "$HEADERS_DIR/$subdir/" \;
    echo "  copied $subdir/*.h"
  fi
done
# Also need config.h generated by configure (included by FFmpeg headers)
if [[ -f "$FFMPEG_DIR/config.h" ]]; then
  cp "$FFMPEG_DIR/config.h" "$HEADERS_DIR/"
  echo "  copied config.h"
fi
echo "=== Headers copied ==="

echo "=== FFmpeg build complete ==="
