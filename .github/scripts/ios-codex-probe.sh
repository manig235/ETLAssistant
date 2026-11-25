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
TARGET=aarch64-apple-ios

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
python3 "$ROOT/.github/scripts/prepare-ios-codex.py" "$SOURCE" "${CODEX_PROBE_COMPONENT:-upstream}"

rustup toolchain install "$TOOLCHAIN" --profile minimal --target "$TARGET"
rustc +"$TOOLCHAIN" --version
export IPHONEOS_DEPLOYMENT_TARGET=17.0
export SDKROOT="$(xcrun --sdk iphoneos --show-sdk-path)"
export CARGO_TERM_COLOR=never
cd "$SOURCE/codex-rs"
PACKAGE=codex-app-server
if [[ "${CODEX_PROBE_COMPONENT:-upstream}" == bridge ]]; then
  PACKAGE=youngmin-codex-bridge
fi
cargo +"$TOOLCHAIN" check --locked --lib -p "$PACKAGE" --target "$TARGET" \
  2>&1 | tee "$OUT/cargo-check.log"
printf 'Official Codex app-server library passed cargo check for %s.\n' "$TARGET" > "$OUT/result.txt"
if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
  cat "$OUT/result.txt" >> "$GITHUB_STEP_SUMMARY"
  printf '\nThis checks compilation only. Linking, device execution, Keychain authentication and tool calls still need verification.\n' >> "$GITHUB_STEP_SUMMARY"
fi
