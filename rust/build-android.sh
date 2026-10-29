#!/usr/bin/env bash
# Build the Rust core for Android and generate Kotlin bindings.
# Usage: build-android.sh <jniLibs out dir> <kotlin out dir>
# Env: FASTIFY_ABIS (default "arm64-v8a"), ANDROID_HOME / ANDROID_NDK_HOME.
set -euo pipefail
cd "$(dirname "$0")"

JNI_OUT="$1"
KOTLIN_OUT="$2"
ABIS="${FASTIFY_ABIS:-arm64-v8a}"

# Non-interactive shells (Gradle) don't source the user's rc files.
export CARGO_HOME="${CARGO_HOME:-$HOME/.local/share/cargo}"
export RUSTUP_HOME="${RUSTUP_HOME:-$HOME/.local/share/rustup}"
export PATH="$CARGO_HOME/bin:$HOME/.cargo/bin:$PATH"
export CARGO_BUILD_JOBS="${CARGO_BUILD_JOBS:-2}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/.local/share/android-sdk}"
if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
  ANDROID_NDK_HOME="$(ls -d "$ANDROID_HOME"/ndk/* | sort -V | tail -1)"
  export ANDROID_NDK_HOME
fi

targets=()
for abi in $ABIS; do targets+=(-t "$abi"); done

rm -rf "$JNI_OUT" "$KOTLIN_OUT"
mkdir -p "$JNI_OUT" "$KOTLIN_OUT"
cargo ndk "${targets[@]}" --platform 35 -o "$JNI_OUT" build --release -p fastify-core

# Bindings come from the compiled library's metadata, so any ABI works.
lib="$(find "$JNI_OUT" -name libfastify_core.so | head -1)"
cargo run --quiet --release -p uniffi-bindgen -- generate --library "$lib" \
  --language kotlin --out-dir "$KOTLIN_OUT" --no-format
