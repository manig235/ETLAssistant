#!/usr/bin/env python3
"""Check exact native library keys and manifest verification without Xcode or Rust."""
import copy
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("codex_lib", ROOT / ".github/scripts/ios-codex-lib.py")
lib = importlib.util.module_from_spec(spec)
spec.loader.exec_module(lib)
TOOLS = {"xcode": "Xcode 26.0 Build version 17A0", "sdk_version": "26.0", "sdk_build": "23A0"}


class KeyTests(unittest.TestCase):
    def setUp(self):
        self.inputs = lib.key_inputs("release", tools=TOOLS)

    def test_key_covers_native_inputs_pins_and_flags(self):
        files = self.inputs["inputs"]
        self.assertIn(".github/scripts/ios-codex-probe.sh", files)
        self.assertIn(".github/scripts/prepare-ios-codex.py", files)
        self.assertTrue(any(name.startswith("ios/NativeCodex/bridge/") for name in files))
        self.assertTrue(any(name.startswith("ios/NativeCodex/javascript/") for name in files))
        self.assertEqual(self.inputs["pinned"]["revision"], "29eb79e9866b3262a777fdf8195c1b9e63684c91")
        self.assertEqual(self.inputs["flags"]["CARGO_PROFILE_DEV_SMALL_DEBUG_ASSERTIONS"], "false")

    def test_any_change_produces_a_different_exact_key(self):
        base = lib.keys(self.inputs, "X64")
        self.assertEqual(base, lib.keys(copy.deepcopy(self.inputs), "X64"))
        self.assertTrue(base["key"].startswith("ios-codex-lib-v1-iphoneos-arm64-release-rust1.95.0-sdk23A0-"))
        self.assertTrue(base["build-key"].startswith(base["build-prefix"]))
        self.assertTrue(base["build-prefix"].startswith("ios-codex-build-v1-X64-"))
        changes = []
        source = copy.deepcopy(self.inputs)
        source["inputs"]["ios/NativeCodex/bridge/src/lib.rs"] = "0" * 64
        changes.append(source)
        tools = copy.deepcopy(self.inputs)
        tools["toolchain"]["xcode"] = "Xcode 26.1 Build version 17B0"
        changes.append(tools)
        pins = copy.deepcopy(self.inputs)
        pins["pinned"]["toolchain"] = "1.96.0"
        changes.append(pins)
        changes.append(lib.key_inputs("fast", tools=TOOLS))
        for changed in changes:
            self.assertNotEqual(base["key"], lib.keys(changed, "X64")["key"])
        # The compile tree survives an Xcode update; only the exact library does not.
        self.assertEqual(base["build-key"], lib.keys(tools, "X64")["build-key"])
        self.assertNotEqual(base["build-key"], lib.keys(source, "X64")["build-key"])
        self.assertNotEqual(base["build-prefix"], lib.keys(changes[-1], "X64")["build-prefix"])

    def test_untracked_files_do_not_change_the_key(self):
        with tempfile.TemporaryDirectory(dir=ROOT / "ios/NativeCodex/javascript") as scratch:
            (Path(scratch) / "stray.txt").write_text("not a source file")
            self.assertEqual(lib.key_inputs("release", tools=TOOLS), self.inputs)


class ManifestTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        base = Path(self.temp.name)
        self.generated, self.stage, self.installed = base / "generated", base / "stage", base / "installed"
        for name in lib.REQUIRED:
            path = self.generated / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("-framework Foundation\n" if name == lib.SYSTEM_LIBS else "synthetic " + name)
        self.inputs = {"schema": 1, "profile": "release", "toolchain": TOOLS}
        self.key = "ios-codex-lib-v1-test"
        lib.stage(self.inputs, self.key, generated=self.generated, stage_dir=self.stage, built={"run_id": "1"})

    def tearDown(self):
        self.temp.cleanup()

    def test_verified_library_installs_byte_for_byte(self):
        manifest = lib.verify(self.inputs, self.key, stage_dir=self.stage)
        lib.install(manifest, generated=self.installed, stage_dir=self.stage)
        for name in lib.REQUIRED:
            self.assertEqual((self.installed / name).read_bytes(), (self.generated / name).read_bytes())

    def test_rejects_other_key_or_inputs(self):
        with self.assertRaises(SystemExit):
            lib.verify(self.inputs, self.key + "-other", stage_dir=self.stage)
        other = dict(self.inputs, profile="fast")
        with self.assertRaises(SystemExit):
            lib.verify(other, self.key, stage_dir=self.stage)

    def test_rejects_modified_missing_or_extra_files(self):
        library = self.stage / "files" / lib.LIBRARY
        library.write_text("tampered")
        with self.assertRaises(SystemExit):
            lib.verify(self.inputs, self.key, stage_dir=self.stage)
        library.unlink()
        with self.assertRaises(SystemExit):
            lib.verify(self.inputs, self.key, stage_dir=self.stage)
        lib.stage(self.inputs, self.key, generated=self.generated, stage_dir=self.stage)
        (self.stage / "files" / "extra.a").write_text("unlisted")
        with self.assertRaises(SystemExit):
            lib.verify(self.inputs, self.key, stage_dir=self.stage)

    def test_rejects_manifest_without_required_files(self):
        manifest = json.loads((self.stage / lib.MANIFEST).read_text())
        del manifest["files"]["Codex-LICENSE.txt"]
        (self.stage / "files/Codex-LICENSE.txt").unlink()
        (self.stage / lib.MANIFEST).write_text(json.dumps(manifest))
        with self.assertRaises(SystemExit):
            lib.verify(self.inputs, self.key, stage_dir=self.stage)

    def test_stage_requires_a_complete_build(self):
        (self.generated / lib.SYSTEM_LIBS).write_text("")
        with self.assertRaises(SystemExit):
            lib.stage(self.inputs, self.key, generated=self.generated, stage_dir=self.stage)


if __name__ == "__main__":
    subprocess.run(["git", "-C", str(ROOT), "rev-parse", "--git-dir"], check=True, stdout=subprocess.DEVNULL)
    unittest.main()
