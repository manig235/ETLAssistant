#!/usr/bin/env python3
"""Check device retention, read-only monitoring, profile reuse and JWT signing offline."""

import base64
import contextlib
import copy
import datetime as dt
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
SPEC = importlib.util.spec_from_file_location("device_sync", ROOT / ".github/scripts/ios-device-sync.py")
sync = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(sync)
TEAM = "ABCDE12345"
CERT = b"synthetic-distribution-certificate"
UDIDS = [f"00008120-{number:016X}" for number in range(1, 7)]


def profiles(devices=None):
    return {bundle: {
        "UUID": f"00000000-0000-0000-0000-{index:012d}", "Name": f"Synthetic {index}",
        "TeamIdentifier": [TEAM], "ApplicationIdentifierPrefix": [TEAM],
        "CreationDate": dt.datetime(2026, 1, 1), "ExpirationDate": dt.datetime(2099, 1, 1),
        "ProvisionedDevices": list(devices or UDIDS[:4]), "DeveloperCertificates": [CERT],
        "Entitlements": {"application-identifier": TEAM + "." + bundle,
                         "com.apple.developer.team-identifier": TEAM, "get-task-allow": False,
                         "com.apple.security.application-groups": [sync.distribution.APP_GROUP]},
    } for index, (bundle, _, _) in enumerate(sync.distribution.TARGETS)}


class FakeApple:
    def __init__(self, initial, registered=None):
        self.profiles = copy.deepcopy(initial)
        self.devices = {sync.normalized(udid): {"id": str(index), "attributes": {"udid": udid, "status": "ENABLED"}}
                        for index, udid in enumerate(registered or UDIDS[:4])}
        self.writes = []

    def listing(self, path):
        if path.startswith("/v1/devices"):
            return list(self.devices.values())
        if path.startswith("/v1/certificates"):
            return [{"id": "cert", "attributes": {"certificateContent": base64.b64encode(CERT).decode()}}]
        if path.startswith("/v1/bundleIds"):
            return [{"id": bundle, "attributes": {"identifier": bundle}} for bundle in self.profiles]
        raise AssertionError(path)

    def call(self, method, path, body):
        self.writes.append((method, path))
        data = body["data"]
        if path == "/v1/devices":
            device = {"id": str(len(self.devices)), "attributes": dict(data["attributes"], status="ENABLED")}
            self.devices[sync.normalized(data["attributes"]["udid"])] = device
            return {"data": device}
        if path == "/v1/profiles":
            bundle = data["relationships"]["bundleId"]["data"]["id"]
            profile = copy.deepcopy(self.profiles[bundle])
            ids = {item["id"] for item in data["relationships"]["devices"]["data"]}
            profile["ProvisionedDevices"] = [d["attributes"]["udid"] for d in self.devices.values() if d["id"] in ids]
            profile["Name"] = data["attributes"]["name"]
            return {"data": {"attributes": {"profileContent": base64.b64encode(plistlib.dumps(profile)).decode()}}}
        raise AssertionError(path)


class DeviceTests(unittest.TestCase):
    def test_retains_four_previous_devices_and_adds_only_two_new_devices(self):
        # Two of Firebase's four registrations already occur in the baseline.
        testers = [{"udid": value} for value in (UDIDS[0], UDIDS[2], UDIDS[4], UDIDS[5])]
        self.assertEqual(sync.expected_devices(profiles(), testers), {sync.normalized(x) for x in UDIDS})

    def test_invalid_identifiers_are_rejected(self):
        for invalid in ["", "$(command)", "https://example.test/", "a" * 100, None]:
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                sync.normalized(invalid)
        self.assertEqual(sync.normalized(UDIDS[0].lower()), sync.normalized(UDIDS[0]))

    def test_sync_registers_missing_devices_and_validates_all_three_profiles(self):
        data = profiles()
        apple = FakeApple(data)
        with tempfile.TemporaryDirectory() as directory, contextlib.redirect_stdout(io.StringIO()), \
                mock.patch.object(sync, "decode_profile", side_effect=plistlib.loads):
            output = Path(directory) / "env"
            count = sync.synchronize(apple, data, {}, CERT, [{"udid": u, "name": "private-tester"} for u in UDIDS[4:]],
                                     {"IOS_TEAM_ID": TEAM, "GITHUB_ENV": str(output)})
            self.assertEqual(count, 6)
            self.assertEqual(apple.writes.count(("POST", "/v1/devices")), 2)
            self.assertEqual(apple.writes.count(("POST", "/v1/profiles")), 3)
            self.assertEqual(len(output.read_text().splitlines()), 3)
            for profile in data.values():
                self.assertEqual(sync.devices_in(profile), {sync.normalized(u) for u in UDIDS})

    def test_reuses_complete_profiles_without_creating_more_apple_resources(self):
        data = profiles()
        apple = FakeApple(data)
        raw = {bundle: plistlib.dumps(profile) for bundle, profile in data.items()}
        with tempfile.TemporaryDirectory() as directory, contextlib.redirect_stdout(io.StringIO()):
            count = sync.synchronize(apple, data, raw, CERT, [{"udid": UDIDS[0]}],
                                     {"IOS_TEAM_ID": TEAM, "GITHUB_ENV": str(Path(directory) / "env")})
        self.assertEqual(count, 4)
        self.assertEqual(apple.writes, [])

    def test_disabled_existing_device_is_not_reenabled_or_removed(self):
        data = profiles()
        apple = FakeApple(data)
        apple.devices[sync.normalized(UDIDS[0])]["attributes"]["status"] = "DISABLED"
        with self.assertRaisesRegex(ValueError, "missing or disabled"):
            sync.synchronize(apple, data, {}, CERT, [], {"IOS_TEAM_ID": TEAM, "GITHUB_ENV": "unused"})
        self.assertEqual(apple.writes, [])

    def test_pagination_cannot_send_apple_token_to_another_origin(self):
        apple = sync.Apple.__new__(sync.Apple)
        apple.token = "secret-marker"
        with mock.patch.object(sync, "http") as request, self.assertRaises(ValueError):
            apple.call("GET", "https://example.test/v1/devices")
        request.assert_not_called()

    def test_check_mode_reports_pending_count_without_writes_or_personal_data(self):
        data = profiles()
        env = {key: "present" for key in ("ASC_KEY_ID", "ASC_ISSUER_ID", "ASC_KEY_P8", "IOS_TEAM_ID", "GITHUB_ENV")
               + tuple(t[1] for t in sync.distribution.TARGETS)}
        output = io.StringIO()
        with mock.patch.dict(os.environ, env, clear=True), mock.patch("sys.argv", ["sync", "check"]), \
                mock.patch.object(sync, "Apple") as apple, mock.patch.object(sync, "firebase_devices", return_value=[
                    {"udid": UDIDS[4], "name": "private-tester@example.test"}]), \
                mock.patch.object(sync, "current_profiles", return_value=(data, {}, CERT)), \
                mock.patch.object(sync, "synchronize") as mutate, contextlib.redirect_stdout(output):
            self.assertEqual(sync.main(), 2)
            apple.return_value.call.assert_not_called()
            mutate.assert_not_called()
        self.assertIn("profile updates: 1", output.getvalue())
        self.assertNotIn(UDIDS[4], output.getvalue())
        self.assertNotIn("private-tester", output.getvalue())

    def test_es256_jwt_uses_a_real_signature_and_jose_encoding(self):
        with tempfile.TemporaryDirectory() as directory:
            key = Path(directory) / "key.pem"
            subprocess.run(["openssl", "genpkey", "-algorithm", "EC", "-pkeyopt", "ec_paramgen_curve:P-256", "-out", str(key)],
                           check=True, capture_output=True)
            token = sync.jwt({"alg": "ES256", "typ": "JWT"}, {"aud": "appstoreconnect-v1"}, key.read_text())
            head, body, signature = token.split(".")
            raw = base64.urlsafe_b64decode(signature + "==")
            self.assertEqual(len(raw), 64)
            integers = []
            for number in (raw[:32], raw[32:]):
                value = number.lstrip(b"\0") or b"\0"
                if value[0] & 128:
                    value = b"\0" + value
                integers.append(bytes([2, len(value)]) + value)
            encoded = b"".join(integers)
            signed = Path(directory) / "signature.der"
            signed.write_bytes(bytes([48, len(encoded)]) + encoded)
            public = Path(directory) / "public.pem"
            result = subprocess.run(["openssl", "pkey", "-in", str(key), "-pubout"], check=True, capture_output=True)
            public.write_bytes(result.stdout)
            result = subprocess.run(["openssl", "dgst", "-sha256", "-verify", str(public), "-signature", str(signed)],
                                    input=(head + "." + body).encode(), capture_output=True)
            self.assertEqual(result.returncode, 0)


if __name__ == "__main__":
    unittest.main()
