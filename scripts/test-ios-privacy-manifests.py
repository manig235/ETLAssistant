#!/usr/bin/env python3
"""Privacy manifest checks (.github/scripts/check-privacy-manifests.py), without Xcode."""
import importlib.util
from pathlib import Path
import plistlib
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

    def manifest(self, bundle, categories):
        bundle.mkdir(parents=True, exist_ok=True)
        (bundle / privacy.MANIFEST).write_bytes(plistlib.dumps({
            "NSPrivacyTracking": False,
            "NSPrivacyTrackingDomains": [],
            "NSPrivacyCollectedDataTypes": [],
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


if __name__ == "__main__":
    unittest.main()
