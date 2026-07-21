#!/usr/bin/env bash
# Cross-compile libarchive for Android arm64-v8a (NDK r27) WITH mbedTLS crypto.
#
# This machine's NDK install only ships the `windows-x86_64` prebuilt toolchain
# (no `linux-x86_64`), so we run from WSL but drive the Windows NDK clang
# wrappers directly via a hand-written CMake toolchain file. The wrappers already
# bake in the correct --sysroot and --target, so we only add -fPIC/-O2.
#
# Run from repo root under WSL:  bash build_libarchive.sh
set -euo pipefail

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

# ----- NDK Path Resolution -----
if [[ -z "$NDK_PATH" ]]; then
  [[ -n "${ANDROID_NDK_ROOT:-}" ]] && NDK_PATH="$ANDROID_NDK_ROOT"
fi
if [[ -z "$NDK_PATH" ]]; then
  [[ -n "${ANDROID_NDK_HOME:-}" ]] && NDK_PATH="$ANDROID_NDK_HOME"
fi

if [[ -z "$NDK_PATH" || ! -d "$NDK_PATH" ]]; then
  if [[ -n "${ANDROID_HOME:-}" && -d "$ANDROID_HOME/ndk" ]]; then
    # Pick the latest NDK version installed under Android Home
    NDK_PATH=$(ls -d "$ANDROID_HOME/ndk"/* 2>/dev/null | sort -V | tail -n 1)
  else
    for user_dir in /mnt/c/Users/*; do
      if [[ -d "$user_dir/AppData/Local/Android/Sdk/ndk" ]]; then
        NDK_PATH=$(ls -d "$user_dir/AppData/Local/Android/Sdk/ndk"/* 2>/dev/null | sort -V | tail -n 1 || true)
        [[ -n "$NDK_PATH" && -d "$NDK_PATH" ]] && break
      fi
    done
  fi
fi

if [[ -z "$NDK_PATH" || ! -d "$NDK_PATH" ]]; then
  echo "ERROR: NDK not found. Please set ANDROID_NDK_ROOT or ensure Android SDK is installed." >&2
  exit 1
fi

NDK="$NDK_PATH"

case "$ABI" in
  arm64-v8a)
    API=28
    TRIPLE="aarch64-linux-android"
    TRIPLE_LIB="aarch64-linux-android"
    ARCH="aarch64"
    ;;
  armeabi-v7a)
    API=21
    TRIPLE="armv7a-linux-androideabi"
    TRIPLE_LIB="arm-linux-androideabi"
    ARCH="arm"
    ;;
  x86_64)
    API=28
    TRIPLE="x86_64-linux-android"
    TRIPLE_LIB="x86_64-linux-android"
    ARCH="x86_64"
    ;;
  x86)
    API=21
    TRIPLE="i686-linux-android"
    TRIPLE_LIB="i686-linux-android"
    ARCH="i686"
    ;;
  *)
    echo "ERROR: Unsupported ABI: $ABI" >&2
    exit 1
    ;;
esac

HOST_DIR="windows-x86_64"
if [ ! -d "$NDK/toolchains/llvm/prebuilt/$HOST_DIR" ]; then
  HOST_DIR="linux-x86_64"
fi
TOOLCHAIN=$NDK/toolchains/llvm/prebuilt/$HOST_DIR/bin
SYSROOT=$NDK/toolchains/llvm/prebuilt/$HOST_DIR/sysroot

if command -v wslpath >/dev/null 2>&1; then
  WIN_SYSROOT=$(wslpath -m "$SYSROOT")
else
  WIN_SYSROOT="$SYSROOT"
fi

EXE_SUFFIX=""
if [ "$HOST_DIR" = "windows-x86_64" ]; then
  EXE_SUFFIX=".exe"
fi

CC=$TOOLCHAIN/${TRIPLE}${API}-clang
CXX=$TOOLCHAIN/${TRIPLE}${API}-clang++
AR=$TOOLCHAIN/llvm-ar${EXE_SUFFIX}
RANLIB=$TOOLCHAIN/llvm-ranlib${EXE_SUFFIX}

# Build inside WSL fs (/tmp). Windows clang.exe cannot read /mnt/c paths, and
# /tmp is wiped across WSL restarts, so the WHOLE build runs in one session.
SRC=/tmp/build_archive
PREFIX=$SRC/prefix
ROOT=$(cd "$(dirname "$0")" && pwd)
OUT=$ROOT/app/src/main/jniLibs/$ABI
JOBS=$(nproc)

SUDO=""
[ "$(id -u)" -ne 0 ] && SUDO=sudo
$SUDO apt-get update -qq
$SUDO apt-get install -y -qq cmake ninja-build wget build-essential

# ---- hand-written cross toolchain (no linux NDK present) ----
TCFILE=$SRC/android-toolchain.cmake
mkdir -p "$SRC" "$PREFIX" "$OUT"
cat > "$TCFILE" <<EOF
set(CMAKE_SYSTEM_NAME Generic)
set(CMAKE_SYSTEM_PROCESSOR $ARCH)
set(CMAKE_C_COMPILER "$CC")
set(CMAKE_CXX_COMPILER "$CXX")
set(CMAKE_AR "$AR")
set(CMAKE_RANLIB "$RANLIB")
set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM NEVER)
set(CMAKE_FIND_ROOT_PATH_MODE_LIBRARY ONLY)
set(CMAKE_FIND_ROOT_PATH_MODE_INCLUDE ONLY)
set(CMAKE_C_FLAGS "-fPIC -O2 -I$SRC/libarchive/contrib/android/include" CACHE STRING "" FORCE)
set(CMAKE_CXX_FLAGS "-fPIC -O2" CACHE STRING "" FORCE)
EOF

export PATH=$TOOLCHAIN:$PATH

# ---------- libbz2 / liblzma / mbedTLS (built in parallel; independent) ----------
build_bzip2() {
  cd "$SRC"
  if [ ! -d bzip2 ]; then
    wget -q https://sourceware.org/pub/bzip2/bzip2-1.0.8.tar.gz -O bzip2.tar.gz
    tar xf bzip2.tar.gz && mv bzip2-1.0.8 bzip2
  fi
  cd bzip2
  make -j"$JOBS" CC="$CC" AR="$AR" RANLIB="$RANLIB" CFLAGS="-fPIC -O2" libbz2.a
  cp libbz2.a "$PREFIX"/
  cp bzlib.h  "$PREFIX"/
}
build_xz() {
  cd "$SRC"
  if [ ! -d xz ]; then
    wget -q https://github.com/tukaani-project/xz/releases/download/v5.6.3/xz-5.6.3.tar.gz -O xz.tar.gz
    tar xf xz.tar.gz && mv xz-5.6.3 xz
  fi
  cmake -S xz -B xz-build -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$TCFILE" -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=OFF \
    -DCMAKE_INSTALL_PREFIX="$PREFIX" -DXZ_TOOL=OFF -DBUILD_TESTING=OFF
  cmake --build xz-build -j"$JOBS"
  cmake --install xz-build
}
build_mbedtls() {
  cd "$SRC"
  if [ ! -d mbedtls ]; then
    git clone --depth 1 --branch mbedtls-3.6.7 --recurse-submodules https://github.com/Mbed-TLS/mbedtls.git mbedtls
  fi
  cmake -S mbedtls -B mbedtls-build -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$TCFILE" -DCMAKE_BUILD_TYPE=Release \
    -DENABLE_TESTING=OFF -DENABLE_PROGRAMS=OFF \
    -DUSE_SHARED_MBEDTLS_LIBRARY=OFF -DUSE_STATIC_MBEDTLS_LIBRARY=ON \
    -DCMAKE_INSTALL_PREFIX="$PREFIX"
  cmake --build mbedtls-build -j"$JOBS"
  cmake --install mbedtls-build
  # Drop the config package so libarchive's module FindMbedTLS (not config mode)
  # is used; we feed the static libs explicitly below.
  rm -rf "$PREFIX"/lib/cmake
}
fail=0
build_bzip2 & p_bz=$!
build_xz    & p_xz=$!
build_mbedtls & p_mb=$!
for p in $p_bz $p_xz $p_mb; do wait "$p" || fail=1; done
[ "$fail" -eq 0 ] || exit 1

# ---------- libarchive (current master) ----------
cd "$SRC"
if [ ! -d libarchive ]; then
  git clone --depth 1 https://github.com/libarchive/libarchive.git libarchive
fi
cmake -S libarchive -B libarchive-build -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$TCFILE" -DCMAKE_BUILD_TYPE=Release -DCMAKE_PREFIX_PATH="$PREFIX" \
  -DENABLE_TAR=OFF -DENABLE_CPIO=OFF -DENABLE_CAT=OFF -DENABLE_UNZIP=OFF \
  -DENABLE_ZLIB=ON \
  -DZLIB_INCLUDE_DIR="$WIN_SYSROOT/usr/include" \
  -DZLIB_LIBRARY="$WIN_SYSROOT/usr/lib/$TRIPLE_LIB/$API/libz.so" \
  -DENABLE_LZMA=ON -DENABLE_BZip2=ON \
  -DENABLE_ZSTD=OFF -DENABLE_LZO=OFF -DENABLE_LZ4=OFF \
  -DENABLE_MBEDTLS=ON -DENABLE_OPENSSL=OFF -DENABLE_NETTLE=OFF -DENABLE_GCRYPT=OFF \
  -DMBEDTLS_INCLUDE_DIR="$PREFIX/include" \
  -DMBEDTLS_LIBRARY="$PREFIX/lib/libmbedtls.a" \
  -DMBEDX509_LIBRARY="$PREFIX/lib/libmbedx509.a" \
  -DMBEDCRYPTO_LIBRARY="$PREFIX/lib/libmbedcrypto.a" \
  -DENABLE_TEST=OFF -DENABLE_DOCS=OFF -DBUILD_SHARED_LIBS=OFF
cmake --build libarchive-build -j"$JOBS"

# Copy public headers to the project's include directory
mkdir -p "$ROOT/app/src/main/cpp/include/libarchive"
cp -f libarchive/libarchive/archive.h "$ROOT/app/src/main/cpp/include/libarchive/"
cp -f libarchive/libarchive/archive_entry.h "$ROOT/app/src/main/cpp/include/libarchive/"

echo "=== DIAG: mbedtls refs in libarchive.a ==="
"$TOOLCHAIN/llvm-nm${EXE_SUFFIX}" libarchive-build/libarchive/libarchive.a 2>/dev/null | grep -i mbedtls | head
echo "=== DIAG: mbedtls_aes present in libmbedcrypto.a ==="
"$TOOLCHAIN/llvm-nm${EXE_SUFFIX}" "$PREFIX/lib/libmbedcrypto.a" 2>/dev/null | grep -i mbedtls_aes | head

# Hand-link a self-contained shared lib from the static archive + static deps.
# (Generic target refuses SHARED libs, so we build static then fuse here.)
# Link into /tmp first (writable), then drop it onto the jniLibs path.
rm -f "$OUT"/libarchive.so
"$CC" -shared -Wl,-soname,libarchive.so -o "$SRC/libarchive.so" \
  -Wl,--whole-archive libarchive-build/libarchive/libarchive.a \
     "$PREFIX/lib/libmbedtls.a" "$PREFIX/lib/libmbedx509.a" "$PREFIX/lib/libmbedcrypto.a" \
     "$PREFIX/lib/liblzma.a" "$PREFIX/libbz2.a" -Wl,--no-whole-archive \
  -lz -llog -lc -lm -ldl
cp -f "$SRC/libarchive.so" "$OUT"/libarchive.so

echo "=== verify mbedTLS crypto symbols are linked ==="
"$TOOLCHAIN/llvm-nm${EXE_SUFFIX}" -D "$OUT"/libarchive.so 2>/dev/null | grep -iE 'mbedtls_aes|mbedtls_pkcs5|mbedtls_sha256' | head || true

echo "=================================================="
file "$OUT"/libarchive.so
"$CC" -print-file-name=libc++_shared.so >/dev/null 2>&1 || true
ls -la "$OUT"/libarchive.so
echo "BUILT OK -> $OUT/libarchive.so"
