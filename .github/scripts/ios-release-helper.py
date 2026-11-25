#!/usr/bin/env python3
"""Release-archive helpers that need no Xcode (except the optional nm checks).

Subcommands:
  config-check        committed Codex settings agree with the pinned engine build
  native-check        the generated engine library exists and matches the config
  build-number        a CFBundleVersion that is unique per upload
  app-only-project    copy of the Xcode project without the two extensions
  profile-info        decode and validate a provisioning profile
  export-options      write ExportOptions.plist for xcodebuild -exportArchive
  verify-archive      the archived app really contains the Codex engine
"""
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import plistlib
import re
import shutil
import struct
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
IOS = ROOT / "ios"
NATIVE_CONFIG = IOS / "Config/NativeCodex.xcconfig"
PROJECT_CONFIG = IOS / "Config/Youngmin.xcconfig"
PROBE_SCRIPT = ROOT / ".github/scripts/ios-codex-probe.sh"
LICENSES = IOS / "Youngmin/Resources/Licenses"
GENERATED = IOS / "NativeCodex/Generated"
APP_BUNDLE_ID = "kr.snu.etlassistant.ios"
APP_GROUP = "group.kr.snu.etlassistant.ios"
# Extension target name -> bundle identifier suffix. Keep in sync with the project.
EXTENSIONS = {"YoungminWidgets": ".widgets", "YoungminShare": ".share"}
METHODS = ("app-store-connect", "release-testing")
# Strings from the Rust bridge that survive symbol stripping.
ENGINE_MARKERS = (b"youngmin/ready", b"cli_auth_credentials_store", b"youngmin_ios")
BRIDGE_SYMBOLS = ("ym_codex_start", "ym_codex_send", "ym_codex_poll", "ym_codex_free_string", "ym_codex_stop")


class ReleaseError(Exception):
    pass


def require(condition, message):
    if not condition:
        raise ReleaseError(message)


# ---------------------------------------------------------------- configuration

def read_xcconfig(path):
    values = {}
    for raw in Path(path).read_text().splitlines():
        line = raw.split("//", 1)[0].strip()
        if not line or line.startswith("#"):
            continue
        key, sep, value = line.partition("=")
        require(sep, f"Unparseable line in {path}: {raw}")
        values[key.strip()] = value.strip()
    return values


def probe_value(name):
    match = re.search(rf"^{name}=(\S+)$", PROBE_SCRIPT.read_text(), re.M)
    require(match, f"{name} missing from {PROBE_SCRIPT}")
    return match[1]


def link_units(flags):
    """Split linker flags into comparable units ("-framework X" stays together)."""
    tokens = flags.split()
    units, index = set(), 0
    while index < len(tokens):
        if tokens[index] == "-framework":
            require(index + 1 < len(tokens), "Dangling -framework flag")
            units.add(f"-framework {tokens[index + 1]}")
            index += 2
        else:
            units.add(tokens[index])
            index += 1
    return units


def check_link_flags(required_flags, source):
    configured = link_units(read_xcconfig(NATIVE_CONFIG)["YOUNGMIN_NATIVE_SYSTEM_LIBS"])
    missing = sorted(link_units(required_flags) - configured)
    require(not missing, f"YOUNGMIN_NATIVE_SYSTEM_LIBS in {NATIVE_CONFIG.relative_to(ROOT)} lacks {missing} required by {source}")


def config_check(_args=None):
    native = read_xcconfig(NATIVE_CONFIG)
    project = read_xcconfig(PROJECT_CONFIG)
    revision = probe_value("REVISION")
    require(native.get("YOUNGMIN_CODEX_SOURCE_REVISION") == revision,
            f"YOUNGMIN_CODEX_SOURCE_REVISION ({native.get('YOUNGMIN_CODEX_SOURCE_REVISION')}) "
            f"differs from REVISION in ios-codex-probe.sh ({revision})")
    require(native.get("YOUNGMIN_NATIVE_CONDITIONS") == "YOUNGMIN_NATIVE_CODEX", "Native compilation condition missing")
    require(native.get("YOUNGMIN_AUTH_ENGINE") == "codex-in-process", "Auth engine marker missing")
    require(native.get("YOUNGMIN_CODE_MODE_RUNTIME") == "quickjs", "Code Mode runtime marker missing")
    require(native.get("YOUNGMIN_NATIVE_LDFLAGS", "").startswith("-lyoungmin_codex_bridge "), "Bridge library is not linked")
    check_link_flags((IOS / "NativeCodex/native-static-libs.txt").read_text(), "ios/NativeCodex/native-static-libs.txt")
    require(re.fullmatch(r"\d+\.\d+\.\d+", project.get("MARKETING_VERSION", "")), "MARKETING_VERSION must be X.Y.Z")
    require(re.fullmatch(r"\d+", project.get("CURRENT_PROJECT_VERSION", "")), "CURRENT_PROJECT_VERSION must be an integer")
    for name in ("Codex-LICENSE.txt", "Codex-NOTICE.txt", "MXC-LICENSE.txt", "QuickJS-LICENSE.txt"):
        require((LICENSES / name).is_file(), f"Missing bundled license {name}")
    require((LICENSES / "QuickJS-LICENSE.txt").read_bytes() == (IOS / "NativeCodex/javascript/vendor/quickjs/LICENSE").read_bytes(),
            "Bundled QuickJS license differs from the vendored source")
    pbxproj = (IOS / "Youngmin.xcodeproj/project.pbxproj").read_text()
    require("baseConfigurationReference = D10000000000000000000002 /* NativeCodex.xcconfig */;" in pbxproj,
            "The app Release configuration no longer uses NativeCodex.xcconfig")
    require("CURRENT_PROJECT_VERSION =" not in pbxproj and "MARKETING_VERSION =" not in pbxproj,
            "Versions must live only in ios/Config/Youngmin.xcconfig")
    print(f"PASS: committed Release config embeds Codex {revision[:12]}; version {project['MARKETING_VERSION']} ({project['CURRENT_PROJECT_VERSION']})")


def native_check(args):
    config_check()
    folder = GENERATED / args.platform / args.arch
    library = folder / "libyoungmin_codex_bridge.a"
    require(library.is_file() and library.stat().st_size > 0,
            f"{library.relative_to(ROOT)} is missing. Release builds require the embedded engine; build it with "
            "CODEX_PROBE_COMPONENT=bridge CODEX_PROBE_OPERATION=build bash .github/scripts/ios-codex-probe.sh")
    reported = folder / "native-static-libs.txt"
    require(reported.is_file(), f"{reported.relative_to(ROOT)} is missing")
    check_link_flags(reported.read_text(), str(reported.relative_to(ROOT)))
    generated = sorted(p for p in GENERATED.glob("*") if p.is_file() and ("LICENSE" in p.name or "NOTICE" in p.name))
    require(generated, "No generated license files next to the engine library")
    for path in generated:
        bundled = LICENSES / path.name
        require(bundled.is_file() and bundled.read_bytes() == path.read_bytes(),
                f"{bundled.relative_to(ROOT)} must equal {path.relative_to(ROOT)} from the pinned source")
    print(f"PASS: {library.relative_to(ROOT)} present; link flags and licenses match")


def build_number(args):
    base = read_xcconfig(PROJECT_CONFIG)["CURRENT_PROJECT_VERSION"]
    value = args.override.strip() if args.override else (f"{base}.{args.run}" if args.run else base)
    require(re.fullmatch(r"[0-9]+(\.[0-9]+){0,2}", value), f"Invalid build number: {value!r}")
    require(int(value.split(".")[0]) >= int(base), f"Build number {value} is below the project build {base}")
    print(value)


def marketing_version(_args):
    print(read_xcconfig(PROJECT_CONFIG)["MARKETING_VERSION"])


# ------------------------------------------------------------- app-only project

APP_TARGET_DEPENDENCIES = """			dependencies = (
				B10000000000000000000052 /* PBXTargetDependency */,
				B10000000000000000000053 /* PBXTargetDependency */,
			);
			fileSystemSynchronizedGroups = (
				A10000000000000000000010 /* Youngmin */,"""
EMBED_PHASE = "\t\t\t\tB10000000000000000000038 /* Embed Foundation Extensions */,\n"


def app_only_project(args):
    """The app target without the widget/share dependencies and embed phase.

    Extensions need their own App IDs, profiles and App Group. Removing them from
    the build (instead of deleting PlugIns afterwards) keeps the signed app's
    seal intact. Every edit must match exactly once, so a project restructure
    fails here loudly instead of silently shipping extensions.
    """
    source, dest = Path(args.source), Path(args.dest)
    require(dest.parent == source.parent, "The copy must sit next to the original project (relative source paths)")
    text = (source / "project.pbxproj").read_text()
    require(text.count(APP_TARGET_DEPENDENCIES) == 1, "App target dependency list changed; update ios-release-helper.py")
    require(text.count(EMBED_PHASE) == 1, "Extension embed phase changed; update ios-release-helper.py")
    text = text.replace(APP_TARGET_DEPENDENCIES, APP_TARGET_DEPENDENCIES.replace(
        "\t\t\t\tB10000000000000000000052 /* PBXTargetDependency */,\n\t\t\t\tB10000000000000000000053 /* PBXTargetDependency */,\n", ""))
    text = text.replace(EMBED_PHASE, "")
    if dest.exists():
        shutil.rmtree(dest)
    (dest / "xcshareddata/xcschemes").mkdir(parents=True)
    (dest / "project.pbxproj").write_text(text)
    scheme = (source / "xcshareddata/xcschemes/Youngmin.xcscheme").read_text()
    old = f"container:{source.name}"
    require(old in scheme, "Shared scheme does not reference the project")
    (dest / "xcshareddata/xcschemes/Youngmin.xcscheme").write_text(scheme.replace(old, f"container:{dest.name}"))
    print(f"Created {dest} (app only: widget and share extensions excluded)")


# --------------------------------------------------------- provisioning profile

def decode_profile(path):
    data = Path(path).read_bytes()
    for command in (["security", "cms", "-D", "-i", str(path)],
                    ["openssl", "smime", "-inform", "der", "-verify", "-noverify", "-in", str(path)]):
        if shutil.which(command[0]) is None:
            continue
        result = subprocess.run(command, capture_output=True)
        if result.returncode == 0 and result.stdout.lstrip().startswith(b"<?xml"):
            return plistlib.loads(result.stdout)
    start, end = data.find(b"<?xml"), data.find(b"</plist>")
    require(start >= 0 and end > start, f"{path} is not a provisioning profile")
    return plistlib.loads(data[start:end + len(b"</plist>")])


def profile_info(path, method, team, bundle_id, app_group=None, certificate_sha1=None, now=None):
    profile = decode_profile(path)
    name = profile.get("Name", "?")
    entitlements = profile.get("Entitlements", {})
    require(team in profile.get("TeamIdentifier", []), f"Profile {name!r} belongs to another team")
    app_id = entitlements.get("application-identifier", "")
    require(app_id in (f"{team}.{bundle_id}", f"{team}.*"),
            f"Profile {name!r} is for {app_id!r}, not {team}.{bundle_id}")
    expires = profile.get("ExpirationDate")
    now = now or datetime.datetime.now(datetime.timezone.utc)
    if expires is not None and expires.tzinfo is None:
        expires = expires.replace(tzinfo=datetime.timezone.utc)
    require(expires is None or expires > now, f"Profile {name!r} expired on {expires}")
    require(not entitlements.get("get-task-allow", False), f"Profile {name!r} is a development profile")
    devices = profile.get("ProvisionedDevices")
    require(not profile.get("ProvisionsAllDevices"), f"Profile {name!r} is an Enterprise profile")
    if method == "app-store-connect":
        require(not devices, f"Profile {name!r} lists devices; use an App Store Connect distribution profile")
    else:
        require(devices, f"Profile {name!r} has no devices; use an Ad Hoc profile for release-testing")
    if app_group:
        groups = entitlements.get("com.apple.security.application-groups", [])
        require(app_group in groups, f"Profile {name!r} lacks the App Group {app_group}")
    if certificate_sha1:
        hashes = {hashlib.sha1(cert).hexdigest().upper() for cert in profile.get("DeveloperCertificates", [])}
        require(certificate_sha1.upper() in hashes, f"Profile {name!r} does not include the signing certificate")
    uuid = profile.get("UUID", "")
    require(re.fullmatch(r"[0-9A-Fa-f-]{36}", uuid), f"Profile {name!r} has no UUID")
    return {"uuid": uuid, "name": name, "bundle_id": bundle_id, "devices": len(devices or [])}


def profile_info_command(args):
    info = profile_info(args.profile, args.method, args.team, args.bundle_id, args.app_group, args.certificate_sha1)
    print(json.dumps(info))


# --------------------------------------------------------------- export options

def export_options(method, team, profiles, certificate):
    require(method in METHODS, f"Unsupported method {method}")
    require(re.fullmatch(r"[A-Z0-9]{10}", team), "IOS_TEAM_ID must be the 10-character Apple team ID")
    require(APP_BUNDLE_ID in profiles, "The app profile is required")
    options = {
        "method": method,
        "signingStyle": "manual",
        "teamID": team,
        "signingCertificate": certificate,
        "provisioningProfiles": dict(sorted(profiles.items())),
        "destination": "export",
        "stripSwiftSymbols": True,
        "manageAppVersionAndBuildNumber": False,
    }
    if method == "app-store-connect":
        options["uploadSymbols"] = True
    else:
        options["thinning"] = "<none>"
        options["compileBitcode"] = False
    return options


def export_options_command(args):
    profiles = {}
    for item in args.profile:
        bundle, sep, uuid = item.partition("=")
        require(sep and uuid, f"Expected bundle=uuid, got {item!r}")
        profiles[bundle] = uuid
    options = export_options(args.method, args.team, profiles, args.certificate)
    Path(args.output).write_bytes(plistlib.dumps(options))
    print(f"Wrote {args.output} ({args.method}, {len(profiles)} profile(s))")


# --------------------------------------------------------------- verify archive

def macho_is_device_arm64(path):
    data = Path(path).read_bytes()
    if len(data) < 32 or struct.unpack_from("<II", data) != (0xFEEDFACF, 0x0100000C):
        return False
    count, size = struct.unpack_from("<II", data, 16)
    offset, end = 32, 32 + size
    for _ in range(count):
        command, length = struct.unpack_from("<II", data, offset)
        if command == 0x32:  # LC_BUILD_VERSION, platform 2 = iOS device
            return struct.unpack_from("<I", data, offset + 8)[0] == 2
        offset += length
        if length < 8 or offset > end:
            break
    return False


def load_plist(path):
    with Path(path).open("rb") as stream:
        return plistlib.load(stream)


def verify_archive(archive, mode, build, marketing, revision=None, check_symbols=True):
    archive = Path(archive)
    require((archive / "Info.plist").is_file(), f"{archive} is not an .xcarchive")
    meta = load_plist(archive / "Info.plist").get("ApplicationProperties", {})
    require(meta.get("ApplicationPath") == "Applications/Youngmin.app",
            "The archive is not an iOS App Archive (did a dependency forget SKIP_INSTALL?)")
    app = archive / "Products/Applications/Youngmin.app"
    info = load_plist(app / "Info.plist")
    revision = revision or probe_value("REVISION")
    expected = {
        "CFBundleIdentifier": APP_BUNDLE_ID,
        "CFBundleShortVersionString": marketing,
        "CFBundleVersion": build,
        "YoungminAuthEngine": "codex-in-process",
        "YoungminCodeModeRuntime": "quickjs",
        "YoungminCodexSourceRevision": revision,
        "ITSAppUsesNonExemptEncryption": False,
        "CFBundleSupportedPlatforms": ["iPhoneOS"],
    }
    for key, value in expected.items():
        require(info.get(key) == value, f"Info.plist {key} is {info.get(key)!r}, expected {value!r}")
    executable = app / info["CFBundleExecutable"]
    require(macho_is_device_arm64(executable), "The app executable is not a thin arm64 iOS device binary")
    binary = executable.read_bytes()
    missing = [m.decode() for m in ENGINE_MARKERS if m not in binary]
    require(not missing, f"The executable lacks embedded Codex bridge data {missing}: the engine was not linked")
    require((app / "PrivacyInfo.xcprivacy").is_file(), "The app privacy manifest is missing")
    for path in sorted(LICENSES.iterdir()):
        bundled = app / path.name
        require(bundled.is_file() and bundled.read_bytes() == path.read_bytes(), f"{path.name} is not bundled in the app")
    plugins = app / "PlugIns"
    found = sorted(p.name for p in plugins.iterdir()) if plugins.is_dir() else []
    if mode == "app-only":
        require(not found, f"App-only archive still embeds {found}")
    else:
        require(found == sorted(f"{name}.appex" for name in EXTENSIONS), f"Full archive embeds {found}")
        for name, suffix in EXTENSIONS.items():
            ext = plugins / f"{name}.appex"
            ext_info = load_plist(ext / "Info.plist")
            require(ext_info.get("CFBundleIdentifier") == APP_BUNDLE_ID + suffix, f"{name} bundle ID mismatch")
            for key in ("CFBundleShortVersionString", "CFBundleVersion"):
                require(ext_info.get(key) == info.get(key), f"{name} {key} differs from the app")
            require((ext / "PrivacyInfo.xcprivacy").is_file(), f"{name} privacy manifest is missing")
    symbols = "skipped"
    dsym = archive / "dSYMs/Youngmin.app.dSYM/Contents/Resources/DWARF/Youngmin"
    if check_symbols and dsym.is_file() and shutil.which("xcrun"):
        table = subprocess.run(["xcrun", "nm", "-gU", str(dsym)], capture_output=True, text=True).stdout
        absent = [s for s in BRIDGE_SYMBOLS if f"_{s}" not in table]
        # Informational: the hard proof is the bridge data above plus a link
        # that resolved the Swift calls into the static library.
        symbols = "all bridge entry points present in dSYM" if not absent else f"dSYM lacks {absent} (informational)"
    print(f"PASS: {archive.name}: Codex {revision[:12]} engine embedded, {mode}, "
          f"{marketing} ({build}), extensions {found or 'none'}, symbols: {symbols}")


def verify_archive_command(args):
    verify_archive(args.archive, args.mode, args.build, args.marketing)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("config-check").set_defaults(func=config_check)
    p = sub.add_parser("native-check")
    p.add_argument("--platform", default="iphoneos")
    p.add_argument("--arch", default="arm64")
    p.set_defaults(func=native_check)
    p = sub.add_parser("build-number")
    p.add_argument("--run", default="")
    p.add_argument("--override", default="")
    p.set_defaults(func=build_number)
    sub.add_parser("marketing-version").set_defaults(func=marketing_version)
    p = sub.add_parser("app-only-project")
    p.add_argument("--source", required=True)
    p.add_argument("--dest", required=True)
    p.set_defaults(func=app_only_project)
    p = sub.add_parser("profile-info")
    p.add_argument("--profile", required=True)
    p.add_argument("--method", choices=METHODS, required=True)
    p.add_argument("--team", required=True)
    p.add_argument("--bundle-id", required=True)
    p.add_argument("--app-group")
    p.add_argument("--certificate-sha1")
    p.set_defaults(func=profile_info_command)
    p = sub.add_parser("export-options")
    p.add_argument("--method", choices=METHODS, required=True)
    p.add_argument("--team", required=True)
    p.add_argument("--certificate", required=True)
    p.add_argument("--profile", action="append", default=[], help="bundle_id=profile_uuid")
    p.add_argument("--output", required=True)
    p.set_defaults(func=export_options_command)
    p = sub.add_parser("verify-archive")
    p.add_argument("--archive", required=True)
    p.add_argument("--mode", choices=("app-only", "full"), required=True)
    p.add_argument("--build", required=True)
    p.add_argument("--marketing", required=True)
    p.set_defaults(func=verify_archive_command)
    args = parser.parse_args(argv)
    try:
        args.func(args)
    except (ReleaseError, OSError, KeyError, plistlib.InvalidFileException) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
