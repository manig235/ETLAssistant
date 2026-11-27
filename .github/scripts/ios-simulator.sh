#!/usr/bin/env bash
# GitHub macOS runner: no signing certificates or Apple account required.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"
mkdir -p ios-test-results
xcodebuild -version | tee ios-test-results/toolchain.log
git rev-parse HEAD > ios-test-results/revision.log
xcrun simctl list devices available --json > ios-test-results/devices.json

# Use an installed device from the newest available iOS runtime. Do not
# hard-code a model or OS version that can disappear from runner images.
# YOUNGMIN_SIMULATOR is a name prefix ("iPhone", "iPad Pro 13-inch", "iPad mini").
export YOUNGMIN_SIMULATOR="${YOUNGMIN_SIMULATOR:-iPhone}"
simulator_id="$(python3 - <<'PY'
import json
import os
import re

prefix = os.environ['YOUNGMIN_SIMULATOR']
with open('ios-test-results/devices.json') as stream:
    runtimes = json.load(stream)['devices']
for runtime in sorted(runtimes, key=lambda value: tuple(map(int, re.findall(r'\d+', value))), reverse=True):
    if '.iOS-' not in runtime:
        continue
    for device in runtimes[runtime]:
        if device.get('isAvailable') and device['name'].startswith(prefix):
            print(device['udid'])
            raise SystemExit(0)
raise SystemExit(f'No available {prefix} simulator. See ios-test-results/devices.json.')
PY
)"
echo "Simulator ($YOUNGMIN_SIMULATOR): $simulator_id" | tee ios-test-results/destination.log
xcrun simctl list devices | grep "$simulator_id" | tee -a ios-test-results/destination.log || true

cleanup() {
  # Best effort: a compile error may mean no app ever reached the screen.
  xcrun simctl io "$simulator_id" screenshot ios-test-results/last-screen.png || true
  xcrun simctl shutdown "$simulator_id" || true
}
trap cleanup EXIT
xcrun simctl boot "$simulator_id"
xcrun simctl bootstatus "$simulator_id" -b
xcrun simctl status_bar "$simulator_id" override --time '9:41' --batteryState charged --batteryLevel 100

# Disabling parallel testing keeps screenshot capture on the selected device.
# pipefail ensures a failing build/test fails CI even though tee succeeds.
native_args=('YOUNGMIN_NATIVE_CONDITIONS=' '-skip-testing:YoungminUITests/YoungminUITests/testNativeCodexStartup')
if [[ "${YOUNGMIN_USE_NATIVE_CODEX:-0}" == 1 ]]; then
  test -f "ios/NativeCodex/Generated/iphonesimulator/$(uname -m)/libyoungmin_codex_bridge.a"
  native_system_libs="$(cat "ios/NativeCodex/Generated/iphonesimulator/$(uname -m)/native-static-libs.txt")"
  native_args=(
    'YOUNGMIN_NATIVE_CONDITIONS=YOUNGMIN_NATIVE_CODEX'
    'YOUNGMIN_NATIVE_BRIDGING_HEADER=$(PROJECT_DIR)/NativeCodex/include/YoungminCodex.h'
    'YOUNGMIN_NATIVE_LIBRARY_PATH=$(PROJECT_DIR)/NativeCodex/Generated/$(PLATFORM_NAME)/$(CURRENT_ARCH)'
    "YOUNGMIN_NATIVE_LDFLAGS=-lyoungmin_codex_bridge $native_system_libs"
  )
fi
xcodebuild test \
  -project ios/Youngmin.xcodeproj -scheme Youngmin \
  -configuration Debug \
  -destination "platform=iOS Simulator,id=$simulator_id" \
  -destination-timeout 120 \
  -derivedDataPath "${RUNNER_TEMP:-/tmp}/youngmin-ios-derived" \
  -resultBundlePath "$PWD/ios-test-results/Youngmin.xcresult" \
  -parallel-testing-enabled NO \
  CODE_SIGNING_ALLOWED=NO \
  "${native_args[@]}" \
  2>&1 | tee ios-test-results/xcodebuild.log

# The built app and extensions must declare iPhone + iPad and the iPad orientations
# needed for Split View, Slide Over and Stage Manager (no UIRequiresFullScreen).
python3 .github/scripts/check-ios-device-family.py \
  "${RUNNER_TEMP:-/tmp}/youngmin-ios-derived/Build/Products/Debug-iphonesimulator/Youngmin.app" \
  | tee ios-test-results/device-family.log
