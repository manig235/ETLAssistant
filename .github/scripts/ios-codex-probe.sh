#!/usr/bin/env bash
# A compile probe, not an installable app or a replacement authentication flow.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OUT="$ROOT/ios-codex-probe"
SOURCE="$OUT/source"
# Same Codex release used by Android. Pin both source revision and archive bytes.
REVISION=29eb79e9866b3262a777fdf8195c1b9e63684c91
ARCHIVE_SHA256=af62862cf5eb25b0ef0c45d2a964b1337a31b502c0627257d8339f582d431c60
TOOLCHAIN=1.95.0
SDK=iphoneos
ARCH=arm64
TARGET=aarch64-apple-ios
if [[ "${CODEX_PROBE_PLATFORM:-device}" == simulator ]]; then
  SDK=iphonesimulator
  if [[ "$(uname -m)" == arm64 ]]; then TARGET=aarch64-apple-ios-sim; else TARGET=x86_64-apple-ios; ARCH=x86_64; fi
fi
if [[ "${CODEX_PROBE_OPERATION:-check}" == code-mode-test ]]; then
  SDK=macosx
  TARGET="$(uname -m)-apple-darwin"
fi

mkdir -p "$OUT" "$SOURCE"
exec > >(tee "$OUT/probe.log") 2>&1
printf 'revision=%s\nrust=%s\ntarget=%s\n' "$REVISION" "$TOOLCHAIN" "$TARGET" > "$OUT/source.txt"
xcodebuild -version | tee "$OUT/xcode.txt"
curl --fail --location --retry 3 \
  "https://api.github.com/repos/openai/codex/tarball/$REVISION" \
  -o "$OUT/codex.tar.gz"
printf '%s  %s\n' "$ARCHIVE_SHA256" "$OUT/codex.tar.gz" | shasum -a 256 -c -
tar -xzf "$OUT/codex.tar.gz" -C "$SOURCE" --strip-components=1
V8_REVISION=90a65f887a0c0f048f3784afbcb320b373c8a2e0
V8_SHA256=32bc3dac906adf19f98ea73f5882bccbdf68cd5e194135fb1b68b14f86178721
curl --fail --location --retry 3 \
  "https://api.github.com/repos/openai-oss-forks/rusty_v8/tarball/$V8_REVISION" \
  -o "$OUT/v8.tar.gz"
printf '%s  %s\n' "$V8_SHA256" "$OUT/v8.tar.gz" | shasum -a 256 -c -
mkdir -p "$SOURCE/youngmin-vendor/rusty-v8"
tar -xzf "$OUT/v8.tar.gz" -C "$SOURCE/youngmin-vendor/rusty-v8" --strip-components=1
MXC_REVISION=b21120168d70befe12356411423266bfef168446
MXC_SHA256=358b5227287fc3b3bcb1a5b0679970ceba38e1c5cf664b791743898a72c20777
curl --fail --location --retry 3 \
  "https://api.github.com/repos/microsoft/mxc/tarball/$MXC_REVISION" \
  -o "$OUT/mxc.tar.gz"
printf '%s  %s\n' "$MXC_SHA256" "$OUT/mxc.tar.gz" | shasum -a 256 -c -
mkdir -p "$SOURCE/youngmin-vendor/mxc"
tar -xzf "$OUT/mxc.tar.gz" -C "$SOURCE/youngmin-vendor/mxc" --strip-components=1
python3 "$ROOT/.github/scripts/prepare-ios-codex.py" "$SOURCE" "${CODEX_PROBE_COMPONENT:-upstream}"

rustup toolchain install "$TOOLCHAIN" --profile minimal --target "$TARGET"
rustc +"$TOOLCHAIN" --version
export IPHONEOS_DEPLOYMENT_TARGET=17.0
export SDKROOT="$(xcrun --sdk "$SDK" --show-sdk-path)"
export CARGO_TERM_COLOR=never
export CARGO_BUILD_JOBS=2
cd "$SOURCE/codex-rs"
PACKAGE=codex-app-server
if [[ "${CODEX_PROBE_COMPONENT:-upstream}" == bridge ]]; then
  PACKAGE=youngmin-codex-bridge
fi
if [[ "${CODEX_PROBE_OPERATION:-check}" == code-mode-test ]]; then
  export CARGO_PROFILE_DEV_SMALL_OPT_LEVEL=0
  export CARGO_PROFILE_DEV_SMALL_CODEGEN_UNITS=64
  cargo +"$TOOLCHAIN" test --locked -p youngmin-code-mode-check --profile dev-small \
    2>&1 | tee "$OUT/cargo-code-mode-test.log"
  printf 'Codex Code Mode session/delegate integration passed on %s.\n' "$TARGET" > "$OUT/result.txt"
elif [[ "${CODEX_PROBE_OPERATION:-check}" == build ]]; then
  test "$PACKAGE" == youngmin-codex-bridge
  # Keep iteration builds reasonably small without release LTO costs.
  export CARGO_PROFILE_DEV_SMALL_OPT_LEVEL="${CODEX_NATIVE_OPT_LEVEL:-1}"
  if [[ "$SDK" == iphonesimulator ]]; then export CARGO_PROFILE_DEV_SMALL_OPT_LEVEL=0; fi
  export CARGO_PROFILE_DEV_SMALL_CODEGEN_UNITS=64
  library="target/$TARGET/dev-small/libyoungmin_codex_bridge.a"
  cache_marker="target/$TARGET/dev-small/youngmin-native-cache-key.txt"
  cached_flags="target/$TARGET/dev-small/youngmin-native-static-libs.txt"
  expected_key="${CODEX_NATIVE_CACHE_KEY:-}"
  if [[ "${CODEX_REUSE_COMPILED_LIBRARY:-false}" == true && "$SDK" == iphoneos && -n "$expected_key" && -s "$library" && -s "$cache_marker" && -s "$cached_flags" && "$(cat "$cache_marker")" == "$expected_key" ]]; then
    # The exact cache key includes all patched source and bridge inputs. A
    # finished static library can survive a later packaging-only failure.
    echo 'Reusing the compiled native library from the exact source cache.'
    native_libs="$(cat "$cached_flags")"
  else
    # A restored target directory can contain an older .a after a failed build.
    # Only a completed compiler invocation can mark a library reusable.
    rm -f "$cache_marker" "$cached_flags"
    touch youngmin-ios-bridge/src/lib.rs
    cargo +"$TOOLCHAIN" rustc --locked --lib -p "$PACKAGE" --target "$TARGET" --profile dev-small -- --print native-static-libs \
      2>&1 | tee "$OUT/cargo-build.log"
    native_libs="$(sed -n 's/^note: native-static-libs: //p' "$OUT/cargo-build.log" | tail -n 1)"
  fi
  GENERATED="$ROOT/ios/NativeCodex/Generated/$SDK/$ARCH"
  mkdir -p "$GENERATED"
  if [[ -z "$native_libs" ]]; then echo 'Rust did not report its required native system libraries.' >&2; exit 1; fi
  printf '%s\n' "$native_libs" > "$GENERATED/native-static-libs.txt"
  cp "$library" "$GENERATED/"
  cp "$SOURCE/LICENSE" "$ROOT/ios/NativeCodex/Generated/Codex-LICENSE.txt"
  cp "$SOURCE/youngmin-vendor/mxc/LICENSE.md" "$ROOT/ios/NativeCodex/Generated/MXC-LICENSE.txt"
  if [[ -f "$SOURCE/NOTICE" ]]; then cp "$SOURCE/NOTICE" "$ROOT/ios/NativeCodex/Generated/Codex-NOTICE.txt"; fi
  printf '%s\n' "$native_libs" > "$cached_flags"
  if [[ -n "$expected_key" ]]; then printf '%s\n' "$expected_key" > "$cache_marker"; fi
  printf 'Built %s static library for %s.\n' "$PACKAGE" "$TARGET" > "$OUT/result.txt"
else
  cargo +"$TOOLCHAIN" check --locked --lib -p "$PACKAGE" --target "$TARGET" \
    2>&1 | tee "$OUT/cargo-check.log"
  printf 'Official Codex app-server library passed cargo check for %s.\n' "$TARGET" > "$OUT/result.txt"
fi
if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
  cat "$OUT/result.txt" >> "$GITHUB_STEP_SUMMARY"
  printf '\nThis checks compilation only. Linking, device execution, Keychain authentication and tool calls still need verification.\n' >> "$GITHUB_STEP_SUMMARY"
fi
