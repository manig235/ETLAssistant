#!/usr/bin/env bash
# Compile on GitHub's Mac; signing happens on the user's Windows PC.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
mkdir -p ios-device-output
xcodebuild -version | tee ios-device-output/toolchain.log
git rev-parse HEAD | tee ios-device-output/revision.log

device_derived="${RUNNER_TEMP:-/tmp}/youngmin-device-derived"
native_args=('YOUNGMIN_NATIVE_CONDITIONS=')
if [[ "${YOUNGMIN_USE_NATIVE_CODEX:-0}" == 1 ]]; then
  test -f ios/NativeCodex/Generated/iphoneos/arm64/libyoungmin_codex_bridge.a
  native_args=(
    'YOUNGMIN_NATIVE_CONDITIONS=YOUNGMIN_NATIVE_CODEX'
    'YOUNGMIN_NATIVE_BRIDGING_HEADER=$(PROJECT_DIR)/NativeCodex/include/YoungminCodex.h'
    'YOUNGMIN_NATIVE_LIBRARY_PATH=$(PROJECT_DIR)/NativeCodex/Generated/$(PLATFORM_NAME)/$(CURRENT_ARCH)'
    'YOUNGMIN_NATIVE_LDFLAGS=-lyoungmin_codex_bridge -lc++ -lz -liconv -framework Security -framework SystemConfiguration -framework CoreFoundation -framework Foundation -framework Network'
    'INFOPLIST_KEY_YoungminAuthEngine=codex-in-process'
    'INFOPLIST_KEY_YoungminCodexSourceRevision=29eb79e9866b3262a777fdf8195c1b9e63684c91'
    'CURRENT_PROJECT_VERSION=23'
  )
fi
xcodebuild build \
  -project ios/Youngmin.xcodeproj -scheme Youngmin \
  -configuration Debug -sdk iphoneos \
  -destination 'generic/platform=iOS' \
  -derivedDataPath "$device_derived" \
  ARCHS=arm64 ONLY_ACTIVE_ARCH=NO ENABLE_DEBUG_DYLIB=NO \
  CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO \
  CODE_SIGN_IDENTITY= CODE_SIGN_ENTITLEMENTS= \
  "${native_args[@]}" \
  2>&1 | tee ios-device-output/xcodebuild.log

# Package a copy, keeping the normal app/extension build products intact.
if [[ "${YOUNGMIN_USE_NATIVE_CODEX:-0}" == 1 ]]; then
  cp ios/NativeCodex/Generated/*LICENSE* "$device_derived/Build/Products/Debug-iphoneos/Youngmin.app/"
  if compgen -G 'ios/NativeCodex/Generated/*NOTICE*' > /dev/null; then
    cp ios/NativeCodex/Generated/*NOTICE* "$device_derived/Build/Products/Debug-iphoneos/Youngmin.app/"
  fi
fi
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
