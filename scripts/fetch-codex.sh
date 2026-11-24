#!/usr/bin/env bash
# Phase 0: 공식 Codex CLI(aarch64-unknown-linux-musl)와 CA 번들을 받아 앱 소스 트리에 넣는다.
# 두 파일 모두 .gitignore 대상이다(저장소에 커밋하지 않는다).
#
#   CODEX_VERSION=rust-v0.170.0 scripts/fetch-codex.sh
#
# CODEX_VERSION: GitHub release 태그. 기본값은 아래 고정 태그(안정 버전).
set -euo pipefail

DEFAULT_CODEX_VERSION="rust-v0.170.0"
CODEX_VERSION="${CODEX_VERSION:-$DEFAULT_CODEX_VERSION}"
TARGET="aarch64-unknown-linux-musl"
REPO="openai/codex"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JNI_DIR="$ROOT/app/src/main/jniLibs/arm64-v8a"
ASSETS_DIR="$ROOT/app/src/main/assets"
OUT_BIN="$JNI_DIR/libcodex.so"
OUT_CA="$ASSETS_DIR/cacert.pem"

for tool in curl tar file python3; do
  command -v "$tool" >/dev/null || { echo "필요한 도구가 없습니다: $tool" >&2; exit 1; }
done

echo "Codex release tag: $CODEX_VERSION"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# 릴리스 메타데이터에서 asset 이름/URL/sha256 digest를 확인한다.
curl -fsSL -H "Accept: application/vnd.github+json" \
  "https://api.github.com/repos/$REPO/releases/tags/$CODEX_VERSION" -o "$WORK/release.json"

# zstd가 있으면 더 작은 .zst(단일 파일 압축)를, 없으면 .tar.gz를 쓴다.
if command -v zstd >/dev/null; then
  ASSET="codex-$TARGET.zst"
else
  ASSET="codex-$TARGET.tar.gz"
fi

read -r URL DIGEST < <(python3 -I - "$WORK/release.json" "$ASSET" <<'PY'
import json, sys
rel = json.load(open(sys.argv[1]))
for a in rel.get("assets", []):
    if a["name"] == sys.argv[2]:
        print(a["browser_download_url"], a.get("digest") or "-")
        break
else:
    sys.exit("asset not found: " + sys.argv[2])
PY
)

echo "Asset: $ASSET"
echo "URL:   $URL"
mkdir -p "$WORK/dl"
curl -fL --retry 3 -o "$WORK/dl/$ASSET" "$URL"

if [[ "$DIGEST" == sha256:* ]]; then
  want="${DIGEST#sha256:}"
  got="$(sha256sum "$WORK/dl/$ASSET" | cut -d' ' -f1)"
  if [[ "$want" != "$got" ]]; then
    echo "sha256 불일치: expected $want, got $got" >&2
    exit 1
  fi
  echo "sha256 OK: $got"
else
  echo "경고: 릴리스에 digest가 없어 sha256을 검증하지 못했습니다." >&2
fi

mkdir -p "$WORK/x"
case "$ASSET" in
  *.zst)    zstd -q -d "$WORK/dl/$ASSET" -o "$WORK/x/codex-$TARGET" ;;
  *.tar.gz) tar -xzf "$WORK/dl/$ASSET" -C "$WORK/x" ;;
esac

BIN="$(find "$WORK/x" -type f -name "codex*" | head -n1)"
[[ -n "$BIN" ]] || { echo "압축 해제 결과에서 codex 바이너리를 찾지 못했습니다." >&2; exit 1; }

INFO="$(file -b "$BIN")"
echo "file: $INFO"
if [[ "$INFO" != *ELF*64-bit*ARM\ aarch64* ]]; then
  echo "aarch64 ELF가 아닙니다." >&2
  exit 1
fi
if command -v readelf >/dev/null; then
  readelf -h "$BIN" | grep -E 'Class|Machine|Type|Entry'
else
  # readelf가 없으면 e_type(오프셋 16, 2바이트 LE)을 직접 읽는다.
  python3 -I -c 'import sys,struct; d=open(sys.argv[1],"rb").read(20); print("e_type:", struct.unpack("<H", d[16:18])[0], "(2=EXEC, 3=DYN)")' "$BIN"
fi

mkdir -p "$JNI_DIR" "$ASSETS_DIR"
install -m 0755 "$BIN" "$OUT_BIN"
echo "설치: $OUT_BIN ($(stat -c %s "$OUT_BIN") bytes)"

# code-mode host: 계정으로 쓸 수 있는 모델은 전부 code_mode_only라서 MCP 도구를 부르려면 필요하다.
# 다운로드 원본은 libcodex-code-mode-host.so로 보관한다. 빌드의 preparePrivateCodexRuntime은
# 검증한 원본을 보존하고 generated jni에 libcodex-modehost.so로 복사한다. Codex의 같은 길이
# 경로 문자열만 바꾸므로 기본 grpc+stdio provider가 직접 실행하며 TCP 리스너는 열지 않는다.
HOST_ASSET="codex-code-mode-host-$TARGET.tar.gz"
read -r HOST_URL HOST_DIGEST < <(python3 -I - "$WORK/release.json" "$HOST_ASSET" <<'PY'
import json, sys
rel = json.load(open(sys.argv[1]))
for a in rel.get("assets", []):
    if a["name"] == sys.argv[2]:
        print(a["browser_download_url"], a.get("digest") or "-")
        break
else:
    sys.exit("asset not found: " + sys.argv[2])
PY
)
echo "Asset: $HOST_ASSET"
curl -fL --retry 3 -o "$WORK/dl/$HOST_ASSET" "$HOST_URL"
if [[ "$HOST_DIGEST" == sha256:* ]]; then
  want="${HOST_DIGEST#sha256:}"
  got="$(sha256sum "$WORK/dl/$HOST_ASSET" | cut -d' ' -f1)"
  [[ "$want" == "$got" ]] || { echo "code-mode host sha256 불일치: expected $want, got $got" >&2; exit 1; }
  echo "sha256 OK: $got"
else
  echo "경고: code-mode host의 digest가 없어 sha256을 검증하지 못했습니다." >&2
fi
mkdir -p "$WORK/hx"
tar -xzf "$WORK/dl/$HOST_ASSET" -C "$WORK/hx"
HOST_BIN="$(find "$WORK/hx" -type f -name "codex-code-mode-host*" | head -n1)"
[[ -n "$HOST_BIN" ]] || { echo "code-mode host를 찾지 못했습니다." >&2; exit 1; }
[[ "$(file -b "$HOST_BIN")" == *ELF*64-bit*ARM\ aarch64* ]] || { echo "code-mode host가 aarch64 ELF가 아닙니다." >&2; exit 1; }
install -m 0755 "$HOST_BIN" "$JNI_DIR/libcodex-code-mode-host.so"
echo "설치: $JNI_DIR/libcodex-code-mode-host.so ($(stat -c %s "$JNI_DIR/libcodex-code-mode-host.so") bytes)"

curl -fsSL --retry 3 -o "$WORK/cacert.pem" https://curl.se/ca/cacert.pem
grep -q "BEGIN CERTIFICATE" "$WORK/cacert.pem" || { echo "cacert.pem 형식이 이상합니다." >&2; exit 1; }
install -m 0644 "$WORK/cacert.pem" "$OUT_CA"
echo "설치: $OUT_CA ($(grep -c 'BEGIN CERTIFICATE' "$OUT_CA") certs)"
echo "$CODEX_VERSION" > "$ASSETS_DIR/codex-version.txt"
