#!/usr/bin/env bash
# Build script for adblock-rust native JNI library for Android (arm64-v8a, x86_64).
# Designed for Linux GitHub Actions workflows and local WSL environments.
#
# Usage: ./build_adblock_rust.sh [--abi arm64-v8a] [--ndk-path /path/to/ndk]
set -euo pipefail

export PATH="$HOME/.cargo/bin:$PATH"
[ -f "$HOME/.cargo/env" ] && source "$HOME/.cargo/env" 2>/dev/null || true
export CARGO_TARGET_DIR="${CARGO_TARGET_DIR:-/tmp/adblock_rust_target}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CRATE_DIR="$SCRIPT_DIR/app/src/main/rust/adblock_jni"
JNI_LIBS_DIR="$SCRIPT_DIR/app/src/main/jniLibs"

# ----- args -------------------------------------------------------------------
ABI="arm64-v8a"
NDK_PATH=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --abi)      ABI="$2";      shift 2 ;;
    --ndk-path) NDK_PATH="$2"; shift 2 ;;
    *)          shift ;;
  esac
done

case "$ABI" in
  arm64-v8a) RUST_TARGET="aarch64-linux-android" ;;
  x86_64)    RUST_TARGET="x86_64-linux-android" ;;
  *) echo "ERROR: unsupported ABI '$ABI' (use arm64-v8a or x86_64)" >&2; exit 1 ;;
esac

echo "=== Building adblock_jni via cargo-ndk for $ABI ==="

# ----- NDK Path Resolution -----
# cargo-ndk needs a host-matching NDK: the Linux NDK under WSL, not the Windows SDK one.
if [[ -z "$NDK_PATH" ]]; then
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
rustup target add "$RUST_TARGET" 2>/dev/null || true

if ! command -v cargo-ndk &> /dev/null; then
  echo "Installing cargo-ndk..."
  cargo install cargo-ndk
fi

cd "$CRATE_DIR"
cargo ndk -t "$ABI" -o "$JNI_LIBS_DIR" build --release

echo "=== adblock_jni compilation complete. Library installed in app/src/main/jniLibs/$ABI/libadblock_jni.so ==="
