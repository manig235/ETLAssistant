#!/usr/bin/env python3
"""Manual Release archive/export for Ad Hoc (Firebase) or App Store Connect (TestFlight).

Both distributions share the archive, signing and IPA verification. Never prints credentials.
"""

import argparse
import base64
import binascii
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import plistlib
import re
import secrets
import shlex
import shutil
import signal
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[2]
OUTPUT = ROOT / "ios-distribution-output"
APP_GROUP = "group.kr.snu.etlassistant.ios"
APP_BUNDLE = "kr.snu.etlassistant.ios"
CODEX_REVISION = "29eb79e9866b3262a777fdf8195c1b9e63684c91"
# Each extension has its own App ID and provisioning profile (one set per distribution kind).
BUNDLES = (
    (APP_BUNDLE, "APP", "YOUNGMIN_APP_PROFILE"),
    (APP_BUNDLE + ".widgets", "WIDGETS", "YOUNGMIN_WIDGETS_PROFILE"),
    (APP_BUNDLE + ".share", "SHARE", "YOUNGMIN_SHARE_PROFILE"),
)
# Shipped privacy manifests: bundle path inside the exported .app -> source file.
PRIVACY_MANIFESTS = {
    ".": "ios/Youngmin/PrivacyInfo.xcprivacy",
    "PlugIns/YoungminWidgets.appex": "ios/YoungminWidgets/PrivacyInfo.xcprivacy",
    "PlugIns/YoungminShare.appex": "ios/YoungminShare/PrivacyInfo.xcprivacy",
}
DISTRIBUTIONS = {
    # Firebase App Distribution: profiles list registered devices.
    "ad-hoc": {"method": "release-testing", "secret_prefix": "IOS_", "ipa": "Youngmin-AdHoc.ipa",
               "label": "Ad Hoc"},
    # TestFlight / App Store: profiles have no device list and use their own secrets.
    "app-store": {"method": "app-store-connect", "secret_prefix": "IOS_APPSTORE_", "ipa": "Youngmin-AppStore.ipa",
                  "label": "App Store Connect"},
}


def targets(kind="ad-hoc"):
    prefix = DISTRIBUTIONS[kind]["secret_prefix"]
    return tuple((bundle, prefix + name + "_PROFILE_BASE64", variable) for bundle, name, variable in BUNDLES)


TARGETS = targets("ad-hoc")
SIGNING_KEYS = ("IOS_DISTRIBUTION_P12_BASE64", "IOS_DISTRIBUTION_P12_PASSWORD", "IOS_TEAM_ID")
FIREBASE_KEYS = ("FIREBASE_APP_ID", "FIREBASE_GROUPS", "FIREBASE_SERVICE_ACCOUNT_JSON")
ASC_KEYS = ("ASC_KEY_ID", "ASC_ISSUER_ID", "ASC_KEY_P8", "ASC_APP_ID")
# TestFlight build numbers must grow; run numbers restart per workflow, so they get an offset.
DEFAULT_BUILD_OFFSET = 1000


def require(keys, env):
    missing = [key for key in keys if not env.get(key, "").strip()]
    if missing:
        raise ValueError("Missing settings: " + ", ".join(missing))


def firebase_config(env):
    require(FIREBASE_KEYS, env)
    if not re.fullmatch(r"1:[0-9]+:ios:[a-zA-Z0-9]+", env["FIREBASE_APP_ID"]):
        raise ValueError("FIREBASE_APP_ID must be the Firebase iOS App ID, not a Bundle ID")
    if not re.fullmatch(r"[a-zA-Z0-9_-]+(?:\s*,\s*[a-zA-Z0-9_-]+)*", env["FIREBASE_GROUPS"]):
        raise ValueError("FIREBASE_GROUPS must contain comma-separated group aliases")
    try:
        account = json.loads(env["FIREBASE_SERVICE_ACCOUNT_JSON"])
    except (ValueError, TypeError):
        raise ValueError("FIREBASE_SERVICE_ACCOUNT_JSON must be valid JSON") from None
    if (not isinstance(account, dict) or account.get("type") != "service_account"
            or not all(account.get(k) for k in ("project_id", "client_email", "private_key"))):
        raise ValueError("FIREBASE_SERVICE_ACCOUNT_JSON must contain a service account private key")


def asc_config(env):
    require(ASC_KEYS, env)
    if not re.fullmatch(r"[A-Z0-9]{10}", env["ASC_KEY_ID"]):
        raise ValueError("ASC_KEY_ID must be the 10-character App Store Connect API key ID")
    if not re.fullmatch(r"[0-9a-fA-F]{8}-(?:[0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}", env["ASC_ISSUER_ID"]):
        raise ValueError("ASC_ISSUER_ID must be the App Store Connect issuer UUID")
    if not re.fullmatch(r"[0-9]{6,12}", env["ASC_APP_ID"]):
        raise ValueError("ASC_APP_ID must be the numeric Apple ID of the App Store Connect app, not a Bundle ID")
    key = env["ASC_KEY_P8"].strip()
    if not (key.startswith("-----BEGIN PRIVATE KEY-----") and key.endswith("-----END PRIVATE KEY-----")):
        raise ValueError("ASC_KEY_P8 must contain the downloaded AuthKey .p8 file as text")


def project_build_number():
    text = (ROOT / "ios/Youngmin.xcodeproj/project.pbxproj").read_text()
    values = {int(v) for v in re.findall(r"CURRENT_PROJECT_VERSION = ([0-9]+);", text)}
    if not values:
        raise ValueError("CURRENT_PROJECT_VERSION is missing from the Xcode project")
    return max(values)


def build_number(env, kind="ad-hoc"):
    run_number, attempt = env.get("GITHUB_RUN_NUMBER", "1"), env.get("GITHUB_RUN_ATTEMPT", "1")
    if not (run_number.isdigit() and attempt.isdigit()):
        raise ValueError("Invalid workflow build number")
    if kind == "ad-hoc":
        return run_number + "." + attempt
    offset = env.get("IOS_TESTFLIGHT_BUILD_OFFSET", "").strip() or str(DEFAULT_BUILD_OFFSET)
    if not offset.isdigit():
        raise ValueError("IOS_TESTFLIGHT_BUILD_OFFSET must be a non-negative integer")
    build = int(offset) + int(run_number)
    # Apple compares builds numerically; stay above the project's local/sideload build number.
    if build <= project_build_number():
        raise ValueError("IOS_TESTFLIGHT_BUILD_OFFSET must keep builds above CURRENT_PROJECT_VERSION")
    return str(build) + "." + str(int(attempt))


def check_config(env, publish=False, kind="ad-hoc", upload=False):
    profile_keys = tuple(t[1] for t in targets(kind))
    # Report every missing name at once, before any slow native compilation.
    require(SIGNING_KEYS + profile_keys + (FIREBASE_KEYS if publish else ()) + (ASC_KEYS if upload else ()), env)
    if not re.fullmatch(r"[A-Z0-9]{10}", env["IOS_TEAM_ID"]):
        raise ValueError("IOS_TEAM_ID must be the 10-character Apple Team ID")
    for key in ("IOS_DISTRIBUTION_P12_BASE64",) + profile_keys:
        decode_secret(env[key], key)
    build_number(env, kind)
    if publish:
        firebase_config(env)
    if upload:
        asc_config(env)


def decode_secret(value, name):
    try:
        result = base64.b64decode("".join(value.split()), validate=True)
    except (ValueError, binascii.Error):
        raise ValueError(name + " must contain the file encoded as Base64") from None
    if not result:
        raise ValueError(name + " decodes to an empty file")
    return result


def run(args, *, data=None, capture=False, env=None):
    # subprocess errors must not echo command arguments (passwords can occur there).
    result = subprocess.run([str(a) for a in args], input=data, env=env,
                            stdout=subprocess.PIPE if capture else None,
                            stderr=subprocess.PIPE if capture else None)
    if result.returncode:
        command = Path(args[0]).name
        reason = ""
        if command == "security":
            # Only fixed operation names and classifications may reach logs.
            # Neither argv nor raw keychain output is safe to echo.
            allowed = {"list-keychains", "create-keychain", "set-keychain-settings", "unlock-keychain",
                       "import", "set-key-partition-list", "find-identity", "find-certificate", "cms"}
            if len(args) > 1 and args[1] in allowed:
                command += " " + args[1]
            diagnostic = (result.stderr or b"").lower()
            for marker, label in ((b"mac verification failed", "PKCS12_PASSWORD_OR_FORMAT_REJECTED"),
                                  (b"user interaction is not allowed", "USER_INTERACTION_REQUIRED"),
                                  (b"specified item could not be found", "KEYCHAIN_ITEM_NOT_FOUND"),
                                  (b"password you entered is not correct", "KEYCHAIN_PASSWORD_REJECTED")):
                if marker in diagnostic:
                    reason = ": " + label
                    break
        elif command == "openssl":
            if len(args) > 1 and args[1] in {"pkcs12", "x509"}:
                command += " " + args[1]
            diagnostic = (result.stderr or b"").lower()
            if b"mac verify error" in diagnostic or b"mac verification failed" in diagnostic:
                reason = ": PKCS12_PASSWORD_REJECTED"
            elif b"unsupported" in diagnostic or b"unknown pbe algorithm" in diagnostic:
                reason = ": PKCS12_UNSUPPORTED_ENCRYPTION"
        raise RuntimeError(f"{command} failed (exit {result.returncode}){reason}")
    return result.stdout if capture else None


def private_file(path, data):
    with path.open("xb") as stream:
        path.chmod(0o600)
        stream.write(data)


def run_pkcs12(args, **kwargs):
    # OpenSSL 3 needs its legacy provider for older RC2 PKCS12 envelopes.
    # Retry only an unsupported-algorithm error; retain normal MAC validation.
    command = ["openssl", "pkcs12", *args]
    try:
        return run(command, **kwargs)
    except RuntimeError as error:
        if "PKCS12_UNSUPPORTED_ENCRYPTION" not in str(error):
            raise
        return run([*command, "-legacy"], **kwargs)


def verify_p12_password(env):
    require(("IOS_DISTRIBUTION_P12_BASE64", "IOS_DISTRIBUTION_P12_PASSWORD"), env)
    with tempfile.TemporaryDirectory(prefix="youngmin-p12-check-", dir=env.get("RUNNER_TEMP")) as directory:
        source = Path(directory) / "distribution.p12"
        private_file(source, decode_secret(env["IOS_DISTRIBUTION_P12_BASE64"], "IOS_DISTRIBUTION_P12_BASE64"))
        child_env = dict(os.environ, YOUNGMIN_P12_VERIFY_PASSWORD=env["IOS_DISTRIBUTION_P12_PASSWORD"])
        try:
            run_pkcs12(["-in", source, "-passin", "env:YOUNGMIN_P12_VERIFY_PASSWORD", "-noout"],
                       capture=True, env=child_env)
        except RuntimeError as error:
            classification = next((label for label in ("PKCS12_PASSWORD_REJECTED", "PKCS12_UNSUPPORTED_ENCRYPTION")
                                   if label in str(error)), "PKCS12_INVALID_INPUT")
            raise ValueError("P12 verification failed: " + classification
                             + "; verify the original P12 and IOS_DISTRIBUTION_P12_PASSWORD") from None


def compatible_p12(source, temp, password):
    # Verify the original MAC/password; never use -nomacver. Only encrypted
    # PEM/P12 leaves OpenSSL, and no password appears in subprocess arguments.
    working_password = secrets.token_urlsafe(32)
    child_env = dict(os.environ, YOUNGMIN_P12_INPUT_PASSWORD=password,
                     YOUNGMIN_P12_WORK_PASSWORD=working_password)
    try:
        encrypted_pem = run_pkcs12(["-in", source,
                                    "-passin", "env:YOUNGMIN_P12_INPUT_PASSWORD", "-aes256",
                                    "-passout", "env:YOUNGMIN_P12_WORK_PASSWORD"], capture=True, env=child_env)
    except RuntimeError:
        raise ValueError("OpenSSL could not decrypt the P12; verify the original file and "
                         "IOS_DISTRIBUTION_P12_PASSWORD") from None
    protected_pem = temp / "keychain-import-encrypted.pem"
    private_file(protected_pem, encrypted_pem)
    temporary_password = secrets.token_urlsafe(32)
    child_env.pop("YOUNGMIN_P12_INPUT_PASSWORD")
    child_env["YOUNGMIN_P12_OUTPUT_PASSWORD"] = temporary_password
    try:
        encrypted = run(["openssl", "pkcs12", "-export", "-in", protected_pem,
                         "-passin", "env:YOUNGMIN_P12_WORK_PASSWORD", "-keypbe", "PBE-SHA1-3DES",
                         "-certpbe", "PBE-SHA1-3DES", "-macalg", "sha1",
                         "-passout", "env:YOUNGMIN_P12_OUTPUT_PASSWORD"], capture=True, env=child_env)
    finally:
        protected_pem.unlink(missing_ok=True)
    path = temp / "keychain-compatible.p12"
    private_file(path, encrypted)
    return path, temporary_password


def read_profile(path):
    return plistlib.loads(run(["security", "cms", "-D", "-i", path], capture=True))


def validate_profile(profile, bundle, team, certificate, now=None, kind="ad-hoc"):
    now = now or dt.datetime.now(dt.timezone.utc)
    entitlements = profile.get("Entitlements", {})
    expiry = profile.get("ExpirationDate")
    if not isinstance(expiry, dt.datetime):
        raise ValueError(bundle + ": profile has no expiration date")
    if expiry.replace(tzinfo=dt.timezone.utc) <= now:
        raise ValueError(bundle + ": profile has expired")
    if (profile.get("TeamIdentifier") != [team]
            or entitlements.get("com.apple.developer.team-identifier") != team):
        raise ValueError(bundle + ": profile Team ID does not match IOS_TEAM_ID")
    prefixes = profile.get("ApplicationIdentifierPrefix", [])
    if entitlements.get("application-identifier") not in [p + "." + bundle for p in prefixes]:
        raise ValueError(bundle + ": profile must match the explicit Bundle ID")
    # Ad Hoc profiles list devices; App Store Connect profiles must not (and neither may be debug/Enterprise).
    if (entitlements.get("get-task-allow") is not False
            or profile.get("ProvisionsAllDevices")
            or bool(profile.get("ProvisionedDevices")) != (kind == "ad-hoc")):
        expected = ("an Ad Hoc distribution profile with registered devices" if kind == "ad-hoc"
                    else "an App Store Connect distribution profile without a device list")
        raise ValueError(bundle + ": " + expected + " is required")
    if APP_GROUP not in entitlements.get("com.apple.security.application-groups", []):
        raise ValueError(bundle + ": profile must enable " + APP_GROUP)
    if certificate not in profile.get("DeveloperCertificates", []):
        raise ValueError(bundle + ": profile does not include the supplied distribution certificate")
    if not re.fullmatch(r"[A-Fa-f0-9-]{36}", profile.get("UUID", "")):
        raise ValueError(bundle + ": invalid provisioning profile UUID")
    if not profile.get("Name") or "\n" in profile["Name"]:
        raise ValueError(bundle + ": invalid provisioning profile name")


def validate_profile_set(profiles, team, certificate, kind="ad-hoc"):
    for bundle, _, _ in BUNDLES:
        validate_profile(profiles[bundle], bundle, team, certificate, kind=kind)
    if kind == "ad-hoc":
        device_sets = [set(p["ProvisionedDevices"]) for p in profiles.values()]
        if any(devices != device_sets[0] for devices in device_sets):
            raise ValueError("App, widget and share profiles must contain the same registered devices")
    if len({p["Name"] for p in profiles.values()}) != len(TARGETS):
        raise ValueError("Use distinct names for the app, widget and share profiles")


def export_options(team, identity, profiles, kind="ad-hoc"):
    options = {"method": DISTRIBUTIONS[kind]["method"], "destination": "export", "teamID": team,
               "signingStyle": "manual", "signingCertificate": identity,
               "provisioningProfiles": {b: p["Name"] for b, p in profiles.items()},
               "stripSwiftSymbols": True, "manageAppVersionAndBuildNumber": False}
    if kind == "app-store":
        # The upload is a separate step (altool); symbols travel with the IPA for crash reports.
        options["uploadSymbols"] = True
    return options


def verify_privacy_manifests(app):
    for relative, source in PRIVACY_MANIFESTS.items():
        shipped = app / relative / "PrivacyInfo.xcprivacy"
        if not shipped.is_file():
            raise ValueError(relative + ": privacy manifest is missing from the exported bundle")
        if plistlib.loads(shipped.read_bytes()) != plistlib.loads((ROOT / source).read_bytes()):
            raise ValueError(relative + ": exported privacy manifest differs from " + source)


def verify_export(ipa, temp, team, certificate, kind="ad-hoc", build=None):
    unpacked = temp / "verified-ipa"
    run(["ditto", "-x", "-k", ipa, unpacked])
    app = unpacked / "Payload/Youngmin.app"
    run(["codesign", "--verify", "--deep", "--strict", app])
    # The removed JavaScriptCore SPI must not come back through the final linked binary.
    run([sys.executable, "-I", ROOT / ".github/scripts/check-ios-private-apis.py", app / "Youngmin"], capture=True)
    bundles = [app] + sorted((app / "PlugIns").glob("*.appex"))
    profiles = {}
    versions = set()
    info = None
    for bundle in bundles:
        current_info = plistlib.loads((bundle / "Info.plist").read_bytes())
        bundle_id = current_info["CFBundleIdentifier"]
        if bundle_id in profiles:
            raise ValueError("Duplicate exported bundle: " + bundle_id)
        profiles[bundle_id] = read_profile(bundle / "embedded.mobileprovision")
        signed = plistlib.loads(run(["codesign", "-d", "--entitlements", ":-", bundle], capture=True))
        if (signed.get("get-task-allow", False) is not False
                or APP_GROUP not in signed.get("com.apple.security.application-groups", [])):
            raise ValueError(bundle_id + ": exported signature has incorrect entitlements")
        if signed.get("application-identifier") != profiles[bundle_id]["Entitlements"]["application-identifier"]:
            raise ValueError(bundle_id + ": exported signature does not match its profile")
        versions.add((current_info["CFBundleShortVersionString"], current_info["CFBundleVersion"]))
        if bundle == app:
            info = current_info
    if set(profiles) != {t[0] for t in BUNDLES}:
        raise ValueError("Export must contain the app, widget and share extension")
    verify_privacy_manifests(app)
    validate_profile_set(profiles, team, certificate, kind)
    if len(versions) != 1:
        raise ValueError("Exported app and extension versions do not match")
    for key, expected in (("YoungminAuthEngine", "codex-in-process"), ("YoungminCodeModeRuntime", "quickjs"),
                          ("YoungminCodexSourceRevision", CODEX_REVISION)):
        if info.get(key) != expected:
            raise ValueError("Exported app metadata " + key + " is missing or does not match " + expected)
    if build is not None and info.get("CFBundleVersion") != build:
        raise ValueError("Exported build number does not match the requested build")
    if info.get("ITSAppUsesNonExemptEncryption") is not False:
        raise ValueError("ITSAppUsesNonExemptEncryption must be NO in the app Info.plist")

    return info


def build(env, kind="ad-hoc", *, verify_only=False):
    check_config(env, kind=kind)
    distribution = DISTRIBUTIONS[kind]
    kind_targets = targets(kind)
    number = build_number(env, kind)
    team = env["IOS_TEAM_ID"]
    if not verify_only:
        generated = ROOT / "ios/NativeCodex/Generated"
        if not (generated / "iphoneos/arm64/libyoungmin_codex_bridge.a").is_file():
            raise ValueError("Build the native Codex bridge first")
        if not (generated / "Codex-LICENSE.txt").is_file():
            raise ValueError("Native Codex license is missing")
        # Match ios-device-ipa.sh: Rust reports the frameworks required by this build.
        native_system_libs = (generated / "iphoneos/arm64/native-static-libs.txt").read_text().strip()
        if not native_system_libs:
            raise ValueError("Native Codex system library flags are missing")
        OUTPUT.mkdir(exist_ok=True)
        # A fresh output directory prevents a failed rebuild from publishing an old IPA.
        if any(OUTPUT.iterdir()):
            raise ValueError("ios-distribution-output must be empty before building")
    with tempfile.TemporaryDirectory(prefix="youngmin-signing-", dir=env.get("RUNNER_TEMP")) as directory:
        temp = Path(directory)
        keychain = temp / "distribution.keychain-db"
        original_keychains = shlex.split(run(["security", "list-keychains", "-d", "user"], capture=True).decode())
        installed = []
        licenses = ROOT / "ios/Youngmin/NativeCodexLicenses"
        created_licenses = False
        try:
            p12 = temp / "distribution.p12"
            private_file(p12, decode_secret(env["IOS_DISTRIBUTION_P12_BASE64"], "IOS_DISTRIBUTION_P12_BASE64"))
            password = secrets.token_urlsafe(32)
            run(["security", "create-keychain", "-p", password, keychain], capture=True)
            run(["security", "set-keychain-settings", "-lut", "21600", keychain], capture=True)
            run(["security", "unlock-keychain", "-p", password, keychain], capture=True)
            try:
                run(["security", "import", p12, "-P", env["IOS_DISTRIBUTION_P12_PASSWORD"], "-k", keychain,
                     "-T", "/usr/bin/codesign", "-T", "/usr/bin/security"], capture=True)
            except RuntimeError as error:
                if "PKCS12_PASSWORD_OR_FORMAT_REJECTED" not in str(error):
                    raise
                compatible, compatible_password = compatible_p12(p12, temp, env["IOS_DISTRIBUTION_P12_PASSWORD"])
                run(["security", "import", compatible, "-P", compatible_password, "-k", keychain,
                     "-T", "/usr/bin/codesign", "-T", "/usr/bin/security"], capture=True)
                print("Imported the verified P12 through a temporary keychain-compatible wrapper.")
            run(["security", "set-key-partition-list", "-S", "apple-tool:,apple:,codesign:",
                 "-s", "-k", password, keychain], capture=True)
            identities = run(["security", "find-identity", "-v", "-p", "codesigning", keychain], capture=True).decode()
            matches = re.findall(r'([A-F0-9]{40}) "Apple Distribution:[^"\n]+"', identities)
            if len(matches) != 1:
                raise ValueError("P12 must contain one valid Apple Distribution identity and its private key")
            identity = matches[0]
            certificate = run(["security", "find-certificate", "-c", "Apple Distribution:", "-p", keychain], capture=True)
            certificate = run(["openssl", "x509", "-outform", "DER"], data=certificate, capture=True)
            if hashlib.sha1(certificate).hexdigest().upper() != identity:
                raise ValueError("Distribution certificate does not match the signing identity")
            profiles, profile_files = {}, {}
            for bundle, secret, _ in kind_targets:
                path = temp / (secret + ".mobileprovision")
                private_file(path, decode_secret(env[secret], secret))
                profiles[bundle], profile_files[bundle] = read_profile(path), path
            validate_profile_set(profiles, team, certificate, kind)
            if verify_only:
                print("Validated the distribution identity and all three " + distribution["label"] + " profiles.")
                return
            for bundle, profile in profiles.items():
                # Support both current Xcode and the legacy lookup directory.
                for folder in (Path.home() / "Library/Developer/Xcode/UserData/Provisioning Profiles",
                               Path.home() / "Library/MobileDevice/Provisioning Profiles"):
                    folder.mkdir(parents=True, exist_ok=True)
                    dest = folder / (profile["UUID"] + ".mobileprovision")
                    if dest.exists():
                        raise ValueError("A profile with this UUID is already installed; use a fresh runner")
                    private_file(dest, profile_files[bundle].read_bytes())
                    installed.append(dest)
            run(["security", "list-keychains", "-d", "user", "-s", keychain, *original_keychains], capture=True)
            # Xcode's synchronized app group copies these text resources before signing.
            licenses.mkdir()
            created_licenses = True
            for license_file in list(generated.glob("*LICENSE*")) + list(generated.glob("*NOTICE*")):
                shutil.copy2(license_file, licenses / license_file.name)
            archive = temp / "Youngmin.xcarchive"
            run(["xcodebuild", "archive", "-project", ROOT / "ios/Youngmin.xcodeproj", "-scheme", "Youngmin",
                 "-configuration", "Release", "-sdk", "iphoneos", "-destination", "generic/platform=iOS",
                 "-archivePath", archive, "-derivedDataPath", temp / "DerivedData",
                 "ARCHS=arm64", "ONLY_ACTIVE_ARCH=NO", "CODE_SIGN_STYLE=Manual",
                 "DEVELOPMENT_TEAM=" + team, "CODE_SIGN_IDENTITY=" + identity,
                 "OTHER_CODE_SIGN_FLAGS=--keychain " + str(keychain), "CURRENT_PROJECT_VERSION=" + number,
                 "YOUNGMIN_NATIVE_CONDITIONS=YOUNGMIN_NATIVE_CODEX",
                 "YOUNGMIN_NATIVE_BRIDGING_HEADER=$(PROJECT_DIR)/NativeCodex/include/YoungminCodex.h",
                 "YOUNGMIN_NATIVE_LIBRARY_PATH=$(PROJECT_DIR)/NativeCodex/Generated/$(PLATFORM_NAME)/$(CURRENT_ARCH)",
                 "YOUNGMIN_NATIVE_LDFLAGS=-lyoungmin_codex_bridge " + native_system_libs,
                 "INFOPLIST_KEY_YoungminAuthEngine=codex-in-process",
                 "INFOPLIST_KEY_YoungminCodeModeRuntime=quickjs",
                 "INFOPLIST_KEY_YoungminCodexSourceRevision=" + CODEX_REVISION,
                 *[variable + "=" + profiles[bundle]["Name"] for bundle, _, variable in kind_targets]])
            options = temp / "ExportOptions.plist"
            options.write_bytes(plistlib.dumps(export_options(team, identity, profiles, kind)))
            exported = temp / "export"
            run(["xcodebuild", "-exportArchive", "-archivePath", archive,
                 "-exportOptionsPlist", options, "-exportPath", exported])
            ipas = list(exported.glob("*.ipa"))
            if len(ipas) != 1:
                raise ValueError("Expected exactly one exported IPA")
            info = verify_export(ipas[0], temp, team, certificate, kind, number)
            ipa = OUTPUT / distribution["ipa"]
            shutil.copy2(ipas[0], ipa)
            digest = hashlib.sha256(ipa.read_bytes()).hexdigest()
            (OUTPUT / (ipa.name + ".sha256")).write_text(digest + "  " + ipa.name + "\n")
            shutil.make_archive(str(OUTPUT / "Youngmin-dSYMs"), "zip", archive / "dSYMs")
            metadata = {"configuration": "Release", "distribution": kind, "native_codex": True,
                        "version": info["CFBundleShortVersionString"], "build": info["CFBundleVersion"],
                        "commit": env.get("GITHUB_SHA", ""), "sha256": digest,
                        "bundle_id": info["CFBundleIdentifier"], "bundles": sorted(profiles),
                        "profile_expiration": min(p["ExpirationDate"] for p in profiles.values()).isoformat() + "Z"}
            (OUTPUT / "build.json").write_text(json.dumps(metadata, indent=2) + "\n")
            print("Verified Release " + distribution["label"] + " IPA with native Codex, widgets and share extension.")
        finally:
            # This also runs on failed import, validation, archive or export.
            for path in installed:
                path.unlink(missing_ok=True)
            if created_licenses:
                shutil.rmtree(licenses)
            subprocess.run(["security", "list-keychains", "-d", "user", "-s", *original_keychains],
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            subprocess.run(["security", "delete-keychain", str(keychain)],
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def publish(env):
    # A separate command/step keeps Google credentials out of the Apple build.
    firebase_config(env)
    ipa = OUTPUT / "Youngmin-AdHoc.ipa"
    metadata = json.loads((OUTPUT / "build.json").read_text())
    if (metadata.get("configuration") != "Release" or metadata.get("distribution") != "ad-hoc"
            or metadata.get("sha256") != hashlib.sha256(ipa.read_bytes()).hexdigest()):
        raise ValueError("Only the verified Release Ad Hoc IPA can be published")
    with tempfile.TemporaryDirectory(prefix="youngmin-firebase-", dir=env.get("RUNNER_TEMP")) as directory:
        temp = Path(directory)
        credentials = temp / "service-account.json"
        private_file(credentials, env["FIREBASE_SERVICE_ACCOUNT_JSON"].encode())
        notes = temp / "release-notes.txt"
        notes.write_text(env.get("RELEASE_NOTES", "지인 테스트") + "\n\n"
                         + f"Version {metadata['version']} ({metadata['build']})\nCommit {metadata['commit']}\n")
        child_env = dict(os.environ, GOOGLE_APPLICATION_CREDENTIALS=str(credentials),
                         FIREBASE_CLI_DISABLE_UPDATE_CHECK="true")
        child_env.pop("FIREBASE_SERVICE_ACCOUNT_JSON", None)
        child_env.pop("FIREBASE_TOKEN", None)
        # The CLI prints one-hour binary download URLs; only report its tester URL.
        raw = run(["firebase", "appdistribution:distribute", ipa, "--app", env["FIREBASE_APP_ID"],
                   "--groups", env["FIREBASE_GROUPS"], "--release-notes-file", notes,
                   "--non-interactive"], capture=True, env=child_env)
        # The CLI command returns no JSON release object; it logs the tester link.
        links = re.findall(r'https://appdistribution\.firebase\.google\.com/[^\s\x1b<>"\)]+', raw.decode())
        url = links[0] if links else "https://appdistribution.firebase.google.com/"
        print("Firebase tester link: " + url)
        if env.get("GITHUB_STEP_SUMMARY"):
            with Path(env["GITHUB_STEP_SUMMARY"]).open("a") as summary:
                summary.write("\n[Firebase에서 설치하기](" + url + ")\n")


def upload(env):
    # A separate command/step keeps the App Store Connect key out of the signing/build step.
    asc_config(env)
    ipa = OUTPUT / DISTRIBUTIONS["app-store"]["ipa"]
    metadata = json.loads((OUTPUT / "build.json").read_text())
    if (metadata.get("configuration") != "Release" or metadata.get("distribution") != "app-store"
            or metadata.get("bundle_id") != APP_BUNDLE
            or metadata.get("sha256") != hashlib.sha256(ipa.read_bytes()).hexdigest()):
        raise ValueError("Only the verified Release App Store Connect IPA can be uploaded")
    # altool looks up AuthKey_<id>.p8 here; the file exists only for this command.
    keys = Path.home() / ".appstoreconnect/private_keys"
    keys.mkdir(parents=True, exist_ok=True)
    keys.chmod(0o700)
    key_file = keys / ("AuthKey_" + env["ASC_KEY_ID"] + ".p8")
    if key_file.exists():
        raise ValueError("An App Store Connect key with this ID is already installed; use a fresh runner")
    try:
        private_file(key_file, (env["ASC_KEY_P8"].strip() + "\n").encode())
        child_env = {k: v for k, v in os.environ.items() if k != "ASC_KEY_P8"}
        run(["xcrun", "altool", "--upload-package", ipa, "-t", "ios",
             "--apple-id", env["ASC_APP_ID"], "--bundle-id", APP_BUNDLE,
             "--bundle-version", metadata["build"], "--bundle-short-version-string", metadata["version"],
             "--apiKey", env["ASC_KEY_ID"], "--apiIssuer", env["ASC_ISSUER_ID"]], env=child_env)
    finally:
        key_file.unlink(missing_ok=True)
    print(f"Uploaded {metadata['version']} ({metadata['build']}) to App Store Connect; processing continues there.")
    if env.get("GITHUB_STEP_SUMMARY"):
        notes = env.get("WHAT_TO_TEST", "").strip()
        with Path(env["GITHUB_STEP_SUMMARY"]).open("a") as summary:
            summary.write(f"\n### TestFlight 업로드\n- 버전 {metadata['version']} ({metadata['build']}), "
                          f"커밋 `{metadata['commit']}`\n- App Store Connect → TestFlight에서 처리 완료 후 "
                          "수출 규정 확인과 테스트 그룹 배정을 진행한다.\n")
            if notes:
                summary.write("- 이 빌드의 **테스트할 내용(What to Test)**에 붙여 넣을 문구:\n\n```text\n"
                              + notes.replace("```", "` ` `") + "\n```\n")


def main():
    # GitHub cancellation must still run the temporary keychain/profile cleanup.
    signal.signal(signal.SIGTERM, lambda signum, frame: sys.exit(128 + signum))
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("check", "check-p12", "check-signing", "build", "publish", "upload"))
    parser.add_argument("--distribution", choices=tuple(DISTRIBUTIONS), default="ad-hoc",
                        help="Ad Hoc (Firebase) or App Store Connect (TestFlight) signing")
    parser.add_argument("--publish", action="store_true", help="Also check Firebase settings")
    parser.add_argument("--upload", action="store_true", help="Also check App Store Connect upload settings")
    args = parser.parse_args()
    kind = args.distribution
    if (args.command == "publish" or args.publish) and kind != "ad-hoc":
        parser.error("Firebase publishing uses the ad-hoc distribution")
    if (args.command == "upload" or args.upload) and kind != "app-store":
        parser.error("App Store Connect upload uses the app-store distribution")
    try:
        if args.command == "check":
            check_config(os.environ, kind=kind,
                         publish=kind == "ad-hoc" and (args.publish or os.environ.get("PUBLISH_TO_FIREBASE") == "true"),
                         upload=kind == "app-store" and (
                             args.upload or os.environ.get("UPLOAD_TO_APP_STORE_CONNECT") == "true"))
            print("Required distribution settings are present.")
        elif args.command == "check-p12":
            verify_p12_password(os.environ)
            print("Verified the P12 MAC and password with OpenSSL.")
        elif args.command in ("check-signing", "build"):
            build(os.environ, kind, verify_only=args.command == "check-signing")
        elif args.command == "upload":
            upload(os.environ)
        else:
            publish(os.environ)
    except (ValueError, RuntimeError, OSError, KeyError, plistlib.InvalidFileException) as error:
        # Do not include exception data from parsers: it might contain credentials.
        if isinstance(error, (ValueError, RuntimeError)) and not isinstance(error, json.JSONDecodeError):
            message = str(error)
        else:
            message = "Invalid distribution file or failed operation (" + type(error).__name__ + ")"
        print("Distribution failed: " + message, file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
