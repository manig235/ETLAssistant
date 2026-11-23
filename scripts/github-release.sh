#!/usr/bin/env bash
# GitHub Release를 만들고 APK와 .sha256을 올린다(docs/release.md).
#   GITHUB_TOKEN=… scripts/github-release.sh <버전> [본문.md]
# 파일은 ~/shared/youngmin-v<버전>.apk(.sha256)를 쓴다(SHARED_DIR로 바꿀 수 있음).
# 토큰은 환경변수로만 받고 출력하지 않는다. draft/prerelease가 아닌 정식 릴리스로 만든다.
set -euo pipefail

REPO="manig235/ETLAssistant"
VERSION="${1:?사용법: $0 <버전> [본문.md]}"
NOTES_FILE="${2:-}"
SHARED_DIR="${SHARED_DIR:-$HOME/shared}"
TAG="v${VERSION}"
APK="${SHARED_DIR}/youngmin-v${VERSION}.apk"
SUM="${APK}.sha256"

[[ "$VERSION" =~ ^[0-9]+(\.[0-9]+){1,3}$ ]] || { echo "버전 형식이 아니에요: $VERSION" >&2; exit 1; }
: "${GITHUB_TOKEN:?GITHUB_TOKEN 환경변수가 필요해요}"
[[ -f "$APK" && -f "$SUM" ]] || { echo "파일이 없어요: $APK / $SUM" >&2; exit 1; }
(cd "$(dirname "$APK")" && sha256sum -c "$(basename "$SUM")" >/dev/null) || { echo "sha256이 맞지 않아요" >&2; exit 1; }
command -v jq >/dev/null || { echo "jq가 필요해요" >&2; exit 1; }

# 토큰은 curl 설정 파일(stdin)로만 넘겨 프로세스 목록·출력에 나타나지 않게 한다.
auth_cfg() { printf 'header = "Authorization: Bearer %s"\n' "$GITHUB_TOKEN"; }
api() { auth_cfg | curl -sS --fail-with-body -K - -H "Accept: application/vnd.github+json" -H "X-GitHub-Api-Version: 2022-11-28" "$@"; }

if [[ -n "$NOTES_FILE" ]]; then BODY="$(cat "$NOTES_FILE")"; else BODY="영민이 ${VERSION}"; fi
DOWNLOAD_URL="https://github.com/${REPO}/releases/download/${TAG}/youngmin-v${VERSION}.apk"
if [[ "$BODY" != *"$DOWNLOAD_URL"* ]]; then
  BODY+=$'\n\n## 다운로드\n'
  BODY+="[youngmin-v${VERSION}.apk](${DOWNLOAD_URL})"
fi
TARGET="$(git rev-parse HEAD)"

payload="$(jq -n --arg tag "$TAG" --arg name "영민이 ${VERSION}" --arg body "$BODY" --arg target "$TARGET" \
  '{tag_name:$tag, target_commitish:$target, name:$name, body:$body, draft:false, prerelease:false}')"

echo "릴리스 만드는 중: $TAG ($TARGET)"
resp="$(api -X POST "https://api.github.com/repos/${REPO}/releases" -d "$payload")"
upload_url="$(jq -r '.upload_url' <<<"$resp" | sed 's/{.*}//')"
html_url="$(jq -r '.html_url' <<<"$resp")"
[[ "$upload_url" == https://uploads.github.com/* ]] || { echo "릴리스를 만들지 못했어요" >&2; jq -r '.message // empty' <<<"$resp" >&2; exit 1; }

for f in "$APK" "$SUM"; do
  name="$(basename "$f")"
  ctype="application/octet-stream"
  [[ "$name" == *.apk ]] && ctype="application/vnd.android.package-archive"
  [[ "$name" == *.sha256 ]] && ctype="text/plain"
  echo "올리는 중: $name"
  api -X POST -H "Content-Type: $ctype" --data-binary @"$f" "${upload_url}?name=${name}" >/dev/null
done

echo "완료: $html_url"
