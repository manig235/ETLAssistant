#!/usr/bin/env python3
"""Check iOS privacy manifests (PrivacyInfo.xcprivacy) against the code that ships.

Source mode validates every manifest under ios/ (keys, Apple reason codes).
Bundle mode (--app) also requires a manifest in the app and in every embedded
.appex, and scans each bundle's Mach-O files for required-reason API references
(imported symbols and Objective-C selectors). A referenced category that the
bundle's manifest does not declare fails the check, because App Store Connect
rejects such uploads (ITMS-91053).

Selector names that appear only as C strings (for example Rust objc2 bindings
compiled into a Debug build) are reported as warnings: they may disappear with
dead-code stripping, so verify them against the Release build before upload.

Runs without Xcode (plistlib + a small Mach-O reader); on macOS it also runs
`plutil -lint`.
"""

import argparse
import json
from pathlib import Path
import plistlib
import re
import shutil
import struct
import subprocess
import sys

MANIFEST = "PrivacyInfo.xcprivacy"

# https://developer.apple.com/documentation/bundleresources/describing-use-of-required-reason-api
REASONS = {
    "NSPrivacyAccessedAPICategoryFileTimestamp": {"DDA9.1", "C617.1", "3B52.1", "0A2A.1"},
    "NSPrivacyAccessedAPICategorySystemBootTime": {"35F9.1", "8FFB.1", "3D61.1"},
    "NSPrivacyAccessedAPICategoryDiskSpace": {"85F4.1", "E174.1", "7D9E.1", "B728.1"},
    "NSPrivacyAccessedAPICategoryActiveKeyboards": {"3EC4.1", "54BD.1"},
    "NSPrivacyAccessedAPICategoryUserDefaults": {"CA92.1", "1C8F.1", "C56D.1", "AC6B.1"},
}

# Imported symbols (Mach-O undefined externals) that are required-reason APIs.
SYMBOLS = {
    "NSPrivacyAccessedAPICategoryFileTimestamp": {
        "_stat", "_fstat", "_fstatat", "_lstat", "_getattrlist", "_getattrlistbulk",
        "_fgetattrlist", "_getattrlistat",
        "_NSFileCreationDate", "_NSFileModificationDate",
        "_NSURLContentModificationDateKey", "_NSURLCreationDateKey",
    },
    "NSPrivacyAccessedAPICategorySystemBootTime": {"_mach_absolute_time"},
    "NSPrivacyAccessedAPICategoryDiskSpace": {
        "_statfs", "_statvfs", "_fstatfs", "_fstatvfs",
        "_NSFileSystemFreeSize", "_NSFileSystemSize",
        "_NSURLVolumeAvailableCapacityKey", "_NSURLVolumeAvailableCapacityForImportantUsageKey",
        "_NSURLVolumeAvailableCapacityForOpportunisticUsageKey", "_NSURLVolumeTotalCapacityKey",
    },
    "NSPrivacyAccessedAPICategoryUserDefaults": {"_OBJC_CLASS_$_NSUserDefaults"},
}

# Objective-C selectors that are required-reason APIs. `creationDate` and
# `modificationDate` are also ordinary property names (EventKit, Photos), so
# they never fail the check on their own.
SELECTORS = {
    "fileModificationDate": "NSPrivacyAccessedAPICategoryFileTimestamp",
    "systemUptime": "NSPrivacyAccessedAPICategorySystemBootTime",
    "activeInputModes": "NSPrivacyAccessedAPICategoryActiveKeyboards",
}
AMBIGUOUS_SELECTORS = {
    "creationDate": "NSPrivacyAccessedAPICategoryFileTimestamp",
    "modificationDate": "NSPrivacyAccessedAPICategoryFileTimestamp",
}

DATA_TYPE = re.compile(r"^NSPrivacyCollectedDataType[A-Za-z]+$")
PURPOSE = re.compile(r"^NSPrivacyCollectedDataTypePurpose[A-Za-z]+$")


class CheckError(Exception):
    pass


def lint(path):
    if shutil.which("plutil"):
        result = subprocess.run(["plutil", "-lint", str(path)], capture_output=True, text=True)
        if result.returncode != 0:
            raise CheckError(f"plutil -lint failed for {path}: {result.stdout.strip()} {result.stderr.strip()}")


def load_manifest(path):
    lint(path)
    try:
        with path.open("rb") as stream:
            data = plistlib.load(stream)
    except (plistlib.InvalidFileException, ValueError) as error:
        raise CheckError(f"{path}: not a valid property list ({error})")
    if not isinstance(data, dict):
        raise CheckError(f"{path}: top level must be a dictionary")
    allowed = {"NSPrivacyTracking", "NSPrivacyTrackingDomains", "NSPrivacyCollectedDataTypes",
               "NSPrivacyAccessedAPITypes"}
    unknown = set(data) - allowed
    if unknown:
        raise CheckError(f"{path}: unknown keys {sorted(unknown)}")
    if data.get("NSPrivacyTracking", False) is not False:
        raise CheckError(f"{path}: NSPrivacyTracking must be false or absent")
    if data.get("NSPrivacyTrackingDomains", []) != []:
        raise CheckError(f"{path}: NSPrivacyTrackingDomains must be empty")
    for entry in data.get("NSPrivacyCollectedDataTypes", []):
        if not isinstance(entry, dict) or not DATA_TYPE.match(str(entry.get("NSPrivacyCollectedDataType", ""))):
            raise CheckError(f"{path}: invalid collected data type entry {entry!r}")
        if not isinstance(entry.get("NSPrivacyCollectedDataTypeLinked"), bool):
            raise CheckError(f"{path}: {entry['NSPrivacyCollectedDataType']} needs a boolean Linked value")
        if entry.get("NSPrivacyCollectedDataTypeTracking") is not False:
            raise CheckError(f"{path}: {entry['NSPrivacyCollectedDataType']} must not be used for tracking")
        purposes = entry.get("NSPrivacyCollectedDataTypePurposes")
        if not purposes or not all(PURPOSE.match(str(p)) for p in purposes):
            raise CheckError(f"{path}: {entry['NSPrivacyCollectedDataType']} needs valid purposes")
    declared = {}
    for entry in data.get("NSPrivacyAccessedAPITypes", []):
        category = entry.get("NSPrivacyAccessedAPIType") if isinstance(entry, dict) else None
        if category not in REASONS:
            raise CheckError(f"{path}: unknown required-reason API category {category!r}")
        if category in declared:
            raise CheckError(f"{path}: {category} declared twice")
        reasons = entry.get("NSPrivacyAccessedAPITypeReasons")
        if not reasons or not isinstance(reasons, list):
            raise CheckError(f"{path}: {category} needs at least one reason")
        invalid = set(reasons) - REASONS[category]
        if invalid:
            raise CheckError(f"{path}: reasons {sorted(invalid)} are not valid for {category}")
        declared[category] = sorted(reasons)
    return declared


# --- Mach-O reading -------------------------------------------------------

def thin_slices(data):
    """Yield the 64-bit little-endian Mach-O slices of a thin or universal file."""
    if len(data) < 8:
        return
    magic = struct.unpack_from(">I", data)[0]
    if magic in (0xCAFEBABE, 0xCAFEBABF):
        count = struct.unpack_from(">I", data, 4)[0]
        wide = magic == 0xCAFEBABF
        for index in range(count):
            if wide:
                _, _, offset, size, _, _ = struct.unpack_from(">iiQQII", data, 8 + index * 32)
            else:
                _, _, offset, size, _ = struct.unpack_from(">iiIII", data, 8 + index * 20)
            yield data[offset:offset + size]
    elif struct.unpack_from("<I", data)[0] == 0xFEEDFACF:
        yield data


def is_macho(path):
    try:
        with path.open("rb") as stream:
            head = stream.read(4)
    except OSError:
        return False
    return head in (b"\xcf\xfa\xed\xfe", b"\xca\xfe\xba\xbe", b"\xca\xfe\xba\xbf")


def c_strings(blob):
    return {s.decode("utf-8", "replace") for s in blob.split(b"\0") if s}


def scan_slice(data):
    """Return (undefined symbols, objc selector names, other C strings) of one slice."""
    ncmds = struct.unpack_from("<I", data, 16)[0]
    offset = 32
    imports, methnames, cstrings = set(), set(), set()
    for _ in range(ncmds):
        command, length = struct.unpack_from("<II", data, offset)
        if command == 0x2:  # LC_SYMTAB
            symoff, nsyms, stroff, _ = struct.unpack_from("<4I", data, offset + 8)
            for index in range(nsyms):
                strx, ntype, _, _, _ = struct.unpack_from("<IBBHQ", data, symoff + index * 16)
                if ntype & 0xE0 or not ntype & 0x01 or ntype & 0x0E:
                    continue  # debug entry, local, or defined here
                end = data.index(b"\0", stroff + strx)
                imports.add(data[stroff + strx:end].decode("utf-8", "replace"))
        elif command == 0x19:  # LC_SEGMENT_64
            nsects = struct.unpack_from("<I", data, offset + 64)[0]
            section = offset + 72
            for _ in range(nsects):
                name = data[section:section + 16].rstrip(b"\0")
                _, size, fileoff = struct.unpack_from("<QQI", data, section + 32)
                if name == b"__objc_methname":
                    methnames |= c_strings(data[fileoff:fileoff + size])
                elif name == b"__cstring":
                    cstrings |= c_strings(data[fileoff:fileoff + size])
                section += 80
        offset += length
    return imports, methnames, cstrings


def normalize(symbol):
    # Variant suffixes such as `_stat$INODE64` name the same C function.
    if symbol.startswith("_OBJC_"):
        return symbol
    return symbol.split("$", 1)[0]


def scan_bundle(bundle):
    """Required-reason evidence for one .app/.appex, excluding nested bundles."""
    strong, weak = {}, {}
    files = []
    for path in sorted(bundle.rglob("*")):
        relative = path.relative_to(bundle)
        if any(part.endswith((".appex", ".app", ".framework", ".dSYM")) for part in relative.parts[:-1]):
            continue  # nested bundles carry their own manifest
        if path.is_file() and not path.is_symlink() and is_macho(path):
            files.append(path)
    for path in files:
        for data in thin_slices(path.read_bytes()):
            imports, methnames, cstrings = scan_slice(data)
            names = {normalize(s) for s in imports}
            for category, symbols in SYMBOLS.items():
                for symbol in sorted(names & symbols):
                    strong.setdefault(category, set()).add(f"{path.name}: import {symbol}")
            for selector, category in SELECTORS.items():
                if selector in methnames:
                    strong.setdefault(category, set()).add(f"{path.name}: selector {selector}")
                elif selector in cstrings:
                    weak.setdefault(category, set()).add(f"{path.name}: C string {selector}")
            for selector, category in AMBIGUOUS_SELECTORS.items():
                if selector in methnames or selector in cstrings:
                    weak.setdefault(category, set()).add(f"{path.name}: name {selector}")
    return files, strong, weak


def check_bundle(bundle):
    manifest = bundle / MANIFEST
    if not manifest.is_file():
        raise CheckError(f"{bundle.name} is missing {MANIFEST}")
    declared = load_manifest(manifest)
    files, strong, weak = scan_bundle(bundle)
    if not files:
        raise CheckError(f"{bundle.name}: no Mach-O executable found to scan")
    missing = sorted(set(strong) - set(declared))
    warnings = sorted(c for c in weak if c not in declared and c not in strong)
    report = {
        "bundle": bundle.name,
        "binaries": [str(f.relative_to(bundle)) for f in files],
        "declared": declared,
        "detected": {c: sorted(v) for c, v in sorted(strong.items())},
        "possible": {c: sorted(v) for c, v in sorted(weak.items())},
        "undeclared": missing,
        "warnings": warnings,
        "declared_without_detection": sorted(set(declared) - set(strong)),
    }
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--source", type=Path, help="Directory whose PrivacyInfo.xcprivacy files to validate (e.g. ios)")
    parser.add_argument("--app", type=Path, action="append", default=[], help="Built .app to check (with its PlugIns/*.appex)")
    parser.add_argument("--expect-extensions", nargs="*", default=[], help="Extension bundle names that must be embedded")
    parser.add_argument("--report", type=Path, help="Write the JSON report here")
    args = parser.parse_args()
    if not args.source and not args.app:
        parser.error("give --source and/or --app")
    reports, failures = [], []
    try:
        if args.source:
            manifests = sorted(p for p in args.source.rglob(MANIFEST) if "Generated" not in p.parts)
            if not manifests:
                raise CheckError(f"no {MANIFEST} under {args.source}")
            for path in manifests:
                declared = load_manifest(path)
                print(f"ok  {path}: {json.dumps(declared, sort_keys=True)}")
        for app in args.app:
            bundles = [app] + sorted((app / "PlugIns").glob("*.appex"))
            names = {b.name for b in bundles}
            for expected in args.expect_extensions:
                if expected not in names:
                    failures.append(f"{app.name} does not embed {expected}")
            for bundle in bundles:
                try:
                    report = check_bundle(bundle)
                except CheckError as error:
                    failures.append(str(error))
                    continue
                reports.append(report)
                print(f"\n== {bundle.name} ({', '.join(report['binaries'])})")
                for category, reasons in report["declared"].items():
                    evidence = report["detected"].get(category) or report["possible"].get(category) or ["(declared; no symbol evidence in this build)"]
                    print(f"  {category} {reasons}")
                    for line in evidence[:6]:
                        print(f"      {line}")
                for category in report["undeclared"]:
                    failures.append(f"{bundle.name} uses {category} but its {MANIFEST} does not declare it: "
                                    + "; ".join(report["detected"][category][:6]))
                for category in report["warnings"]:
                    print(f"  WARNING possible {category} (verify in the Release build): "
                          + "; ".join(report["possible"][category][:6]))
    except CheckError as error:
        failures.append(str(error))
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps({"bundles": reports, "failures": failures}, indent=2, ensure_ascii=False) + "\n")
    if failures:
        sys.stdout.flush()
        for failure in failures:
            print(f"error: {failure}", file=sys.stderr)
        return 1
    print("\nPrivacy manifests OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
