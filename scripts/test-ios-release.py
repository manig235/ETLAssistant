#!/usr/bin/env python3
"""Release archive helper rules, without Xcode."""
import contextlib
import datetime
import importlib.util
import io
import os
from pathlib import Path
import plistlib
import shutil
import struct
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("release", ROOT / ".github/scripts/ios-release-helper.py")
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)
TEAM = "ABCDE12345"
NOW = datetime.datetime(2026, 11, 26, tzinfo=datetime.timezone.utc)


def quiet(function, *args, **kwargs):
    with contextlib.redirect_stdout(io.StringIO()) as output:
        function(*args, **kwargs)
    return output.getvalue()


class ConfigTests(unittest.TestCase):
    def test_committed_release_config_embeds_engine(self):
        self.assertIn("PASS", quiet(release.config_check))

    def test_link_units_keep_frameworks_together(self):
        self.assertEqual(release.link_units("-framework UIKit -lc -framework UIKit"), {"-framework UIKit", "-lc"})

    def test_missing_system_library_fails(self):
        with self.assertRaises(release.ReleaseError):
            release.check_link_flags("-framework Metal -lc", "test")

    def test_build_number_is_unique_per_run(self):
        base = release.read_xcconfig(release.PROJECT_CONFIG)["CURRENT_PROJECT_VERSION"]
        self.assertEqual(quiet(release.build_number, type("A", (), {"run": "41", "override": ""})).strip(), f"{base}.41")
        with self.assertRaises(release.ReleaseError):
            release.build_number(type("A", (), {"run": "", "override": "1.2.3.4"}))


class AppOnlyProjectTests(unittest.TestCase):
    def test_removes_extension_dependencies_and_embedding(self):
        with tempfile.TemporaryDirectory() as temp:
            source = Path(temp) / "Youngmin.xcodeproj"
            shutil.copytree(ROOT / "ios/Youngmin.xcodeproj", source)
            dest = Path(temp) / "YoungminAppOnly.xcodeproj"
            quiet(release.app_only_project, type("A", (), {"source": str(source), "dest": str(dest)}))
            text = (dest / "project.pbxproj").read_text()
            app = text[text.index("A10000000000000000000030 /* Youngmin */ = {"):]
            app = app[:app.index("productType")]
            self.assertNotIn("Embed Foundation Extensions", app)
            self.assertNotIn("PBXTargetDependency", app)
            self.assertIn("A10000000000000000000006 /* Resources */", app)
            scheme = (dest / "xcshareddata/xcschemes/Youngmin.xcscheme").read_text()
            self.assertIn("container:YoungminAppOnly.xcodeproj", scheme)
            self.assertNotIn("container:Youngmin.xcodeproj", scheme)

    def test_accepts_relative_source_and_absolute_destination(self):
        with tempfile.TemporaryDirectory() as temp:
            shutil.copytree(ROOT / "ios/Youngmin.xcodeproj", Path(temp) / "ios/Youngmin.xcodeproj")
            previous = os.getcwd()
            os.chdir(temp)
            try:
                quiet(release.app_only_project, type("A", (), {
                    "source": "ios/Youngmin.xcodeproj", "dest": f"{temp}/ios/YoungminAppOnly.xcodeproj"}))
            finally:
                os.chdir(previous)
            self.assertTrue((Path(temp) / "ios/YoungminAppOnly.xcodeproj/project.pbxproj").is_file())

    def test_changed_project_fails_loudly(self):
        with tempfile.TemporaryDirectory() as temp:
            source = Path(temp) / "Youngmin.xcodeproj"
            shutil.copytree(ROOT / "ios/Youngmin.xcodeproj", source)
            pbx = source / "project.pbxproj"
            pbx.write_text(pbx.read_text().replace(release.EMBED_PHASE, ""))
            with self.assertRaises(release.ReleaseError):
                release.app_only_project(type("A", (), {"source": str(source), "dest": str(Path(temp) / "X.xcodeproj")}))


class ProfileTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.cert = b"certificate-der"

    def write(self, bundle="kr.snu.etlassistant.ios", devices=None, groups=("group.kr.snu.etlassistant.ios",), **extra):
        profile = {
            "Name": "Youngmin", "UUID": "12345678-1234-1234-1234-1234567890AB", "TeamIdentifier": [TEAM],
            "ExpirationDate": NOW + datetime.timedelta(days=30), "DeveloperCertificates": [self.cert],
            "Entitlements": {"application-identifier": f"{TEAM}.{bundle}", "get-task-allow": False,
                             "com.apple.security.application-groups": list(groups)},
        }
        if devices:
            profile["ProvisionedDevices"] = devices
        profile.update(extra)
        path = Path(self.temp.name) / "p.mobileprovision"
        # Shape of a CMS envelope: binary prefix and suffix around the plist.
        path.write_bytes(b"\x30\x82junk" + plistlib.dumps(profile) + b"\x00trailer")
        return path

    def info(self, path, method="app-store-connect", **kwargs):
        return release.profile_info(path, method, TEAM, kwargs.pop("bundle", "kr.snu.etlassistant.ios"), now=NOW, **kwargs)

    def test_valid_app_store_profile(self):
        import hashlib
        info = self.info(self.write(), app_group="group.kr.snu.etlassistant.ios",
                         certificate_sha1=hashlib.sha1(self.cert).hexdigest())
        self.assertEqual(info["uuid"], "12345678-1234-1234-1234-1234567890AB")

    def test_rejects_wrong_kind_bundle_team_and_certificate(self):
        with self.assertRaises(release.ReleaseError):
            self.info(self.write(devices=["a"]))
        with self.assertRaises(release.ReleaseError):
            self.info(self.write(), method="release-testing")
        with self.assertRaises(release.ReleaseError):
            self.info(self.write(bundle="other.app"))
        with self.assertRaises(release.ReleaseError):
            self.info(self.write(TeamIdentifier=["ZZZZZ99999"]))
        with self.assertRaises(release.ReleaseError):
            self.info(self.write(), certificate_sha1="00" * 20)
        with self.assertRaises(release.ReleaseError):
            self.info(self.write(groups=()), app_group="group.kr.snu.etlassistant.ios")
        with self.assertRaises(release.ReleaseError):
            self.info(self.write(ExpirationDate=NOW - datetime.timedelta(days=1)))

    def test_ad_hoc_profile_for_release_testing(self):
        self.assertEqual(self.info(self.write(devices=["a", "b"]), method="release-testing")["devices"], 2)

    @unittest.skipUnless(shutil.which("openssl"), "openssl not installed")
    def test_decodes_cms_signed_profile(self):
        temp = Path(self.temp.name)
        subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1", "-subj", "/CN=t",
                        "-keyout", temp / "k.pem", "-out", temp / "c.pem"], check=True, capture_output=True)
        plain = temp / "plain.plist"
        plain.write_bytes(plistlib.dumps({"UUID": "X", "Name": "cms"}))
        subprocess.run(["openssl", "smime", "-sign", "-binary", "-nodetach", "-outform", "der", "-signer", temp / "c.pem",
                        "-inkey", temp / "k.pem", "-in", plain, "-out", temp / "s.mobileprovision"], check=True)
        self.assertEqual(release.decode_profile(temp / "s.mobileprovision")["Name"], "cms")


class ExportOptionsTests(unittest.TestCase):
    def test_app_store_connect(self):
        options = release.export_options("app-store-connect", TEAM, {"kr.snu.etlassistant.ios": "U1"}, "SHA")
        self.assertEqual(options["method"], "app-store-connect")
        self.assertEqual(options["signingStyle"], "manual")
        self.assertTrue(options["uploadSymbols"])
        self.assertFalse(options["manageAppVersionAndBuildNumber"])
        plistlib.loads(plistlib.dumps(options))

    def test_release_testing_and_validation(self):
        options = release.export_options("release-testing", TEAM, {"kr.snu.etlassistant.ios": "U1"}, "SHA")
        self.assertEqual(options["thinning"], "<none>")
        with self.assertRaises(release.ReleaseError):
            release.export_options("ad-hoc", TEAM, {"kr.snu.etlassistant.ios": "U1"}, "SHA")
        with self.assertRaises(release.ReleaseError):
            release.export_options("app-store-connect", "short", {"kr.snu.etlassistant.ios": "U1"}, "SHA")
        with self.assertRaises(release.ReleaseError):
            release.export_options("app-store-connect", TEAM, {"x": "U1"}, "SHA")


class VerifyArchiveTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.archive = Path(self.temp.name) / "Youngmin.xcarchive"
        self.app = self.archive / "Products/Applications/Youngmin.app"
        self.app.mkdir(parents=True)
        (self.archive / "Info.plist").write_bytes(plistlib.dumps({"ApplicationProperties": {"ApplicationPath": "Applications/Youngmin.app"}}))
        self.info = {
            "CFBundleIdentifier": "kr.snu.etlassistant.ios", "CFBundleExecutable": "Youngmin",
            "CFBundleShortVersionString": "0.3.3", "CFBundleVersion": "26.4", "CFBundleSupportedPlatforms": ["iPhoneOS"],
            "YoungminAuthEngine": "codex-in-process", "YoungminCodeModeRuntime": "quickjs",
            "YoungminCodexSourceRevision": "rev", "ITSAppUsesNonExemptEncryption": False,
        }
        (self.app / "Info.plist").write_bytes(plistlib.dumps(self.info))
        header = struct.pack("<8I", 0xFEEDFACF, 0x0100000C, 0, 2, 1, 24, 0, 0)
        command = struct.pack("<6I", 0x32, 24, 2, 0x110000, 0x1A0000, 0)
        (self.app / "Youngmin").write_bytes(header + command + b"".join(release.ENGINE_MARKERS))
        (self.app / "PrivacyInfo.xcprivacy").write_bytes(b"x")
        for path in release.LICENSES.iterdir():
            shutil.copy(path, self.app / path.name)

    def verify(self, mode="app-only"):
        return quiet(release.verify_archive, self.archive, mode, "26.4", "0.3.3", revision="rev", check_symbols=False)

    def test_accepts_engine_archive(self):
        self.assertIn("PASS", self.verify())

    def test_rejects_swift_fallback_build(self):
        self.info["YoungminAuthEngine"] = "swift"
        (self.app / "Info.plist").write_bytes(plistlib.dumps(self.info))
        with self.assertRaises(release.ReleaseError):
            self.verify()

    def test_rejects_unlinked_engine(self):
        data = (self.app / "Youngmin").read_bytes().replace(b"youngmin/ready", b"")
        (self.app / "Youngmin").write_bytes(data)
        with self.assertRaises(release.ReleaseError):
            self.verify()

    def test_app_only_rejects_extensions_and_full_requires_them(self):
        (self.app / "PlugIns/YoungminWidgets.appex").mkdir(parents=True)
        with self.assertRaises(release.ReleaseError):
            self.verify()
        with self.assertRaises(release.ReleaseError):
            self.verify("full")

    def test_full_archive_versions_match(self):
        for name, suffix in release.EXTENSIONS.items():
            ext = self.app / f"PlugIns/{name}.appex"
            ext.mkdir(parents=True)
            (ext / "PrivacyInfo.xcprivacy").write_bytes(b"x")
            (ext / "Info.plist").write_bytes(plistlib.dumps({
                "CFBundleIdentifier": "kr.snu.etlassistant.ios" + suffix,
                "CFBundleShortVersionString": "0.3.3", "CFBundleVersion": "26.4"}))
        self.assertIn("PASS", self.verify("full"))
        (self.app / "PlugIns/YoungminShare.appex/Info.plist").write_bytes(plistlib.dumps({
            "CFBundleIdentifier": "kr.snu.etlassistant.ios.share", "CFBundleShortVersionString": "0.3.3", "CFBundleVersion": "22"}))
        with self.assertRaises(release.ReleaseError):
            self.verify("full")

    def test_missing_license_or_privacy_manifest(self):
        (self.app / "Codex-NOTICE.txt").unlink()
        with self.assertRaises(release.ReleaseError):
            self.verify()


if __name__ == "__main__":
    unittest.main()
