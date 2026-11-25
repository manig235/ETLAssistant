#!/usr/bin/env python3
"""Package an unsigned arm64 iOS app for local re-signing on Windows."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import plistlib
import shutil
import struct
import tempfile
import zipfile


def validate_app(app):
    with (app / "Info.plist").open("rb") as stream:
        info = plistlib.load(stream)
    if info.get("CFBundleSupportedPlatforms") != ["iPhoneOS"]:
        raise ValueError("Expected an iPhoneOS device build, not an iOS simulator build")
    executable = info["CFBundleExecutable"]
    if Path(executable).name != executable:
        raise ValueError("Invalid app executable name")
    data = (app / executable).read_bytes()
    if len(data) < 32 or struct.unpack_from("<II", data) != (0xFEEDFACF, 0x0100000C):
        raise ValueError("Expected a thin arm64 Mach-O executable")
    count, size = struct.unpack_from("<II", data, 16)
    end = 32 + size
    if end > len(data):
        raise ValueError("Truncated Mach-O load commands")
    offset = 32
    for _ in range(count):
        if offset + 8 > end:
            raise ValueError("Missing Mach-O load command")
        command, length = struct.unpack_from("<II", data, offset)
        if length < 8 or offset + length > end:
            raise ValueError("Invalid Mach-O load command size")
        if command == 0x32:  # LC_BUILD_VERSION: platform 2 is iOS; 7 is iOS Simulator.
            if length < 24 or struct.unpack_from("<I", data, offset + 8)[0] != 2:
                raise ValueError("Executable does not target an actual iOS device")
            return info
        offset += length
    raise ValueError("Missing iOS device platform load command")


def package(app, output):
    info = validate_app(app)
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="youngmin-ipa-") as temp:
        staging = Path(temp)
        staged_app = staging / "Payload" / app.name
        shutil.copytree(app, staged_app, ignore=shutil.ignore_patterns(
            "_CodeSignature", "embedded.mobileprovision", ".DS_Store"
        ))
        # Extensions need additional App IDs and shared App Group signing.
        # The app already handles a missing App Group container with nil checks.
        plugins = staged_app / "PlugIns"
        removed = sorted(path.name for path in plugins.iterdir()) if plugins.exists() else []
        if plugins.exists():
            shutil.rmtree(plugins)
        with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as archive:
            for path in sorted(staged_app.rglob("*")):
                if path.is_file():
                    # ZipFile.write retains UNIX permissions for the executable.
                    archive.write(path, path.relative_to(staging))
        with zipfile.ZipFile(output) as archive:
            if archive.testzip() is not None:
                raise ValueError("IPA ZIP integrity check failed")
    digest = hashlib.sha256(output.read_bytes()).hexdigest()
    output.with_suffix(".ipa.sha256").write_text(f"{digest}  {output.name}\n")
    metadata = {
        "bundle_identifier": info["CFBundleIdentifier"],
        "version": info.get("CFBundleShortVersionString"),
        "build": info.get("CFBundleVersion"),
        "minimum_os": info.get("MinimumOSVersion"),
        "device_family": info.get("UIDeviceFamily"),
        "platform": "iPhoneOS",
        "architecture": "arm64",
        "unsigned": True,
        "removed_extensions": removed,
        "sha256": digest,
        "github_sha": os.environ.get("GITHUB_SHA"),
    }
    output.with_suffix(".json").write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(metadata, ensure_ascii=False, indent=2))
    print(f"Created {output}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--app", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        package(args.app, args.output)
    except (ValueError, KeyError, OSError, plistlib.InvalidFileException) as error:
        parser.exit(1, f"IPA packaging failed: {error}\n")


if __name__ == "__main__":
    main()
