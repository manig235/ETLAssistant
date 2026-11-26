#!/usr/bin/env python3
"""Check Firebase tester UDIDs; explicitly sync Apple Ad Hoc profiles before a build.

Print counts only: device identifiers, tester names, credentials and profiles must
never enter public Actions logs, summaries or artifacts.
"""

import argparse
import base64
import datetime as dt
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import plistlib
import re
import subprocess
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request

SPEC = importlib.util.spec_from_file_location("distribution", Path(__file__).with_name("ios-firebase-distribute.py"))
distribution = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(distribution)
LABELS = {"APP": "App", "WIDGETS": "Widgets", "SHARE": "Share"}
APPLE = "https://api.appstoreconnect.apple.com"


def b64url(data):
    return base64.urlsafe_b64encode(data).decode().rstrip("=")


def ecdsa_raw(signature):
    """Convert OpenSSL's ASN.1 ECDSA signature into the JWT ES256 format."""
    def element(pos, tag):
        if pos >= len(signature) or signature[pos] != tag:
            raise ValueError("Invalid ECDSA signature")
        size, pos = signature[pos + 1], pos + 2
        if size & 128:
            length_bytes = size & 127
            size = int.from_bytes(signature[pos:pos + length_bytes], "big")
            pos += length_bytes
        return signature[pos:pos + size], pos + size
    body, end = element(0, 48)
    if end != len(signature):
        raise ValueError("Invalid ECDSA signature length")
    start = len(signature) - len(body)
    r, pos = element(start, 2)
    s, pos = element(pos, 2)
    if pos != end:
        raise ValueError("Invalid ECDSA signature values")
    return b"".join(int.from_bytes(n, "big").to_bytes(32, "big") for n in (r, s))


def jwt(header, claims, private_key):
    message = ".".join(b64url(json.dumps(value, separators=(",", ":")).encode()) for value in (header, claims))
    with tempfile.TemporaryDirectory(prefix="youngmin-device-key-") as directory:
        path = Path(directory) / "key.pem"
        distribution.private_file(path, private_key.encode())
        result = subprocess.run(["openssl", "dgst", "-sha256", "-sign", str(path)],
                                input=message.encode(), capture_output=True)
        if result.returncode:
            raise ValueError("Could not sign device synchronization token")
    signature = ecdsa_raw(result.stdout) if header["alg"] == "ES256" else result.stdout
    return message + "." + b64url(signature)


def http(method, url, *, token=None, body=None, form=None):
    headers = {"Accept": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    data = None
    if body is not None:
        headers["Content-Type"] = "application/json"
        data = json.dumps(body).encode()
    if form is not None:
        headers["Content-Type"] = "application/x-www-form-urlencoded"
        data = urllib.parse.urlencode(form).encode()
    request = urllib.request.Request(url, method=method, headers=headers, data=data)
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        # Apple error details can contain tester names or device identifiers.
        raise ValueError(f"Device API {method} {urllib.parse.urlsplit(url).path} failed (HTTP {error.code})") from None
    except (OSError, ValueError):
        raise ValueError("Device API network or response failure") from None


def normalized(udid):
    if not isinstance(udid, str) or not re.fullmatch(r"(?:[0-9A-Fa-f]{8}-[0-9A-Fa-f]{16}|[0-9A-Fa-f]{40})", udid):
        raise ValueError("Invalid tester device identifier")
    return udid.replace("-", "").upper()


def decode_profile(raw):
    result = subprocess.run(["openssl", "cms", "-verify", "-inform", "DER", "-noverify"],
                            input=raw, capture_output=True)
    if result.returncode:
        raise ValueError("Could not decode an Ad Hoc provisioning profile")
    return plistlib.loads(result.stdout)


def devices_in(profile):
    return {normalized(value) for value in profile.get("ProvisionedDevices", [])}


def expected_devices(profiles, testers):
    # Preserve every previously provisioned device, including devices that have
    # not yet registered through Firebase, and include all newly collected UDIDs.
    result = set().union(*(devices_in(profile) for profile in profiles.values()))
    result.update(normalized(device["udid"]) for device in testers)
    return result


class Apple:
    def __init__(self, env):
        now = int(time.time())
        self.token = jwt({"alg": "ES256", "kid": env["ASC_KEY_ID"], "typ": "JWT"},
                         {"iss": env["ASC_ISSUER_ID"], "iat": now - 30, "exp": now + 600,
                          "aud": "appstoreconnect-v1"}, env["ASC_KEY_P8"])

    def call(self, method, path, body=None):
        url = path if path.startswith("https://") else APPLE + path
        if urllib.parse.urlsplit(url).netloc != "api.appstoreconnect.apple.com":
            raise ValueError("Unexpected Apple pagination origin")
        return http(method, url, token=self.token, body=body)

    def listing(self, path):
        result = []
        while path:
            page = self.call("GET", path)
            result.extend(page.get("data", []))
            path = page.get("links", {}).get("next")
        return result


def firebase_devices(env):
    distribution.firebase_config(env)
    account = json.loads(env["FIREBASE_SERVICE_ACCOUNT_JSON"])
    now = int(time.time())
    assertion = jwt({"alg": "RS256", "typ": "JWT"},
                    {"iss": account["client_email"], "scope": "https://www.googleapis.com/auth/cloud-platform",
                     "aud": "https://oauth2.googleapis.com/token", "iat": now - 30, "exp": now + 3600},
                    account["private_key"])
    token = http("POST", "https://oauth2.googleapis.com/token", form={
        "grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer", "assertion": assertion})["access_token"]
    project = env["FIREBASE_APP_ID"].split(":")[1]
    response = http("GET", f"https://firebaseappdistribution.googleapis.com/v1alpha/projects/{project}/testers:udids", token=token)
    devices = response.get("testerUdids", [])
    for device in devices:
        normalized(device["udid"])
    return devices


def current_profiles(apple, env):
    team = env["IOS_TEAM_ID"]
    original, raw_profiles = {}, {}
    for bundle, secret, _ in distribution.TARGETS:
        raw_profiles[bundle] = base64.b64decode(env[secret], validate=True)
        original[bundle] = decode_profile(raw_profiles[bundle])
    certificate = original[distribution.APP_BUNDLE]["DeveloperCertificates"][0]
    distribution.validate_profile_set(original, team, certificate)
    # Repository Secrets remain a recovery baseline. Reuse the most recent
    # managed profiles from Apple so scheduled checks see the last synchronized set.
    for record in apple.listing("/v1/profiles?limit=200"):
        attributes = record["attributes"]
        if (attributes.get("profileState") != "ACTIVE"
                or not attributes.get("name", "").startswith("Youngmin Auto ")):
            continue
        raw = base64.b64decode(attributes["profileContent"], validate=True)
        profile = decode_profile(raw)
        for bundle in original:
            try:
                distribution.validate_profile(profile, bundle, team, certificate)
            except ValueError:
                continue
            if profile["CreationDate"] >= original[bundle]["CreationDate"]:
                original[bundle], raw_profiles[bundle] = profile, raw
            break
    return original, raw_profiles, certificate


def synchronize(apple, profiles, raw_profiles, certificate, testers, env):
    expected = expected_devices(profiles, testers)
    registered = {normalized(d["attributes"]["udid"]): d for d in apple.listing("/v1/devices?filter%5Bplatform%5D=IOS&limit=200")}
    for tester in testers:
        udid = normalized(tester["udid"])
        if udid in registered:
            continue
        record = apple.call("POST", "/v1/devices", {"data": {"type": "devices", "attributes": {
            "name": ("Firebase " + tester.get("name", "iOS tester"))[:50],
            "udid": tester["udid"], "platform": "IOS"}}})["data"]
        registered[udid] = record
    if any(value not in registered or registered[value]["attributes"].get("status") != "ENABLED" for value in expected):
        raise ValueError("A required Apple device is missing or disabled; existing device registrations were preserved")
    certificate_ids = [item["id"] for item in apple.listing("/v1/certificates?limit=200")
                       if base64.b64decode(item["attributes"]["certificateContent"]) == certificate]
    if len(certificate_ids) != 1:
        raise ValueError("Could not uniquely match the existing distribution certificate")
    bundle_ids = {item["attributes"]["identifier"]: item["id"] for item in apple.listing("/v1/bundleIds?limit=200")}
    for bundle, secret, _ in distribution.TARGETS:
        if devices_in(profiles[bundle]) == expected:
            continue
        suffix = secret.removeprefix("IOS_").removesuffix("_PROFILE_BASE64")
        name = "Youngmin Auto " + LABELS[suffix] + " " + dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
        data = {"type": "profiles", "attributes": {"name": name, "profileType": "IOS_APP_ADHOC"},
                "relationships": {"bundleId": {"data": {"type": "bundleIds", "id": bundle_ids[bundle]}},
                                  "certificates": {"data": [{"type": "certificates", "id": value} for value in certificate_ids]},
                                  "devices": {"data": [{"type": "devices", "id": registered[value]["id"]} for value in sorted(expected)]}}}
        record = apple.call("POST", "/v1/profiles", {"data": data})["data"]
        raw_profiles[bundle] = base64.b64decode(record["attributes"]["profileContent"], validate=True)
        profiles[bundle] = decode_profile(raw_profiles[bundle])
    distribution.validate_profile_set(profiles, env["IOS_TEAM_ID"], certificate)
    if any(devices_in(profile) != expected for profile in profiles.values()):
        raise ValueError("Generated profiles do not cover the complete device set")
    if not env.get("GITHUB_ENV"):
        raise ValueError("Profile synchronization must run inside the signing workflow")
    with Path(env["GITHUB_ENV"]).open("a") as output:
        for bundle, secret, _ in distribution.TARGETS:
            encoded = base64.b64encode(raw_profiles[bundle]).decode()
            print("::add-mask::" + encoded)
            output.write(secret + "=" + encoded + "\n")
    return len(expected)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("check", "sync"))
    args = parser.parse_args()
    env = os.environ
    try:
        distribution.require(("ASC_KEY_ID", "ASC_ISSUER_ID", "ASC_KEY_P8", "IOS_TEAM_ID", "GITHUB_ENV")
                             + tuple(target[1] for target in distribution.TARGETS), env)
        apple = Apple(env)
        testers = firebase_devices(env)
        profiles, raw_profiles, certificate = current_profiles(apple, env)
        expected = expected_devices(profiles, testers)
        pending = set().union(*(expected - devices_in(profile) for profile in profiles.values()))
        print(f"Firebase registered devices: {len(testers)}; devices needing profile updates: {len(pending)}")
        if args.command == "sync":
            count = synchronize(apple, profiles, raw_profiles, certificate, testers, env)
            print(f"All three Ad Hoc profiles verified with {count} devices.")
        if env.get("GITHUB_STEP_SUMMARY"):
            with Path(env["GITHUB_STEP_SUMMARY"]).open("a") as summary:
                summary.write(f"\n### iOS tester devices\n- Firebase registrations: {len(testers)}\n"
                              f"- Devices needing profile updates: {len(pending)}\n")
                if args.command == "sync":
                    summary.write(f"- Synchronized and verified all three profiles: {count} devices each\n")
                elif pending:
                    summary.write("- Run **iOS Ad Hoc Firebase** with **sync_devices** and **publish** enabled.\n")
        if args.command == "check" and pending:
            print("::error::New iOS devices are waiting. Run iOS Ad Hoc Firebase with device sync and publishing enabled.")
            return 2
    except (ValueError, KeyError, OSError, plistlib.InvalidFileException) as error:
        # Do not interpolate key values or upstream response bodies here.
        message = str(error) if isinstance(error, ValueError) else type(error).__name__
        print("Device synchronization failed: " + message)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
