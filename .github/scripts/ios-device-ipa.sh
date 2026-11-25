#!/usr/bin/env bash
# Compile on GitHub's Mac; signing happens on the user's Windows PC.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
mkdir -p ios-device-output
build_config="${YOUNGMIN_CONFIGURATION:-Debug}"
case "$build_config" in Debug|Release) ;; *) echo "Unsupported build configuration" >&2; exit 1 ;; esac
xcodebuild -version | tee ios-device-output/toolchain.log
git rev-parse HEAD | tee ios-device-output/revision.log

device_derived="${RUNNER_TEMP:-/tmp}/youngmin-device-derived"
# Release always embeds Codex through ios/Config/NativeCodex.xcconfig. Debug can
# opt in with the same file; no engine settings are duplicated here.
native_args=()
if [[ "${YOUNGMIN_USE_NATIVE_CODEX:-0}" == 1 ]]; then
  python3 .github/scripts/ios-release-helper.py native-check --platform iphoneos --arch arm64
  if [[ "$build_config" == Debug ]]; then native_args=(-xcconfig ios/Config/NativeCodex.xcconfig); fi
elif [[ "$build_config" == Release ]]; then
  echo "Release builds always embed the native Codex engine; build it and set YOUNGMIN_USE_NATIVE_CODEX=1." >&2
  exit 1
fi
xcodebuild build \
  -project ios/Youngmin.xcodeproj -scheme Youngmin \
  -configuration "$build_config" -sdk iphoneos \
  -destination 'generic/platform=iOS' \
  -derivedDataPath "$device_derived" \
  ARCHS=arm64 ONLY_ACTIVE_ARCH=NO ENABLE_DEBUG_DYLIB=NO \
  CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO \
  CODE_SIGN_IDENTITY= CODE_SIGN_ENTITLEMENTS= \
  ${native_args[@]+"${native_args[@]}"} \
  2>&1 | tee ios-device-output/xcodebuild.log

# Package a copy, keeping the normal app/extension build products intact.
# Third-party licenses are bundled resources (ios/Youngmin/Resources/Licenses).
if [[ "${YOUNGMIN_USE_NATIVE_CODEX:-0}" == 1 ]]; then
  python3 .github/scripts/check-ios-private-apis.py "$device_derived/Build/Products/$build_config-iphoneos/Youngmin.app/Youngmin"
fi
python3 .github/scripts/package-device-ipa.py \
  --app "$device_derived/Build/Products/$build_config-iphoneos/Youngmin.app" \
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
