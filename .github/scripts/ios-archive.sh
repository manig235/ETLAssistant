#!/usr/bin/env bash
# Release archive -> engine verification -> signed export -> optional upload.
#
# The archive uses the committed Release configuration only; the Codex engine
# settings come from ios/Config/NativeCodex.xcconfig, never from this script.
# Build the engine first (ios/NativeCodex/Generated/iphoneos/arm64).
#
# Environment (all optional):
#   YOUNGMIN_EXPORT_METHOD  app-store-connect (TestFlight, default) | release-testing (Ad Hoc)
#   YOUNGMIN_EXTENSIONS     app-only (default) | full
#   YOUNGMIN_BUILD_NUMBER   CFBundleVersion; default <project build>.<GITHUB_RUN_NUMBER>
#   YOUNGMIN_UPLOAD         1 = upload the exported IPA to App Store Connect
#   YOUNGMIN_SIGNING_DRY_RUN 1 = without real secrets, exercise keychain, profile
#                           and ExportOptions handling with a throwaway identity
# Signing secrets (all required for a signed export; never printed):
#   IOS_DIST_P12_BASE64 IOS_DIST_P12_PASSWORD IOS_TEAM_ID IOS_PROFILE_APP_BASE64
#   full mode also: IOS_PROFILE_WIDGETS_BASE64 IOS_PROFILE_SHARE_BASE64
# Upload secrets: ASC_KEY_ID ASC_ISSUER_ID ASC_KEY_P8
set -euo pipefail
set +x
cd "$(git rev-parse --show-toplevel)"

helper=.github/scripts/ios-release-helper.py
out="$PWD/ios-release-output"
method="${YOUNGMIN_EXPORT_METHOD:-app-store-connect}"
mode="${YOUNGMIN_EXTENSIONS:-app-only}"
upload="${YOUNGMIN_UPLOAD:-0}"
dry_run="${YOUNGMIN_SIGNING_DRY_RUN:-0}"
case "$method" in app-store-connect|release-testing) ;; *) echo "Unsupported export method: $method" >&2; exit 1 ;; esac
case "$mode" in app-only|full) ;; *) echo "Unsupported extension mode: $mode" >&2; exit 1 ;; esac
if [[ "$upload" == 1 && "$method" != app-store-connect ]]; then
  echo "Upload is only possible for app-store-connect exports." >&2; exit 1
fi

rm -rf "$out"
mkdir -p "$out"
work="$(mktemp -d "${RUNNER_TEMP:-/tmp}/youngmin-release.XXXXXX")"
xcodebuild -version | tee "$out/toolchain.log"
git rev-parse HEAD | tee "$out/revision.log"

build="$(python3 "$helper" build-number --run "${GITHUB_RUN_NUMBER:-}" --override "${YOUNGMIN_BUILD_NUMBER:-}")"
marketing="$(python3 "$helper" marketing-version)"
echo "Version $marketing ($build), $mode, $method"
python3 "$helper" native-check --platform iphoneos --arch arm64

# ------------------------------------------------------------------ cleanup
keychain=""
original_keychains=()
installed_profiles=()
app_only_project=""
cleanup() {
  local status=$?
  if [[ -n "$keychain" ]]; then
    if ((${#original_keychains[@]})); then
      security list-keychains -d user -s "${original_keychains[@]}" || true
    fi
    security delete-keychain "$keychain" 2>/dev/null || true
  fi
  if ((${#installed_profiles[@]})); then rm -f "${installed_profiles[@]}"; fi
  if [[ -n "$app_only_project" ]]; then rm -rf "$app_only_project"; fi
  rm -rf "$work"
  exit "$status"
}
trap cleanup EXIT

# --------------------------------------------------------- signing inputs
required=(IOS_DIST_P12_BASE64 IOS_DIST_P12_PASSWORD IOS_TEAM_ID IOS_PROFILE_APP_BASE64)
if [[ "$mode" == full ]]; then required+=(IOS_PROFILE_WIDGETS_BASE64 IOS_PROFILE_SHARE_BASE64); fi
present=() missing=()
for name in "${required[@]}"; do
  if [[ -n "${!name:-}" ]]; then present+=("$name"); else missing+=("$name"); fi
done
signing=0
if ((${#missing[@]} == 0)); then
  signing=1
  dry_run=0
elif ((${#present[@]})); then
  echo "Incomplete signing secrets. Missing: ${missing[*]}" >&2; exit 1
fi

make_dry_run_identity() {
  # Self-signed stand-ins with the same shape as Apple's files. Xcode cannot
  # sign with them, so the archive stays unsigned and export is skipped.
  local dir="$work/dry-run" ssl=openssl
  if [[ -x /usr/bin/openssl ]]; then ssl=/usr/bin/openssl; fi
  mkdir -p "$dir"
  cat > "$dir/cert.cnf" <<'CNF'
[req]
distinguished_name = dn
x509_extensions = ext
prompt = no
[dn]
CN = Apple Distribution: Youngmin Dry Run (DRYRUN0000)
OU = DRYRUN0000
O = Youngmin Dry Run
C = KR
[ext]
basicConstraints = critical,CA:false
keyUsage = critical,digitalSignature
extendedKeyUsage = critical,codeSigning
CNF
  "$ssl" req -x509 -newkey rsa:2048 -nodes -days 2 -config "$dir/cert.cnf" \
    -keyout "$dir/key.pem" -out "$dir/cert.pem" 2>/dev/null
  local password
  password="$("$ssl" rand -hex 16)"
  "$ssl" pkcs12 -export -inkey "$dir/key.pem" -in "$dir/cert.pem" -passout "pass:$password" -out "$dir/dist.p12"
  "$ssl" x509 -in "$dir/cert.pem" -outform der -out "$dir/cert.der"
  local targets=("kr.snu.etlassistant.ios:APP")
  if [[ "$mode" == full ]]; then targets+=("kr.snu.etlassistant.ios.widgets:WIDGETS" "kr.snu.etlassistant.ios.share:SHARE"); fi
  local entry bundle key
  for entry in "${targets[@]}"; do
    bundle="${entry%%:*}" key="${entry##*:}"
    python3 - "$dir/cert.der" "$bundle" "$method" "$dir/$key.plist" <<'PY'
import datetime, plistlib, sys, uuid
cert, bundle, method, out = sys.argv[1:]
profile = {
    "Name": f"Youngmin dry run {bundle}", "UUID": str(uuid.uuid4()).upper(),
    "TeamIdentifier": ["DRYRUN0000"],
    "ExpirationDate": datetime.datetime.now(datetime.timezone.utc) + datetime.timedelta(days=1),
    "DeveloperCertificates": [open(cert, "rb").read()],
    "Entitlements": {"application-identifier": f"DRYRUN0000.{bundle}", "get-task-allow": False,
                     "com.apple.developer.team-identifier": "DRYRUN0000",
                     "com.apple.security.application-groups": ["group.kr.snu.etlassistant.ios"]},
}
if method == "release-testing":
    profile["ProvisionedDevices"] = ["00008110-000000000000001E"]
open(out, "wb").write(plistlib.dumps(profile))
PY
    "$ssl" smime -sign -binary -nodetach -outform der -signer "$dir/cert.pem" -inkey "$dir/key.pem" \
      -in "$dir/$key.plist" -out "$dir/$key.mobileprovision"
    printf -v "IOS_PROFILE_${key}_BASE64" '%s' "$(base64 < "$dir/$key.mobileprovision" | tr -d '\n')"
  done
  IOS_DIST_P12_BASE64="$(base64 < "$dir/dist.p12" | tr -d '\n')"
  IOS_DIST_P12_PASSWORD="$password"
  IOS_TEAM_ID=DRYRUN0000
}

decode_secret() { # name file
  printf '%s' "${!1}" | tr -d ' \r\n' | base64 --decode > "$2"
  [[ -s "$2" ]] || { echo "$1 is empty or not base64" >&2; exit 1; }
}

identity_name="" identity_sha1="" profile_args=() export_profiles=()
setup_signing() {
  [[ "$IOS_TEAM_ID" =~ ^[A-Z0-9]{10}$ ]] || { echo "IOS_TEAM_ID must be the 10-character team ID" >&2; exit 1; }
  local p12="$work/dist.p12" password
  decode_secret IOS_DIST_P12_BASE64 "$p12"
  keychain="$work/youngmin-signing.keychain-db"
  password="$(openssl rand -hex 24)"
  read -r -a original_keychains < <(security list-keychains -d user | tr -d '"' | xargs)
  security create-keychain -p "$password" "$keychain"
  security set-keychain-settings -lut 7200 "$keychain"
  security unlock-keychain -p "$password" "$keychain"
  security list-keychains -d user -s "$keychain" "${original_keychains[@]}"
  security import "$p12" -k "$keychain" -f pkcs12 -P "$IOS_DIST_P12_PASSWORD" \
    -T /usr/bin/codesign -T /usr/bin/security -T /usr/bin/productbuild > /dev/null
  # The Apple WWDR G3 intermediate completes the distribution certificate chain.
  if curl -fsSL --retry 3 -o "$work/wwdr.cer" https://www.apple.com/certificateauthority/AppleWWDRCAG3.cer &&
     echo "dcf21878c77f4198e4b4614f03d696d89c66c66008d4244e1b99161aac91601f  $work/wwdr.cer" | shasum -a 256 -c - > /dev/null; then
    security import "$work/wwdr.cer" -k "$keychain" > /dev/null 2>&1 || true
  else
    echo "warning: could not fetch the Apple WWDR G3 certificate; relying on the runner's copy" >&2
  fi
  security set-key-partition-list -S apple-tool:,apple:,codesign: -s -k "$password" "$keychain" > /dev/null
  local identities line
  identities="$(security find-identity -v -p codesigning "$keychain" || true)"
  if ! grep -q '"' <<<"$identities"; then
    [[ "$dry_run" == 1 ]] || { echo "No valid codesigning identity in IOS_DIST_P12_BASE64 (expired, or missing WWDR chain?)" >&2; exit 1; }
    identities="$(security find-identity -p codesigning "$keychain")"
  fi
  line="$(grep -E '"(Apple|iPhone) Distribution' <<<"$identities" | head -n 1 || true)"
  [[ -n "$line" ]] || { echo "The .p12 does not contain an Apple Distribution identity" >&2; exit 1; }
  identity_sha1="$(awk '{print $2}' <<<"$line")"
  identity_name="$(sed -E 's/^[^"]*"(.*)"[^"]*$/\1/' <<<"$line")"
  [[ "$identity_name" == *"($IOS_TEAM_ID)"* ]] || { echo "The distribution certificate belongs to another team than IOS_TEAM_ID" >&2; exit 1; }
  echo "Signing identity: $identity_name"

  local entries=("Youngmin:kr.snu.etlassistant.ios:IOS_PROFILE_APP_BASE64")
  local group_args=()
  if [[ "$mode" == full ]]; then
    entries+=("YoungminWidgets:kr.snu.etlassistant.ios.widgets:IOS_PROFILE_WIDGETS_BASE64"
              "YoungminShare:kr.snu.etlassistant.ios.share:IOS_PROFILE_SHARE_BASE64")
    group_args=(--app-group group.kr.snu.etlassistant.ios)
  fi
  local entry target bundle secret file info uuid dir
  mkdir -p "$HOME/Library/MobileDevice/Provisioning Profiles" "$HOME/Library/Developer/Xcode/UserData/Provisioning Profiles"
  for entry in "${entries[@]}"; do
    IFS=: read -r target bundle secret <<<"$entry"
    file="$work/$target.mobileprovision"
    decode_secret "$secret" "$file"
    info="$(python3 "$helper" profile-info --profile "$file" --method "$method" --team "$IOS_TEAM_ID" \
      --bundle-id "$bundle" --certificate-sha1 "$identity_sha1" ${group_args[@]+"${group_args[@]}"})"
    uuid="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["uuid"])' <<<"$info")"
    echo "Profile for $target: $info"
    for dir in "$HOME/Library/MobileDevice/Provisioning Profiles" "$HOME/Library/Developer/Xcode/UserData/Provisioning Profiles"; do
      cp "$file" "$dir/$uuid.mobileprovision"
      installed_profiles+=("$dir/$uuid.mobileprovision")
    done
    # Per-target values: package targets resolve to an empty specifier.
    profile_args+=("YOUNGMIN_PROFILE_$target=$uuid")
    export_profiles+=(--profile "$bundle=$uuid")
  done
  python3 "$helper" export-options --method "$method" --team "$IOS_TEAM_ID" --certificate "$identity_sha1" \
    "${export_profiles[@]}" --output "$out/ExportOptions.plist"
  plutil -lint "$out/ExportOptions.plist"
}

if [[ "$signing" == 0 && "$dry_run" == 1 ]]; then make_dry_run_identity; fi
if [[ "$signing" == 1 || "$dry_run" == 1 ]]; then setup_signing; fi

# ----------------------------------------------------------------- archive
project=ios/Youngmin.xcodeproj
if [[ "$mode" == app-only ]]; then
  app_only_project="$PWD/ios/YoungminAppOnly.xcodeproj"
  python3 "$helper" app-only-project --source ios/Youngmin.xcodeproj --dest "$app_only_project"
  project="$app_only_project"
fi
common=(-project "$project" -scheme Youngmin -configuration Release -destination 'generic/platform=iOS'
        -derivedDataPath "$work/derived" "CURRENT_PROJECT_VERSION=$build")
signing_args=(CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO CODE_SIGN_IDENTITY= CODE_SIGN_ENTITLEMENTS=)
if [[ "$signing" == 1 || "$dry_run" == 1 ]]; then
  planned=(CODE_SIGN_STYLE=Manual "DEVELOPMENT_TEAM=$IOS_TEAM_ID" "CODE_SIGN_IDENTITY=$identity_name"
           'PROVISIONING_PROFILE_SPECIFIER=$(YOUNGMIN_PROFILE_$(TARGET_NAME))' "${profile_args[@]}"
           "OTHER_CODE_SIGN_FLAGS=--keychain $keychain")
  # App-only builds ship without the App Group, so the app's App ID needs no capability.
  if [[ "$mode" == app-only ]]; then planned+=(CODE_SIGN_ENTITLEMENTS=); fi
  xcodebuild -showBuildSettings "${common[@]}" "${planned[@]}" > "$out/signing-settings.log" 2>&1
  python3 - "$out/signing-settings.log" "$mode" "${profile_args[@]}" <<'PY'
import re, sys
log, mode, *pairs = sys.argv[1:]
expected = dict(p.removeprefix("YOUNGMIN_PROFILE_").split("=", 1) for p in pairs)
actual, target = {}, None
for line in open(log):
    m = re.match(r"Build settings for action \w+ and target (.+):", line)
    if m: target = m[1]; continue
    m = re.match(r"\s+PROVISIONING_PROFILE_SPECIFIER = (.*)", line)
    if m and target: actual[target] = m[1].strip()
apps = {t: v for t, v in actual.items() if t in ("Youngmin", "YoungminWidgets", "YoungminShare")}
if apps != expected: raise SystemExit(f"Per-target provisioning profiles resolve to {apps}, expected {expected}")
others = {t: v for t, v in actual.items() if t not in apps and v}
if others: raise SystemExit(f"Profiles leak into non-app targets: {others}")
print(f"PASS: per-target provisioning profiles resolve correctly for {sorted(apps)} ({mode})")
PY
  if [[ "$signing" == 1 ]]; then signing_args=("${planned[@]}"); fi
fi

archive="$out/Youngmin.xcarchive"
xcodebuild archive "${common[@]}" -archivePath "$archive" "${signing_args[@]}" \
  2>&1 | tee "$out/xcodebuild-archive.log"

python3 "$helper" verify-archive --archive "$archive" --mode "$mode" --build "$build" --marketing "$marketing" \
  | tee "$out/verify.log"
python3 .github/scripts/check-ios-private-apis.py "$archive/Products/Applications/Youngmin.app/Youngmin" | tee -a "$out/verify.log"
(cd "$out" && ditto -c -k --keepParent Youngmin.xcarchive Youngmin.xcarchive.zip)

# ------------------------------------------------------------ export/upload
result="unsigned archive (export skipped)"
if [[ "$signing" == 1 ]]; then
  xcodebuild -exportArchive -archivePath "$archive" -exportPath "$out/export" \
    -exportOptionsPlist "$out/ExportOptions.plist" 2>&1 | tee "$out/xcodebuild-export.log"
  ipa="$out/export/Youngmin.ipa"
  [[ -s "$ipa" ]] || { echo "Export did not produce Youngmin.ipa" >&2; exit 1; }
  rm -rf "$work/ipa" && mkdir -p "$work/ipa" && unzip -q "$ipa" -d "$work/ipa"
  codesign --verify --deep --strict "$work/ipa/Payload/Youngmin.app"
  codesign -dv "$work/ipa/Payload/Youngmin.app" 2>&1 | grep -E '^(Identifier|TeamIdentifier|Authority)=' | tee -a "$out/verify.log"
  [[ -f "$work/ipa/Payload/Youngmin.app/embedded.mobileprovision" ]] || { echo "IPA lacks embedded.mobileprovision" >&2; exit 1; }
  shasum -a 256 "$ipa" | sed "s#  .*/#  #" > "$ipa.sha256"
  result="signed $method IPA"
  if [[ "$upload" == 1 ]]; then
    for name in ASC_KEY_ID ASC_ISSUER_ID ASC_KEY_P8; do
      [[ -n "${!name:-}" ]] || { echo "Upload requested but $name is not set" >&2; exit 1; }
    done
    [[ "$ASC_KEY_ID" =~ ^[A-Z0-9]{10}$ ]] || { echo "ASC_KEY_ID has an unexpected format" >&2; exit 1; }
    [[ "$ASC_ISSUER_ID" =~ ^[0-9a-fA-F-]{36}$ ]] || { echo "ASC_ISSUER_ID has an unexpected format" >&2; exit 1; }
    keys="$work/private_keys"
    mkdir -p "$keys"
    (umask 077 && printf '%s\n' "$ASC_KEY_P8" > "$keys/AuthKey_$ASC_KEY_ID.p8")
    API_PRIVATE_KEYS_DIR="$keys" xcrun altool --validate-app -f "$ipa" -t ios \
      --apiKey "$ASC_KEY_ID" --apiIssuer "$ASC_ISSUER_ID" 2>&1 | tee "$out/altool-validate.log"
    API_PRIVATE_KEYS_DIR="$keys" xcrun altool --upload-app -f "$ipa" -t ios \
      --apiKey "$ASC_KEY_ID" --apiIssuer "$ASC_ISSUER_ID" 2>&1 | tee "$out/altool-upload.log"
    result="signed IPA uploaded to App Store Connect (processing continues there)"
  fi
elif [[ "$dry_run" == 1 ]]; then
  result="unsigned archive; signing dry run passed (throwaway identity, export skipped)"
  echo "Signing secrets are not configured: the throwaway identity verified keychain, profile and ExportOptions handling only."
else
  echo "Signing secrets are not configured: produced and verified the unsigned archive; skipping export."
fi

python3 - "$out/summary.json" "$marketing" "$build" "$mode" "$method" "$result" <<'PY'
import json, os, sys
out, marketing, build, mode, method, result = sys.argv[1:]
json.dump({"version": marketing, "build": build, "extensions": mode, "method": method, "result": result,
           "revision": os.popen("git rev-parse HEAD").read().strip()}, open(out, "w"), indent=2)
PY
if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
  {
    echo "### 영민이 Release archive"
    echo "- 버전: $marketing ($build), 확장: $mode, 방식: $method"
    echo "- 결과: $result"
    echo "- 확인: $(head -n 1 "$out/verify.log")"
  } >> "$GITHUB_STEP_SUMMARY"
fi
echo "Done: $result"
