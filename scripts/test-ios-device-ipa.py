#!/usr/bin/env python3
"""Device/simulator distinction and re-signable IPA contents, without Xcode."""
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import plistlib
import stat
import struct
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location(
    "ipa", Path(__file__).resolve().parents[1] / ".github/scripts/package-device-ipa.py"
)
ipa = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ipa)


class DeviceIPATests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.app = self.root / "Youngmin.app"
        self.app.mkdir()
        self.info = {
            "CFBundleSupportedPlatforms": ["iPhoneOS"],
            "CFBundleExecutable": "Youngmin",
            "CFBundleIdentifier": "kr.snu.etlassistant.ios",
            "MinimumOSVersion": "17.0",
            "UIDeviceFamily": [1],
        }
        self.write_info()
        self.write_binary(platform=2)

    def write_info(self):
        (self.app / "Info.plist").write_bytes(plistlib.dumps(self.info))

    def write_binary(self, platform):
        header = struct.pack("<8I", 0xFEEDFACF, 0x0100000C, 0, 2, 1, 24, 0, 0)
        command = struct.pack("<6I", 0x32, 24, platform, 0x110000, 0x1A0000, 0)
        binary = self.app / "Youngmin"
        binary.write_bytes(header + command)
        binary.chmod(0o755)

    def test_packages_device_and_preserves_executable_permissions(self):
        extension = self.app / "PlugIns/YoungminWidgets.appex"
        extension.mkdir(parents=True)
        (extension / "Widget").write_bytes(b"extension")
        signature = self.app / "_CodeSignature"
        signature.mkdir()
        (signature / "CodeResources").write_bytes(b"old signature")
        (self.app / "embedded.mobileprovision").write_bytes(b"old profile")
        output = self.root / "output/Youngmin-unsigned.ipa"
        with contextlib.redirect_stdout(io.StringIO()):
            ipa.package(self.app, output)
        with zipfile.ZipFile(output) as archive:
            self.assertEqual(set(archive.namelist()), {
                "Payload/Youngmin.app/Info.plist", "Payload/Youngmin.app/Youngmin"
            })
            executable = archive.getinfo("Payload/Youngmin.app/Youngmin")
            self.assertTrue((executable.external_attr >> 16) & stat.S_IXUSR)
        self.assertTrue(extension.exists(), "Source build product must be preserved")
        metadata = json.loads(output.with_suffix(".json").read_text())
        self.assertEqual(metadata["removed_extensions"], ["YoungminWidgets.appex"])
        self.assertTrue(metadata["unsigned"])
        self.assertIn(metadata["sha256"], output.with_suffix(".ipa.sha256").read_text())

    def test_rejects_simulator_info(self):
        self.info["CFBundleSupportedPlatforms"] = ["iPhoneSimulator"]
        self.write_info()
        with self.assertRaisesRegex(ValueError, "device build"):
            ipa.validate_app(self.app)

    def test_rejects_arm64_simulator_even_with_device_info(self):
        self.write_binary(platform=7)
        with self.assertRaisesRegex(ValueError, "actual iOS device"):
            ipa.validate_app(self.app)

    def test_rejects_truncated_executable(self):
        (self.app / "Youngmin").write_bytes(b"not a binary")
        with self.assertRaisesRegex(ValueError, "Mach-O"):
            ipa.validate_app(self.app)

    def test_rejects_truncated_load_command(self):
        data = (self.app / "Youngmin").read_bytes()
        (self.app / "Youngmin").write_bytes(data[:-1])
        with self.assertRaisesRegex(ValueError, "Truncated"):
            ipa.validate_app(self.app)


if __name__ == "__main__":
    unittest.main()
