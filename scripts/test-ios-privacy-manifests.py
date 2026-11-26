#!/usr/bin/env python3
"""Privacy manifest checks (.github/scripts/check-privacy-manifests.py), without Xcode."""
import importlib.util
from pathlib import Path
import plistlib
import re
import struct
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("privacy", ROOT / ".github/scripts/check-privacy-manifests.py")
privacy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(privacy)


def macho(imports=(), methnames=(), cstrings=()):
    """A minimal arm64 Mach-O with undefined symbols and two string sections."""
    strtab = b"\0"
    entries = b""
    for name in imports:
        entries += struct.pack("<IBBHQ", len(strtab), 0x01, 0, 0, 0)  # N_UNDF | N_EXT
        strtab += name.encode() + b"\0"
    sections = [(b"__objc_methname", b"\0".join(n.encode() for n in methnames) + b"\0"),
                (b"__cstring", b"\0".join(n.encode() for n in cstrings) + b"\0")]
    segment_size = 72 + 80 * len(sections)
    header_size = 32 + 24 + segment_size
    blob = b""
    section_headers = b""
    for name, data in sections:
        offset = header_size + len(blob)
        section_headers += struct.pack("<16s16sQQIIIIIIII", name, b"__TEXT", 0, len(data), offset, 0, 0, 0, 0, 0, 0, 0)
        blob += data
    symoff = header_size + len(blob)
    stroff = symoff + len(entries)
    header = struct.pack("<8I", 0xFEEDFACF, 0x0100000C, 0, 2, 2, 24 + segment_size, 0, 0)
    symtab = struct.pack("<6I", 0x2, 24, symoff, len(imports), stroff, len(strtab))
    segment = struct.pack("<II16sQQQQiiII", 0x19, segment_size, b"__TEXT", 0, 0, 0, 0, 5, 5, len(sections), 0)
    return header + symtab + segment + section_headers + blob + entries + strtab


class PrivacyManifestTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.app = self.root / "Youngmin.app"
        self.app.mkdir()

    def manifest(self, bundle, categories, collected=()):
        bundle.mkdir(parents=True, exist_ok=True)
        (bundle / privacy.MANIFEST).write_bytes(plistlib.dumps({
            "NSPrivacyTracking": False,
            "NSPrivacyTrackingDomains": [],
            "NSPrivacyCollectedDataTypes": list(collected),
            "NSPrivacyAccessedAPITypes": [
                {"NSPrivacyAccessedAPIType": c, "NSPrivacyAccessedAPITypeReasons": r} for c, r in categories.items()
            ],
        }))

    def test_repository_manifests_are_valid(self):
        manifests = sorted((ROOT / "ios").rglob(privacy.MANIFEST))
        names = {p.parent.name for p in manifests}
        self.assertEqual(names, {"Youngmin", "YoungminWidgets", "YoungminShare"})
        for path in manifests:
            privacy.load_manifest(path)

    def test_detects_imports_and_selectors(self):
        (self.app / "Youngmin").write_bytes(macho(
            imports=["_statfs", "_stat$INODE64", "_OBJC_CLASS_$_NSUserDefaults", "_malloc"],
            methnames=["systemUptime"],
            cstrings=["activeInputModes"],
        ))
        self.manifest(self.app, {"NSPrivacyAccessedAPICategoryUserDefaults": ["CA92.1"]})
        report = privacy.check_bundle(self.app)
        self.assertEqual(report["undeclared"], [
            "NSPrivacyAccessedAPICategoryDiskSpace",
            "NSPrivacyAccessedAPICategoryFileTimestamp",
            "NSPrivacyAccessedAPICategorySystemBootTime",
        ])
        self.assertEqual(report["warnings"], ["NSPrivacyAccessedAPICategoryActiveKeyboards"])

    def test_declared_categories_pass_and_nested_bundles_are_separate(self):
        (self.app / "Youngmin").write_bytes(macho(imports=["_fstat", "_OBJC_CLASS_$_NSUserDefaults"]))
        self.manifest(self.app, {"NSPrivacyAccessedAPICategoryUserDefaults": ["CA92.1"],
                                 "NSPrivacyAccessedAPICategoryFileTimestamp": ["C617.1"]})
        extension = self.app / "PlugIns/YoungminWidgets.appex"
        extension.mkdir(parents=True)
        (extension / "YoungminWidgets").write_bytes(macho(imports=["_mach_absolute_time"]))
        self.assertEqual(privacy.check_bundle(self.app)["undeclared"], [])
        with self.assertRaisesRegex(privacy.CheckError, "missing PrivacyInfo"):
            privacy.check_bundle(extension)

    def test_rejects_reason_from_another_category(self):
        self.manifest(self.app, {"NSPrivacyAccessedAPICategoryUserDefaults": ["C617.1"]})
        with self.assertRaisesRegex(privacy.CheckError, "not valid"):
            privacy.load_manifest(self.app / privacy.MANIFEST)

    def test_rejects_tracking(self):
        self.manifest(self.app, {})
        data = plistlib.loads((self.app / privacy.MANIFEST).read_bytes())
        data["NSPrivacyTracking"] = True
        (self.app / privacy.MANIFEST).write_bytes(plistlib.dumps(data))
        with self.assertRaisesRegex(privacy.CheckError, "NSPrivacyTracking"):
            privacy.load_manifest(self.app / privacy.MANIFEST)


    def collected_entry(self, kind="NSPrivacyCollectedDataTypeOtherUserContent", **overrides):
        entry = {
            "NSPrivacyCollectedDataType": kind,
            "NSPrivacyCollectedDataTypeLinked": True,
            "NSPrivacyCollectedDataTypeTracking": False,
            "NSPrivacyCollectedDataTypePurposes": ["NSPrivacyCollectedDataTypePurposeAppFunctionality"],
        }
        entry.update(overrides)
        return {k: v for k, v in entry.items() if v is not None}

    def assert_collected_rejected(self, entries, message):
        self.manifest(self.app, {}, entries)
        with self.assertRaisesRegex(privacy.CheckError, message):
            privacy.load_manifest(self.app / privacy.MANIFEST)

    def test_accepts_valid_collected_data(self):
        self.manifest(self.app, {}, [self.collected_entry(), self.collected_entry("NSPrivacyCollectedDataTypeDeviceID")])
        path = self.app / privacy.MANIFEST
        privacy.load_manifest(path)
        self.assertEqual(sorted(privacy.collected_data_types(path, privacy.read_plist(path))),
                         ["NSPrivacyCollectedDataTypeDeviceID", "NSPrivacyCollectedDataTypeOtherUserContent"])

    def test_rejects_invalid_collected_data(self):
        self.assert_collected_rejected([self.collected_entry("NSPrivacyCollectedDataTypeFiles")], "unknown collected data type")
        self.assert_collected_rejected([self.collected_entry(), self.collected_entry()], "declared twice")
        self.assert_collected_rejected([self.collected_entry(NSPrivacyCollectedDataTypeLinked=None)], "missing")
        self.assert_collected_rejected([self.collected_entry(NSPrivacyCollectedDataTypeLinked="yes")], "booleans")
        self.assert_collected_rejected([self.collected_entry(NSPrivacyCollectedDataTypeTracking=True)], "tracking")
        self.assert_collected_rejected([self.collected_entry(NSPrivacyCollectedDataTypePurposes=[])], "at least one purpose")
        self.assert_collected_rejected(
            [self.collected_entry(NSPrivacyCollectedDataTypePurposes=["NSPrivacyCollectedDataTypePurposeSelling"])],
            "unknown purposes")
        self.assert_collected_rejected(
            [self.collected_entry(NSPrivacyCollectedDataTypePurposes=["NSPrivacyCollectedDataTypePurposeOther"] * 2)],
            "purpose twice")
        self.assert_collected_rejected([dict(self.collected_entry(), Extra=1)], "unknown keys")

    def test_requires_explicit_tracking_fields(self):
        for key in ["NSPrivacyTracking", "NSPrivacyTrackingDomains", "NSPrivacyCollectedDataTypes"]:
            self.manifest(self.app, {})
            data = plistlib.loads((self.app / privacy.MANIFEST).read_bytes())
            del data[key]
            (self.app / privacy.MANIFEST).write_bytes(plistlib.dumps(data))
            with self.assertRaisesRegex(privacy.CheckError, key):
                privacy.load_manifest(self.app / privacy.MANIFEST)

    def test_app_manifest_is_conservative_and_extensions_collect_nothing(self):
        def collected(name):
            path = ROOT / "ios" / name / privacy.MANIFEST
            return privacy.collected_data_types(path, privacy.read_plist(path))
        app = collected("Youngmin")
        # User decision: declare everything that plausibly leaves the device (chat via OpenAI).
        for kind in ["OtherUserContent", "PhotosorVideos", "UserID", "DeviceID", "EmailAddress", "Name",
                     "ProductInteraction", "OtherDiagnosticData"]:
            self.assertIn("NSPrivacyCollectedDataType" + kind, app)
        for kind, (linked, tracking, purposes) in app.items():
            self.assertTrue(linked, kind)
            self.assertFalse(tracking, kind)
            self.assertIn("NSPrivacyCollectedDataTypePurposeAppFunctionality", purposes, kind)
        self.assertEqual(collected("YoungminWidgets"), {})
        self.assertEqual(collected("YoungminShare"), {})

    def test_privacy_labels_document_mirrors_app_manifest(self):
        path = ROOT / "ios/Youngmin" / privacy.MANIFEST
        manifest = privacy.collected_data_types(path, privacy.read_plist(path))
        rows = {}
        for line in (ROOT / "ios/Docs/privacy-labels.md").read_text(encoding="utf-8").splitlines():
            match = re.match(r"^\| `(NSPrivacyCollectedDataType[A-Za-z]+)` \|", line)
            if not match:
                continue
            cells = [c.strip() for c in line.strip().strip("|").split("|")]
            self.assertEqual(cells[2], "예", line)
            purposes = sorted("NSPrivacyCollectedDataTypePurpose" + p for p in re.findall(r"\(([A-Za-z]+)\)", cells[5]))
            rows[match.group(1)] = (cells[3] == "예", cells[4] != "아니요", purposes)
        self.assertEqual(rows, manifest)


if __name__ == "__main__":
    unittest.main()
