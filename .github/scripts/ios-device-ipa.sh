#!/usr/bin/env bash
# Compile on GitHub's Mac; signing happens on the user's Windows PC.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
mkdir -p ios-device-output
xcodebuild -version | tee ios-device-output/toolchain.log
git rev-parse HEAD | tee ios-device-output/revision.log

device_derived="${RUNNER_TEMP:-/tmp}/youngmin-device-derived"
xcodebuild build \
  -project ios/Youngmin.xcodeproj -scheme Youngmin \
  -configuration Debug -sdk iphoneos \
  -destination 'generic/platform=iOS' \
  -derivedDataPath "$device_derived" \
  ARCHS=arm64 ONLY_ACTIVE_ARCH=NO ENABLE_DEBUG_DYLIB=NO \
  CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO \
  CODE_SIGN_IDENTITY= CODE_SIGN_ENTITLEMENTS= \
  2>&1 | tee ios-device-output/xcodebuild.log

# Package a copy, keeping the normal app/extension build products intact.
python3 .github/scripts/package-device-ipa.py \
  --app "$device_derived/Build/Products/Debug-iphoneos/Youngmin.app" \
  --output ios-device-output/Youngmin-unsigned.ipa \
  2>&1 | tee ios-device-output/package.log
cp ios/INSTALL-WINDOWS.md ios-device-output/INSTALL-WINDOWS.md

if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
  cat >> "$GITHUB_STEP_SUMMARY" <<'EOF'
### 아이패드·아이폰 설치용 IPA 생성 완료

**Artifacts → youngmin-device-ipa-…**를 내려받고 ZIP 압축을 해제하세요.
안의 `Youngmin-unsigned.ipa`를 Windows의 Sideloadly로 불러와 Apple 계정으로 서명·설치합니다.
함께 제공된 `INSTALL-WINDOWS.md`에 단계별 설명이 있습니다.

앱 본체를 담은 Debug 빌드입니다. 위젯·공유 확장은 포함하지 않습니다.
실제 아이패드 설치와 실행은 사용자 기기에서 확인해야 합니다.
EOF
fi
