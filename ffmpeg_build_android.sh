#!/bin/bash
set -euo pipefail

# WSL build script for FFmpeg Android arm64-v8a
# Uses Windows NDK binaries via WSL2 interop, with wslpath path conversion.

NDK_BASE="/mnt/c/Users/reihan/AppData/Local/Android/Sdk/ndk/28.2.13676358"
TC="$NDK_BASE/toolchains/llvm/prebuilt/windows-x86_64/bin"
SYSROOT="$NDK_BASE/toolchains/llvm/prebuilt/windows-x86_64/sysroot"

# Create wrapper dir that converts /mnt/c/ paths to Windows paths
WRAPPER_DIR="/tmp/ndk-wrappers"
mkdir -p "$WRAPPER_DIR"

# Wrapper for clang/clang++: convert args containing /mnt/c/ to Windows paths
TC_REAL="/mnt/c/Users/reihan/AppData/Local/Android/Sdk/ndk/28.2.13676358/toolchains/llvm/prebuilt/windows-x86_64/bin"

cat > "$WRAPPER_DIR/aarch64-linux-android28-clang" << WRAPEOF
#!/bin/bash
ARGS=()
for a in "\$@"; do
  # If arg contains /mnt/c/ somewhere after an = sign, split and convert
  if [[ "\$a" == *=/mnt/c/* ]]; then
    prefix="\${a%%=*}"
    path="\${a#*=}"
    converted="\$(wslpath -w "\$path")"
    ARGS+=("\$prefix=\$converted")
  elif [[ "\$a" == /mnt/c/* ]]; then
    ARGS+=("\$(wslpath -w "\$a")")
  else
    ARGS+=("\$a")
  fi
done
exec "$TC_REAL/aarch64-linux-android28-clang" "\${ARGS[@]}"
WRAPEOF

cat > "$WRAPPER_DIR/aarch64-linux-android28-clang++" << WRAPEOF
#!/bin/bash
ARGS=()
for a in "\$@"; do
  if [[ "\$a" == *=/mnt/c/* ]]; then
    prefix="\${a%%=*}"
    path="\${a#*=}"
    converted="\$(wslpath -w "\$path")"
    ARGS+=("\$prefix=\$converted")
  elif [[ "\$a" == /mnt/c/* ]]; then
    ARGS+=("\$(wslpath -w "\$a")")
  else
    ARGS+=("\$a")
  fi
done
exec "$TC_REAL/aarch64-linux-android28-clang++" "\${ARGS[@]}"
WRAPEOF

for tool in aarch64-linux-android-{as,ld,objcopy,objdump,dlltool,windres,readelf} llvm-{ar,nm,strip,ranlib,objcopy,objdump,readobj,dlltool}; do
  if [ -f "$TC_REAL/$tool" ]; then
    ln -sf "$TC_REAL/$tool" "$WRAPPER_DIR/$tool" 2>/dev/null || true
  fi
done
# ld -> clang wrapper (for linking with sysroot paths)
ln -sf "$WRAPPER_DIR/aarch64-linux-android28-clang" "$WRAPPER_DIR/aarch64-linux-android-ld" 2>/dev/null || true

chmod +x "$WRAPPER_DIR/"*

export PATH="$WRAPPER_DIR:$PATH"
cd /root/ffmpeg-src
export TMPDIR=/tmp
make clean 2>/dev/null || true

./configure \
  --target-os=android --arch=arm64 \
  --enable-shared --disable-static \
  --disable-programs --disable-doc \
  --disable-avdevice --disable-avfilter --disable-swresample --disable-network \
  --disable-encoders --disable-hwaccels --disable-muxers \
  --enable-decoder=h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1 \
  --enable-demuxer=matroska,mov,avi,mp4,mpegts,flv \
  --enable-protocol=file --enable-swscale \
  --cross-prefix=aarch64-linux-android- \
  --cc=aarch64-linux-android28-clang \
  --cxx=aarch64-linux-android28-clang++ \
  --ar=llvm-ar --nm=llvm-nm \
  --strip=llvm-strip --ranlib=llvm-ranlib \
  --sysroot="$SYSROOT" \
  --extra-cflags="-fPIC" 2>&1 | tail -20

# Windows ld.lld can't follow Linux SONAME symlinks (libavutil.so -> libavutil.so.61).
# Build libavutil first so flat .so exists before dependents need it.
make -j$(nproc) libavutil/libavutil.so.61 2>&1
cp libavutil/libavutil.so.61 libavutil/libavutil.so
make -j$(nproc) 2>&1
