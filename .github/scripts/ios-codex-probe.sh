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

rustup toolchain install "$TOOLCHAIN" --profile minimal --target "$TARGET"
rustc +"$TOOLCHAIN" --version
export IPHONEOS_DEPLOYMENT_TARGET=17.0
export SDKROOT="$(xcrun --sdk iphoneos --show-sdk-path)"
export CARGO_TERM_COLOR=never
cd "$SOURCE/codex-rs"
cargo +"$TOOLCHAIN" check --locked --lib -p codex-app-server --target "$TARGET" \
  2>&1 | tee "$OUT/cargo-check.log"
printf 'Official Codex app-server library passed cargo check for %s.\n' "$TARGET" > "$OUT/result.txt"
if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
  cat "$OUT/result.txt" >> "$GITHUB_STEP_SUMMARY"
  printf '\nThis checks compilation only. Linking, device execution, Keychain authentication and tool calls still need verification.\n' >> "$GITHUB_STEP_SUMMARY"
fi
