#!/bin/bash
set -euo pipefail

# Store the script's root directory immediately before changing directories
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# FFmpeg Android cross-build. Default ABI arm64-v8a; pass --abi x86_64 for the emulator.
# NDK is resolved from --ndk-path, $ANDROID_NDK_ROOT, $ANDROID_HOME/ndk, then a
# Windows/WSL fallback. Toolchain host is detected (windows-x86_64 under WSL).

ABI="arm64-v8a"
NDK_PATH=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --abi) ABI="$2"; shift 2 ;;
    --ndk-path) NDK_PATH="$2"; shift 2 ;;
    *) shift ;;
  esac
done

if [[ -z "$NDK_PATH" ]]; then
  if [[ -n "${ANDROID_NDK_ROOT:-}" ]]; then
    NDK_PATH="$ANDROID_NDK_ROOT"
  elif [[ -n "${ANDROID_HOME:-}" && -d "$ANDROID_HOME/ndk" ]]; then
    NDK_PATH=$(ls -d "$ANDROID_HOME/ndk"/* 2>/dev/null | head -n1)
  elif [[ -d "/mnt/c/Users/reihan/AppData/Local/Android/Sdk/ndk" ]]; then
    NDK_PATH=$(ls -d /mnt/c/Users/reihan/AppData/Local/Android/Sdk/ndk/* 2>/dev/null | head -n1)
  fi
fi
if [[ -z "$NDK_PATH" || ! -d "$NDK_PATH" ]]; then
  echo "ERROR: NDK not found. Set ANDROID_NDK_ROOT or pass --ndk-path." >&2
  exit 1
fi

# Detect toolchain host: Windows binaries under WSL, Linux natively.
if [[ -n "${WSL_DISTRO_NAME:-}" || -f /proc/sys/fs/binfmt_misc/WSLInterop ]]; then
  TC_HOST="windows-x86_64"
else
  TC_HOST="linux-x86_64"
fi
NDK_BASE="$NDK_PATH"
TC="$NDK_BASE/toolchains/llvm/prebuilt/$TC_HOST/bin"
SYSROOT="$NDK_BASE/toolchains/llvm/prebuilt/$TC_HOST/sysroot"
TC_REAL="$TC"

# Per-ABI configure knobs.
case "$ABI" in
  arm64-v8a) TARGET_ARCH=arm64;  CROSS="aarch64-linux-android-"; CLANG="aarch64-linux-android28-clang"; CLANGPP="aarch64-linux-android28-clang++" ;;
  x86_64)    TARGET_ARCH=x86_64; CROSS="x86_64-linux-android-";  CLANG="x86_64-linux-android28-clang";  CLANGPP="x86_64-linux-android28-clang++" ;;
  *) echo "ERROR: unsupported ABI '$ABI' (use arm64-v8a or x86_64)" >&2; exit 1 ;;
esac

if [[ "$TC_HOST" == "windows-x86_64" ]]; then
  # WSL interop: create wrappers that convert /mnt/c/ paths to Windows paths.
  WRAPPER_DIR="/tmp/ndk-wrappers"
  mkdir -p "$WRAPPER_DIR"

  make_wrapper() {
    local name="$1"; local real="$2"
    cat > "$WRAPPER_DIR/$name" << WRAPEOF
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
exec "$real" "\${ARGS[@]}"
WRAPEOF
  }
  make_wrapper "$CLANG" "$TC_REAL/$CLANG"
  make_wrapper "$CLANGPP" "$TC_REAL/$CLANGPP"

  for tool in aarch64-linux-android-{as,ld,objcopy,objdump,dlltool,windres,readelf} llvm-{ar,nm,strip,ranlib,objcopy,objdump,readobj,dlltool}; do
    if [ -f "$TC_REAL/$tool" ]; then
      ln -sf "$TC_REAL/$tool" "$WRAPPER_DIR/$tool" 2>/dev/null || true
    fi
  done
  # ld -> clang wrapper (for linking with sysroot paths)
  ln -sf "$WRAPPER_DIR/$CLANG" "$WRAPPER_DIR/aarch64-linux-android-ld" 2>/dev/null || true
  chmod +x "$WRAPPER_DIR/"*
  export PATH="$WRAPPER_DIR:$PATH"
else
  export PATH="$TC:$PATH"
fi

cd "${FFMPEG_SRC_DIR:-ffmpeg-src}"
export TMPDIR=/tmp
make clean 2>/dev/null || true

./configure \
  --target-os=android --arch=$TARGET_ARCH \
  --enable-shared --disable-static \
  --disable-programs --disable-doc \
  --disable-avdevice --disable-avfilter --disable-swresample --disable-network \
  --enable-small \
  --disable-encoders --disable-hwaccels --disable-muxers \
  --enable-decoder=h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1 \
  --enable-demuxer=matroska,mov,avi,mp4,mpegts,flv \
  --enable-protocol=file --enable-swscale \
  --cross-prefix=$CROSS \
  --cc=$CLANG \
  --cxx=$CLANGPP \
  --ar=llvm-ar --nm=llvm-nm \
  --strip=llvm-strip --ranlib=llvm-ranlib \
  --sysroot="$SYSROOT" \
  --extra-cflags="-fPIC -Os" 2>&1 | tail -20

# Windows ld.lld can't follow Linux SONAME symlinks (libavutil.so -> libavutil.so.61).
# Build libavutil first so flat .so exists before dependents need it.
make -j$(nproc) libavutil/libavutil.so.61 2>&1
# Force real copy (dd avoids 9p hardlink dedup)
dd if=libavutil/libavutil.so.61 of=libavutil/libavutil.so bs=1M 2>/dev/null
make -j$(nproc) 2>&1 || true

# Create flat .so copies for Windows linker (9p makes hardlinks from cat, use dd)
echo "=== Creating unversioned .so copies ==="
for pair in "libavutil/libavutil.so.61 libavutil/libavutil.so" "libavcodec/libavcodec.so.63 libavcodec/libavcodec.so" "libswscale/libswscale.so.10 libswscale/libswscale.so" "libavformat/libavformat.so.63 libavformat/libavformat.so"; do
  src=$(echo $pair | cut -d' ' -f1)
  dst=$(echo $pair | cut -d' ' -f2)
  if [ -f "$src" ] && [ ! -f "$dst" ]; then
    dd if="$src" of="$dst" bs=1M 2>/dev/null && echo "  $dst created"
  fi
done

# Rebuild libavformat now that all flat .so files exist
echo "=== Rebuilding libavformat with flat deps ==="
if [[ "$TC_HOST" == "windows-x86_64" ]]; then
  export PATH="/tmp/ndk-wrappers:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
else
  export PATH="$TC_REAL:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
fi
make -j4 libavformat/libavformat.so.63 2>&1 | tail -5

# Copy flat .so files to the Android project jniLibs directory
echo "=== Copying and stripping final .so files to jniLibs/$ABI ==="
JNILIBS_DIR="$SCRIPT_DIR/app/src/main/jniLibs/$ABI"
mkdir -p "$JNILIBS_DIR"

cp libavutil/libavutil.so "$JNILIBS_DIR/"
cp libavcodec/libavcodec.so "$JNILIBS_DIR/"
cp libavformat/libavformat.so "$JNILIBS_DIR/"
cp libswscale/libswscale.so "$JNILIBS_DIR/"

for f in "$JNILIBS_DIR"/libavutil.so "$JNILIBS_DIR"/libavcodec.so "$JNILIBS_DIR"/libavformat.so "$JNILIBS_DIR"/libswscale.so; do
  if [ -f "$f" ]; then
    "$TC_REAL/llvm-strip" --strip-all "$f" 2>&1 && echo "  stripped $f" || true
  fi
done
echo "=== Done copying ==="
