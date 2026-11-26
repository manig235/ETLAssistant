#!/usr/bin/env python3
"""Manual Ad Hoc archive/export and Firebase upload; never prints credentials."""

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
# Each extension has its own App ID and provisioning profile.
TARGETS = (
    ("kr.snu.etlassistant.ios", "IOS_APP_PROFILE_BASE64", "YOUNGMIN_APP_PROFILE"),
    ("kr.snu.etlassistant.ios.widgets", "IOS_WIDGETS_PROFILE_BASE64", "YOUNGMIN_WIDGETS_PROFILE"),
    ("kr.snu.etlassistant.ios.share", "IOS_SHARE_PROFILE_BASE64", "YOUNGMIN_SHARE_PROFILE"),
)
SIGNING_KEYS = ("IOS_DISTRIBUTION_P12_BASE64", "IOS_DISTRIBUTION_P12_PASSWORD", "IOS_TEAM_ID")
FIREBASE_KEYS = ("FIREBASE_APP_ID", "FIREBASE_GROUPS", "FIREBASE_SERVICE_ACCOUNT_JSON")


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


def check_config(env, publish=False):
    require(SIGNING_KEYS + tuple(t[1] for t in TARGETS), env)
    if not re.fullmatch(r"[A-Z0-9]{10}", env["IOS_TEAM_ID"]):
        raise ValueError("IOS_TEAM_ID must be the 10-character Apple Team ID")
    for key in ("IOS_DISTRIBUTION_P12_BASE64",) + tuple(t[1] for t in TARGETS):
        decode_secret(env[key], key)
    if publish:
        firebase_config(env)


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
        raise RuntimeError(f"{Path(args[0]).name} failed (exit {result.returncode})")
    return result.stdout if capture else None


def private_file(path, data):
    with path.open("xb") as stream:
        path.chmod(0o600)
        stream.write(data)


def read_profile(path):
    return plistlib.loads(run(["security", "cms", "-D", "-i", path], capture=True))


def validate_profile(profile, bundle, team, certificate, now=None):
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
    if (entitlements.get("get-task-allow") is not False
            or profile.get("ProvisionsAllDevices")
            or not profile.get("ProvisionedDevices")):
        raise ValueError(bundle + ": an Ad Hoc distribution profile with registered devices is required")
    if APP_GROUP not in entitlements.get("com.apple.security.application-groups", []):
        raise ValueError(bundle + ": profile must enable " + APP_GROUP)
    if certificate not in profile.get("DeveloperCertificates", []):
        raise ValueError(bundle + ": profile does not include the supplied distribution certificate")
    if not re.fullmatch(r"[A-Fa-f0-9-]{36}", profile.get("UUID", "")):
        raise ValueError(bundle + ": invalid provisioning profile UUID")
    if not profile.get("Name") or "\n" in profile["Name"]:
        raise ValueError(bundle + ": invalid provisioning profile name")


def validate_profile_set(profiles, team, certificate):
    for bundle, _, _ in TARGETS:
        validate_profile(profiles[bundle], bundle, team, certificate)
    device_sets = [set(p["ProvisionedDevices"]) for p in profiles.values()]
    if any(devices != device_sets[0] for devices in device_sets):
        raise ValueError("App, widget and share profiles must contain the same registered devices")
    if len({p["Name"] for p in profiles.values()}) != len(TARGETS):
        raise ValueError("Use distinct names for the app, widget and share profiles")


def export_options(team, identity, profiles):
    return {"method": "release-testing", "destination": "export", "teamID": team,
            "signingStyle": "manual", "signingCertificate": identity,
            "provisioningProfiles": {b: p["Name"] for b, p in profiles.items()},
            "stripSwiftSymbols": True, "manageAppVersionAndBuildNumber": False}


def verify_export(ipa, temp, team, certificate):
    unpacked = temp / "verified-ipa"
    run(["ditto", "-x", "-k", ipa, unpacked])
    app = unpacked / "Payload/Youngmin.app"
    run(["codesign", "--verify", "--deep", "--strict", app])
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
    if set(profiles) != {t[0] for t in TARGETS}:
        raise ValueError("Export must contain the app, widget and share extension")
    validate_profile_set(profiles, team, certificate)
    if (len(versions) != 1 or info.get("YoungminAuthEngine") != "codex-in-process"
            or info.get("YoungminCodeModeRuntime") != "quickjs"):
        raise ValueError("Exported versions or native Codex engine metadata do not match")
    return info


def build(env):
    check_config(env)
    team = env["IOS_TEAM_ID"]
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
            run(["security", "import", p12, "-P", env["IOS_DISTRIBUTION_P12_PASSWORD"], "-k", keychain,
                 "-T", "/usr/bin/codesign", "-T", "/usr/bin/security"], capture=True)
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
            for bundle, secret, _ in TARGETS:
                path = temp / (secret + ".mobileprovision")
                private_file(path, decode_secret(env[secret], secret))
                profiles[bundle], profile_files[bundle] = read_profile(path), path
            validate_profile_set(profiles, team, certificate)
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
            number = env.get("GITHUB_RUN_NUMBER", "1") + "." + env.get("GITHUB_RUN_ATTEMPT", "1")
            if not re.fullmatch(r"[0-9]+\.[0-9]+", number):
                raise ValueError("Invalid workflow build number")
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
                 "INFOPLIST_KEY_YoungminCodexSourceRevision=29eb79e9866b3262a777fdf8195c1b9e63684c91",
                 *[variable + "=" + profiles[bundle]["Name"] for bundle, _, variable in TARGETS]])
            options = temp / "ExportOptions.plist"
            options.write_bytes(plistlib.dumps(export_options(team, identity, profiles)))
            exported = temp / "export"
            run(["xcodebuild", "-exportArchive", "-archivePath", archive,
                 "-exportOptionsPlist", options, "-exportPath", exported])
            ipas = list(exported.glob("*.ipa"))
            if len(ipas) != 1:
                raise ValueError("Expected exactly one exported IPA")
            info = verify_export(ipas[0], temp, team, certificate)
            ipa = OUTPUT / "Youngmin-AdHoc.ipa"
            shutil.copy2(ipas[0], ipa)
            digest = hashlib.sha256(ipa.read_bytes()).hexdigest()
            (OUTPUT / "Youngmin-AdHoc.ipa.sha256").write_text(digest + "  " + ipa.name + "\n")
            shutil.make_archive(str(OUTPUT / "Youngmin-dSYMs"), "zip", archive / "dSYMs")
            metadata = {"configuration": "Release", "distribution": "ad-hoc", "native_codex": True,
                        "version": info["CFBundleShortVersionString"], "build": info["CFBundleVersion"],
                        "commit": env.get("GITHUB_SHA", ""), "sha256": digest,
                        "bundles": sorted(profiles),
                        "profile_expiration": min(p["ExpirationDate"] for p in profiles.values()).isoformat() + "Z"}
            (OUTPUT / "build.json").write_text(json.dumps(metadata, indent=2) + "\n")
            print("Verified Release Ad Hoc IPA with native Codex, widgets and share extension.")
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


def main():
    # GitHub cancellation must still run the temporary keychain/profile cleanup.
    signal.signal(signal.SIGTERM, lambda signum, frame: sys.exit(128 + signum))
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("check", "build", "publish"))
    parser.add_argument("--publish", action="store_true", help="Also check Firebase settings")
    args = parser.parse_args()
    try:
        if args.command == "check":
            check_config(os.environ, args.publish or os.environ.get("PUBLISH_TO_FIREBASE") == "true")
            print("Required distribution settings are present.")
        elif args.command == "build":
            build(os.environ)
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
