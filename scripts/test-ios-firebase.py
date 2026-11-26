#!/usr/bin/env python3
"""Exercise distribution rejection paths and credential cleanup without Apple/Google access."""

import base64
import contextlib
import copy
import datetime as dt
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import plistlib
import subprocess
import tempfile
import unittest
from unittest import mock

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("distribution", ROOT / ".github/scripts/ios-firebase-distribute.py")
distribution = importlib.util.module_from_spec(spec)
spec.loader.exec_module(distribution)
TEAM = "ABCDE12345"
CERT = b"synthetic-certificate"
IDENTITY = hashlib.sha1(CERT).hexdigest().upper()


def profiles():
    result = {}
    for i, (bundle, _, _) in enumerate(distribution.TARGETS):
        result[bundle] = {
            "UUID": f"00000000-0000-0000-0000-{i:012d}", "Name": "Youngmin AdHoc " + str(i),
            "TeamIdentifier": [TEAM], "ApplicationIdentifierPrefix": [TEAM],
            "ExpirationDate": dt.datetime.now(dt.timezone.utc).replace(tzinfo=None) + dt.timedelta(days=30),
            "ProvisionedDevices": ["SYNTHETIC-DEVICE-A", "SYNTHETIC-DEVICE-B"],
            "DeveloperCertificates": [CERT],
            "Entitlements": {"application-identifier": TEAM + "." + bundle,
                             "com.apple.developer.team-identifier": TEAM,
                             "get-task-allow": False,
                             "com.apple.security.application-groups": [distribution.APP_GROUP]},
        }
    return result


def settings():
    env = {key: base64.b64encode(b"synthetic-file").decode()
           for key in ("IOS_DISTRIBUTION_P12_BASE64",) + tuple(t[1] for t in distribution.TARGETS)}
    env.update(IOS_TEAM_ID=TEAM, IOS_DISTRIBUTION_P12_PASSWORD="secret-marker-password")
    return env


def firebase_settings():
    return {"FIREBASE_APP_ID": "1:123456:ios:abc123", "FIREBASE_GROUPS": "friends",
            "FIREBASE_SERVICE_ACCOUNT_JSON": json.dumps({"type": "service_account", "project_id": "demo",
                "client_email": "demo@example.test", "private_key": "secret-marker-key"}),
            "RELEASE_NOTES": "한글 안내\n$(do-not-execute) `literal`"}


class ProfileTests(unittest.TestCase):
    def test_valid_adhoc_profiles_and_manual_export_mapping(self):
        data = profiles()
        distribution.validate_profile_set(data, TEAM, CERT)
        options = plistlib.loads(plistlib.dumps(distribution.export_options(TEAM, IDENTITY, data)))
        self.assertEqual(options["method"], "release-testing")
        self.assertEqual(options["signingCertificate"], IDENTITY)
        self.assertEqual(set(options["provisioningProfiles"]), set(data))

    def test_rejects_wrong_team_bundle_expiry_certificate_and_app_group(self):
        original = profiles()
        mutations = [
            lambda p: p.update(TeamIdentifier=["WRONG12345"]),
            lambda p: p["Entitlements"].update({"application-identifier": TEAM + ".*"}),
            lambda p: p.update(ExpirationDate=dt.datetime(2000, 1, 1)),
            lambda p: p.update(DeveloperCertificates=[b"different-certificate"]),
            lambda p: p["Entitlements"].update({"com.apple.security.application-groups": []}),
        ]
        for mutate in mutations:
            with self.subTest(mutation=mutate):
                data = copy.deepcopy(original)
                mutate(data[distribution.TARGETS[0][0]])
                with self.assertRaises(ValueError):
                    distribution.validate_profile_set(data, TEAM, CERT)

    def test_rejects_development_enterprise_and_app_store_profiles(self):
        for change in ("debug", "enterprise", "app-store"):
            with self.subTest(change=change):
                data = profiles()
                app = data[distribution.TARGETS[0][0]]
                if change == "debug":
                    app["Entitlements"]["get-task-allow"] = True
                elif change == "enterprise":
                    app["ProvisionsAllDevices"] = True
                else:
                    del app["ProvisionedDevices"]
                with self.assertRaisesRegex(ValueError, "Ad Hoc"):
                    distribution.validate_profile_set(data, TEAM, CERT)

    def test_rejects_extension_with_different_device_set(self):
        data = profiles()
        data[distribution.TARGETS[1][0]]["ProvisionedDevices"].pop()
        with self.assertRaisesRegex(ValueError, "same registered devices"):
            distribution.validate_profile_set(data, TEAM, CERT)

    def test_missing_settings_report_names_without_values(self):
        env = settings()
        del env["IOS_SHARE_PROFILE_BASE64"]
        with self.assertRaisesRegex(ValueError, "IOS_SHARE_PROFILE_BASE64") as error:
            distribution.check_config(env)
        self.assertNotIn(env["IOS_DISTRIBUTION_P12_PASSWORD"], str(error.exception))

    def test_build_only_does_not_need_google_credentials(self):
        distribution.check_config(settings())
        with self.assertRaisesRegex(ValueError, "FIREBASE_APP_ID"):
            distribution.check_config(settings(), publish=True)

    def test_rejects_invalid_base64_without_echoing_it(self):
        env = settings()
        env["IOS_DISTRIBUTION_P12_BASE64"] = "secret!notbase64"
        with self.assertRaises(ValueError) as error:
            distribution.check_config(env)
        self.assertNotIn("secret!notbase64", str(error.exception))


class PublishTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.patch = mock.patch.object(distribution, "OUTPUT", self.root)
        self.patch.start()
        self.addCleanup(self.patch.stop)
        self.ipa = self.root / "Youngmin-AdHoc.ipa"
        self.ipa.write_bytes(b"synthetic-verified-ipa")
        self.metadata = {"configuration": "Release", "distribution": "ad-hoc", "version": "0.3.3",
                         "build": "1.1", "commit": "synthetic-commit",
                         "sha256": hashlib.sha256(self.ipa.read_bytes()).hexdigest()}
        self.write_metadata()
        self.env = firebase_settings()
        self.env.update(RUNNER_TEMP=str(self.root), GITHUB_STEP_SUMMARY=str(self.root / "summary.md"))

    def write_metadata(self):
        (self.root / "build.json").write_text(json.dumps(self.metadata))

    def test_no_upload_of_modified_or_debug_ipa(self):
        for change in ("tamper", "debug"):
            with self.subTest(change=change), mock.patch.object(distribution, "run") as run:
                if change == "tamper":
                    self.ipa.write_bytes(b"tampered")
                else:
                    self.ipa.write_bytes(b"synthetic-verified-ipa")
                    self.metadata["configuration"] = "Debug"
                    self.write_metadata()
                with self.assertRaisesRegex(ValueError, "verified Release"):
                    distribution.publish(self.env)
                run.assert_not_called()

    def test_upload_passes_notes_literally_and_removes_credentials(self):
        credentials_seen = []

        def fake_run(args, **kwargs):
            credentials = Path(kwargs["env"]["GOOGLE_APPLICATION_CREDENTIALS"])
            credentials_seen.append(credentials)
            self.assertEqual(credentials.stat().st_mode & 0o777, 0o600)
            self.assertEqual(json.loads(credentials.read_text())["private_key"], "secret-marker-key")
            self.assertNotIn("FIREBASE_SERVICE_ACCOUNT_JSON", kwargs["env"])
            notes = Path(args[args.index("--release-notes-file") + 1]).read_text()
            self.assertIn(self.env["RELEASE_NOTES"], notes)
            self.assertEqual(args[args.index("--groups") + 1], "friends")
            return (b"Share: https://appdistribution.firebase.google.com/testerapps/demo/releases/one\n"
                    b"Download: https://example.test/secret-marker-signed-link\n")

        output = io.StringIO()
        with mock.patch.object(distribution, "run", side_effect=fake_run), contextlib.redirect_stdout(output):
            distribution.publish(self.env)
        self.assertFalse(credentials_seen[0].exists())
        self.assertIn("testerapps/demo/releases/one", output.getvalue())
        self.assertNotIn("secret-marker", output.getvalue())
        self.assertNotIn("secret-marker", Path(self.env["GITHUB_STEP_SUMMARY"]).read_text())

    def test_upload_failure_also_removes_credentials(self):
        credentials_seen = []

        def fail(args, **kwargs):
            credentials_seen.append(Path(kwargs["env"]["GOOGLE_APPLICATION_CREDENTIALS"]))
            raise RuntimeError("firebase failed")

        with mock.patch.object(distribution, "run", side_effect=fail), self.assertRaises(RuntimeError):
            distribution.publish(self.env)
        self.assertFalse(credentials_seen[0].exists())

    def test_rejects_bad_service_account_without_showing_contents(self):
        for value in ("secret-marker-invalid-json", "[]", '{"type":"authorized_user"}'):
            self.env["FIREBASE_SERVICE_ACCOUNT_JSON"] = value
            with self.assertRaises(ValueError) as error:
                distribution.firebase_config(self.env)
            self.assertNotIn(value, str(error.exception))


class ExportVerificationTests(unittest.TestCase):
    def test_export_requires_both_extensions_and_distribution_entitlements(self):
        for scenario in ("valid", "missing-share", "debug-signature", "wrong-version", "wrong-engine", "wrong-javascript-runtime"):
            with self.subTest(scenario=scenario), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                data = profiles()
                app = root / "verified-ipa/Payload/Youngmin.app"
                bundles = [app, app / "PlugIns/YoungminWidgets.appex", app / "PlugIns/YoungminShare.appex"]
                profile_for_path = {}
                for (bundle_id, _, _), bundle in zip(distribution.TARGETS, bundles):
                    if scenario == "missing-share" and bundle_id.endswith(".share"):
                        continue
                    bundle.mkdir(parents=True)
                    info = {"CFBundleIdentifier": bundle_id, "CFBundleShortVersionString": "0.3.3",
                            "CFBundleVersion": "1.1", "YoungminAuthEngine": "codex-in-process",
                            "YoungminCodeModeRuntime": "quickjs"}
                    if scenario == "wrong-version" and bundle_id.endswith(".widgets"):
                        info["CFBundleVersion"] = "2.1"
                    if scenario == "wrong-engine":
                        info["YoungminAuthEngine"] = "stub"
                    if scenario == "wrong-javascript-runtime":
                        info["YoungminCodeModeRuntime"] = "javascriptcore"
                    (bundle / "Info.plist").write_bytes(plistlib.dumps(info))
                    profile_for_path[bundle] = data[bundle_id]

                def fake_run(args, **kwargs):
                    if args[0] == "codesign" and "-d" in args:
                        entitlements = copy.deepcopy(profile_for_path[args[-1]]["Entitlements"])
                        if scenario == "debug-signature":
                            entitlements["get-task-allow"] = True
                        return plistlib.dumps(entitlements)
                    return b""

                with mock.patch.object(distribution, "run", side_effect=fake_run), \
                        mock.patch.object(distribution, "read_profile", side_effect=lambda p: profile_for_path[p.parent]):
                    if scenario == "valid":
                        info = distribution.verify_export(root / "synthetic.ipa", root, TEAM, CERT)
                        self.assertEqual(info["CFBundleVersion"], "1.1")
                    else:
                        with self.assertRaises(ValueError):
                            distribution.verify_export(root / "synthetic.ipa", root, TEAM, CERT)


class P12CompatibilityTests(unittest.TestCase):
    def test_legacy_retry_is_limited_to_unsupported_encryption_and_keeps_mac_checking(self):
        command = ["-in", "synthetic.p12", "-passin", "env:SYNTHETIC_PASSWORD", "-noout"]
        for classification in ("PKCS12_UNSUPPORTED_ENCRYPTION", "PKCS12_PASSWORD_REJECTED"):
            with self.subTest(classification=classification), \
                    mock.patch.object(distribution, "run", side_effect=[RuntimeError(classification), b"verified"]) as run:
                if classification == "PKCS12_UNSUPPORTED_ENCRYPTION":
                    self.assertEqual(distribution.run_pkcs12(command, capture=True), b"verified")
                    self.assertEqual(run.call_count, 2)
                    self.assertNotIn("-legacy", run.call_args_list[0].args[0])
                    self.assertIn("-legacy", run.call_args_list[1].args[0])
                else:
                    with self.assertRaisesRegex(RuntimeError, classification):
                        distribution.run_pkcs12(command, capture=True)
                    self.assertEqual(run.call_count, 1)
                self.assertTrue(all("-nomacver" not in call.args[0] for call in run.call_args_list))

    def test_password_verification_hides_errors_and_removes_temporary_file(self):
        for failure in (None, "PKCS12_PASSWORD_REJECTED secret-marker-output"):
            with self.subTest(failure=failure), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                env = dict(settings(), RUNNER_TEMP=str(root))
                def fake_run(args, **kwargs):
                    self.assertIn("-noout", args)
                    self.assertNotIn(env["IOS_DISTRIBUTION_P12_PASSWORD"], args)
                    if failure:
                        raise RuntimeError(failure)
                    return b""
                with mock.patch.object(distribution, "run", side_effect=fake_run):
                    if failure:
                        with self.assertRaisesRegex(ValueError, "PKCS12_PASSWORD_REJECTED") as error:
                            distribution.verify_p12_password(env)
                        self.assertNotIn("secret-marker", str(error.exception))
                    else:
                        distribution.verify_p12_password(env)
                self.assertEqual(list(root.iterdir()), [])

    def test_conversion_keeps_plaintext_key_off_disk_and_out_of_arguments_and_logs(self):
        plaintext = b"secret-marker-plaintext-private-key"
        original_password = "secret-marker-original-password"
        captured = []
        def fake_run(args, **kwargs):
            captured.append((args, kwargs))
            if "-export" in args:
                protected = Path(args[args.index("-in") + 1])
                self.assertEqual(protected.read_bytes(), b"synthetic-encrypted-pem")
                self.assertEqual(protected.stat().st_mode & 0o777, 0o600)
                return b"synthetic-encrypted-p12"
            return b"synthetic-encrypted-pem"
        with tempfile.TemporaryDirectory() as directory, \
                mock.patch.object(distribution, "run", side_effect=fake_run):
            root = Path(directory)
            source = root / "original.p12"
            source.write_bytes(b"synthetic-original")
            output = io.StringIO()
            with contextlib.redirect_stdout(output), contextlib.redirect_stderr(output):
                compatible, temporary_password = distribution.compatible_p12(source, root, original_password)
            self.assertEqual(source.read_bytes(), b"synthetic-original")
            self.assertEqual(compatible.read_bytes(), b"synthetic-encrypted-p12")
            self.assertEqual(compatible.stat().st_mode & 0o777, 0o600)
            self.assertNotEqual(temporary_password, original_password)
            self.assertTrue(all(plaintext not in file.read_bytes() for file in root.iterdir()))
            self.assertNotIn("secret-marker", output.getvalue())
        self.assertTrue(all(original_password not in args and temporary_password not in args for args, _ in captured))
        self.assertNotIn("-nomacver", captured[0][0])
        self.assertNotIn("-nodes", captured[0][0])
        self.assertIn("-aes256", captured[0][0])
        self.assertEqual(captured[1][0].count("PBE-SHA1-3DES"), 2)

    def test_rejected_input_does_not_write_a_compatible_file_or_print_subprocess_data(self):
        with tempfile.TemporaryDirectory() as directory, \
                mock.patch.object(distribution, "run", side_effect=RuntimeError("secret-marker-output")):
            root = Path(directory)
            with self.assertRaisesRegex(ValueError, "IOS_DISTRIBUTION_P12_PASSWORD") as error:
                distribution.compatible_p12(root / "original.p12", root, "secret-marker-password")
            self.assertNotIn("secret-marker", str(error.exception))
            self.assertEqual(list(root.iterdir()), [])


class SigningCleanupTests(unittest.TestCase):
    def test_command_error_reports_only_safe_operation_and_classification(self):
        command = ["security", "import", "secret-marker-file", "-P", "secret-marker-password"]
        result = subprocess.CompletedProcess(command, 1, b"secret-marker-key",
                                             b"MAC verification failed secret-marker-private-output")
        with mock.patch.object(distribution.subprocess, "run", return_value=result):
            with self.assertRaises(RuntimeError) as error:
                distribution.run(command, capture=True)
        message = str(error.exception)
        self.assertIn("security import", message)
        self.assertIn("PKCS12_PASSWORD_OR_FORMAT_REJECTED", message)
        self.assertNotIn("secret-marker", message)

    def test_signing_preflight_needs_no_native_build_and_cleans_up_import_failure(self):
        for fail_import in (False, True):
            with self.subTest(fail_import=fail_import), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                data = profiles()
                commands = []

                def fake_run(args, **kwargs):
                    args = [str(a) for a in args]
                    commands.append(args)
                    if args[:2] == ["security", "import"] and fail_import:
                        raise RuntimeError("security import failed")
                    if args[:2] == ["security", "find-identity"]:
                        return (f'1) {IDENTITY} "Apple Distribution: Synthetic"\n').encode()
                    if args[:2] == ["security", "find-certificate"]:
                        return b"synthetic-pem"
                    if args[0] == "openssl":
                        return CERT
                    return b""

                def fake_profile(path):
                    return next(data[b] for b, secret, _ in distribution.TARGETS if path.name.startswith(secret))

                with mock.patch.multiple(distribution, ROOT=root, OUTPUT=root / "output"), \
                        mock.patch.object(distribution, "run", side_effect=fake_run), \
                        mock.patch.object(distribution, "read_profile", side_effect=fake_profile), \
                        mock.patch.object(distribution.subprocess, "run") as cleanup:
                    if fail_import:
                        with self.assertRaisesRegex(RuntimeError, "security import"):
                            distribution.build(dict(settings(), RUNNER_TEMP=str(root)), verify_only=True)
                    else:
                        with contextlib.redirect_stdout(io.StringIO()):
                            distribution.build(dict(settings(), RUNNER_TEMP=str(root)), verify_only=True)
                self.assertFalse((root / "output").exists())
                self.assertFalse(list(root.glob("youngmin-signing-*")))
                self.assertFalse(any(command[0] == "xcodebuild" for command in commands))
                self.assertEqual(len(cleanup.call_args_list), 2)

    def test_failed_archive_restores_keychains_and_removes_profiles_and_licenses(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            generated = root / "ios/NativeCodex/Generated"
            (generated / "iphoneos/arm64").mkdir(parents=True)
            (generated / "iphoneos/arm64/libyoungmin_codex_bridge.a").write_bytes(b"synthetic")
            (generated / "iphoneos/arm64/native-static-libs.txt").write_text("-framework SyntheticNativeFramework -liconv\n")
            (generated / "Codex-LICENSE.txt").write_text("Synthetic license")
            (root / "ios/Youngmin").mkdir()
            data = profiles()
            commands = []

            def fake_run(args, **kwargs):
                args = [str(a) for a in args]
                commands.append(args)
                if args[:4] == ["security", "list-keychains", "-d", "user"] and "-s" not in args:
                    return b'"/original/login.keychain-db"\n'
                if args[0:2] == ["security", "find-identity"]:
                    return (f'1) {IDENTITY} "Apple Distribution: Synthetic"\n').encode()
                if args[0:2] == ["security", "find-certificate"]:
                    return b"synthetic-pem"
                if args[0] == "openssl":
                    return CERT
                if args[0] == "xcodebuild":
                    self.assertIn("Release", args)
                    self.assertIn("CODE_SIGN_STYLE=Manual", args)
                    self.assertIn("YOUNGMIN_NATIVE_LDFLAGS=-lyoungmin_codex_bridge -framework SyntheticNativeFramework -liconv", args)
                    self.assertIn("INFOPLIST_KEY_YoungminCodeModeRuntime=quickjs", args)
                    for bundle, _, variable in distribution.TARGETS:
                        self.assertIn(variable + "=" + data[bundle]["Name"], args)
                    raise RuntimeError("xcodebuild failed")
                return b""

            def fake_profile(path):
                return next(data[b] for b, secret, _ in distribution.TARGETS if path.name.startswith(secret))

            env = dict(settings(), RUNNER_TEMP=str(root))
            with mock.patch.multiple(distribution, ROOT=root, OUTPUT=root / "output"), \
                    mock.patch.object(Path, "home", return_value=root / "home"), \
                    mock.patch.object(distribution, "run", side_effect=fake_run), \
                    mock.patch.object(distribution, "read_profile", side_effect=fake_profile), \
                    mock.patch.object(distribution.subprocess, "run") as cleanup:
                with self.assertRaisesRegex(RuntimeError, "xcodebuild"):
                    distribution.build(env)
            self.assertFalse(list(root.rglob("*.mobileprovision")))
            self.assertFalse(list(root.glob("youngmin-signing-*")))
            self.assertFalse((root / "ios/Youngmin/NativeCodexLicenses").exists())
            self.assertEqual(cleanup.call_args_list[0].args[0],
                             ["security", "list-keychains", "-d", "user", "-s", "/original/login.keychain-db"])
            self.assertEqual(cleanup.call_args_list[1].args[0][:2], ["security", "delete-keychain"])


if __name__ == "__main__":
    unittest.main()
