#!/bin/bash
set -euo pipefail
cd /root/ffmpeg-src
export TMPDIR=/tmp
make clean 2>/dev/null || true
# Convert NDK path to Windows format so Windows ld.lld can find CRT objects.
NDK_WIN=$(wslpath -w /mnt/c/Users/reihan/AppData/Local/Android/Sdk/ndk/28.2.13676358)
TC=$NDK_WIN\\toolchains\\llvm\\prebuilt\\windows-x86_64\\bin
SYSROOT=$NDK_WIN\\toolchains\\llvm\\prebuilt\\windows-x86_64\\sysroot
./configure \
  --target-os=android --arch=arm64 \
  --enable-shared --disable-static \
  --disable-programs --disable-doc \
  --disable-avdevice --disable-avfilter --disable-network \
  --enable-decoder=h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1 \
  --enable-demuxer=matroska,mov,avi,mp4,mpegts,flv \
  --enable-protocol=file --enable-swscale \
  --cross-prefix=$TC/aarch64-linux-android- \
  --cc=$TC/aarch64-linux-android28-clang \
  --cxx=$TC/aarch64-linux-android28-clang++ \
  --ar=$TC/llvm-ar --nm=$TC/llvm-nm \
  --strip=$TC/llvm-strip --ranlib=$TC/llvm-ranlib \
  --sysroot=$SYSROOT \
  --extra-cflags=-fPIC 2>&1 | tail -20
