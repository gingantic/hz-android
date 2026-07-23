#!/usr/bin/env bash
# Build script for adblock-rust native JNI library for Android (arm64-v8a).
# Designed for Linux GitHub Actions workflows and local WSL environments.
set -euo pipefail

export PATH="$HOME/.cargo/bin:$PATH"
[ -f "$HOME/.cargo/env" ] && source "$HOME/.cargo/env" 2>/dev/null || true
export CARGO_TARGET_DIR="${CARGO_TARGET_DIR:-/tmp/adblock_rust_target}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CRATE_DIR="$SCRIPT_DIR/app/src/main/rust/adblock_jni"
JNI_LIBS_DIR="$SCRIPT_DIR/app/src/main/jniLibs"

echo "=== Building adblock_jni via cargo-ndk ==="

# ----- NDK Path Resolution -----
NDK_PATH=""
if [[ -n "${ANDROID_NDK_ROOT:-}" && -d "$ANDROID_NDK_ROOT" ]]; then
  NDK_PATH="$ANDROID_NDK_ROOT"
elif [[ -n "${ANDROID_NDK_HOME:-}" && -d "$ANDROID_NDK_HOME" ]]; then
  NDK_PATH="$ANDROID_NDK_HOME"
elif [[ -n "${ANDROID_HOME:-}" && -d "$ANDROID_HOME/ndk" ]]; then
  NDK_PATH=$(ls -d "$ANDROID_HOME/ndk"/* 2>/dev/null | sort -V | tail -n 1 || true)
else
  for user_dir in /mnt/c/Users/*; do
    if [[ -d "$user_dir/AppData/Local/Android/Sdk/ndk" ]]; then
      NDK_PATH=$(ls -d "$user_dir/AppData/Local/Android/Sdk/ndk"/* 2>/dev/null | sort -V | tail -n 1 || true)
      [[ -n "$NDK_PATH" && -d "$NDK_PATH" ]] && break
    fi
  done
fi

if [[ -n "$NDK_PATH" && -d "$NDK_PATH" ]]; then
  export ANDROID_NDK_HOME="$NDK_PATH"
  export ANDROID_NDK_ROOT="$NDK_PATH"
  export NDK_HOME="$NDK_PATH"
  echo "Using NDK at: $NDK_PATH"
else
  echo "WARNING: NDK path not found automatically."
fi

rustup default stable 2>/dev/null || true
rustup target add aarch64-linux-android 2>/dev/null || true

if ! command -v cargo-ndk &> /dev/null; then
  echo "Installing cargo-ndk..."
  cargo install cargo-ndk
fi

cd "$CRATE_DIR"
cargo ndk -t arm64-v8a -o "$JNI_LIBS_DIR" build --release

echo "=== adblock_jni compilation complete. Library installed in app/src/main/jniLibs/arm64-v8a/libadblock_jni.so ==="
