#!/usr/bin/env python3
"""Exact cache of the finished embedded Codex library (not the Rust build tree).

key      Print the exact library key and the shared compilation cache key.
build    Compile with ios-codex-probe.sh, then stage the files for the library cache.
install  Verify a restored library against its manifest and install it for Xcode.

The library key covers every native input file (content hash), the pinned
Codex/Rust revisions, the target, the build flags and the Xcode/SDK version.
A restored library is installed only when its manifest matches this key and
every file matches its recorded SHA-256.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
PROBE = ROOT / ".github/scripts/ios-codex-probe.sh"
GENERATED = ROOT / "ios/NativeCodex/Generated"
STAGE = ROOT / "ios-codex-lib"
MANIFEST = "manifest.json"
SCHEMA = 1
LIB_PREFIX = f"ios-codex-lib-v{SCHEMA}"
# One prefix for the large Cargo target cache, shared by every workflow.
BUILD_PREFIX = "ios-codex-build-v1"
# Everything copied into, or used to patch, the Cargo workspace.
INPUTS = (
    ".github/scripts/ios-codex-probe.sh",
    ".github/scripts/prepare-ios-codex.py",
    "ios/NativeCodex/bridge",
    "ios/NativeCodex/javascript",
    "ios/NativeCodex/code-mode-check",
    "ios/NativeCodex/native-static-libs.txt",
)
# Cargo dev-small profile overrides. Workflows with identical flags share a key.
PROFILES = {
    # Quick device test: dev-small as upstream defines it (opt-level 0, no debug
    # info, debug assertions and overflow checks on).
    "fast": {"CODEX_NATIVE_OPT_LEVEL": "0"},
    # Optimized test IPA and Ad Hoc distribution.
    "release": {
        "CODEX_NATIVE_OPT_LEVEL": "1",
        "CARGO_PROFILE_DEV_SMALL_DEBUG": "0",
        "CARGO_PROFILE_DEV_SMALL_DEBUG_ASSERTIONS": "false",
        "CARGO_PROFILE_DEV_SMALL_OVERFLOW_CHECKS": "false",
    },
}
# Read-only fallbacks to the per-workflow caches used before this scheme.
LEGACY_BUILD_PREFIXES = {
    "fast": "ios-codex-170-minimal-{arch}-device-dev0-build-",
    "release": "ios-codex-170-distribution-{arch}-",
}
DEVICE = {"platform": "device", "sdk": "iphoneos", "arch": "arm64", "target": "aarch64-apple-ios"}
LIBRARY = f"{DEVICE['sdk']}/{DEVICE['arch']}/libyoungmin_codex_bridge.a"
SYSTEM_LIBS = f"{DEVICE['sdk']}/{DEVICE['arch']}/native-static-libs.txt"
REQUIRED = (LIBRARY, SYSTEM_LIBS, "Codex-LICENSE.txt", "MXC-LICENSE.txt", "QuickJS-LICENSE.txt")
OPTIONAL = ("Codex-NOTICE.txt",)


def sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as stream:
        for block in iter(lambda: stream.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def input_files(root=ROOT):
    listed = subprocess.run(["git", "-C", str(root), "ls-files", "-z", "--", *INPUTS],
                            check=True, stdout=subprocess.PIPE).stdout.decode()
    files = sorted(name for name in listed.split("\0") if name)
    for required in INPUTS:
        if not any(name == required or name.startswith(required + "/") for name in files):
            raise SystemExit(f"Native input is missing from the checkout: {required}")
    return {name: sha256(root / name) for name in files}


def pinned(script_text):
    values = {}
    for name in ("REVISION", "ARCHIVE_SHA256", "TOOLCHAIN", "V8_REVISION", "MXC_REVISION"):
        match = re.search(rf"^{name}=([0-9A-Za-z._-]+)$", script_text, re.M)
        if match is None:
            raise SystemExit(f"ios-codex-probe.sh no longer pins {name}")
        values[name.lower()] = match[1]
    return values


def run_text(*args):
    return subprocess.run(args, check=True, stdout=subprocess.PIPE, text=True).stdout.strip()


def toolchain():
    return {
        "xcode": " ".join(run_text("xcodebuild", "-version").split()),
        "sdk_version": run_text("xcrun", "--sdk", DEVICE["sdk"], "--show-sdk-version"),
        "sdk_build": run_text("xcrun", "--sdk", DEVICE["sdk"], "--show-sdk-build-version"),
    }


def runner_arch():
    return {"x86_64": "X64", "amd64": "X64", "arm64": "ARM64", "aarch64": "ARM64"}.get(platform.machine().lower(), platform.machine())


def key_inputs(profile, root=ROOT, tools=None):
    if profile not in PROFILES:
        raise SystemExit(f"Unknown native profile: {profile}")
    return {
        "schema": SCHEMA,
        "profile": profile,
        "flags": PROFILES[profile],
        **DEVICE,
        "pinned": pinned((root / PROBE.relative_to(ROOT)).read_text()),
        "toolchain": tools if tools is not None else toolchain(),
        "inputs": input_files(root),
    }


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def keys(inputs, arch):
    readable = re.sub(r"[^0-9A-Za-z.]+", "", inputs["toolchain"]["sdk_build"])
    lib = (f"{LIB_PREFIX}-{inputs['sdk']}-{inputs['arch']}-{inputs['profile']}"
           f"-rust{inputs['pinned']['toolchain']}-sdk{readable}-{digest(inputs)[:40]}")
    # The Cargo target tree is not tied to the Xcode build. A mismatch there
    # only means the exact library marker differs and Cargo runs again.
    compile_inputs = {k: v for k, v in inputs.items() if k != "toolchain"}
    build_prefix = f"{BUILD_PREFIX}-{arch}-{inputs['sdk']}-{inputs['arch']}-{inputs['profile']}-"
    return {
        "key": lib,
        "build-key": build_prefix + digest(compile_inputs)[:40],
        "build-prefix": build_prefix,
        "legacy-build-prefix": LEGACY_BUILD_PREFIXES[inputs["profile"]].format(arch=arch),
    }


def write_outputs(values):
    output = os.environ.get("GITHUB_OUTPUT")
    lines = "".join(f"{name}={value}\n" for name, value in values.items())
    if output:
        with open(output, "a") as stream:
            stream.write(lines)
    sys.stdout.write(lines)


def files_to_stage(generated):
    names = list(REQUIRED) + [name for name in OPTIONAL if (generated / name).is_file()]
    for name in REQUIRED:
        if not (generated / name).is_file() or (generated / name).stat().st_size == 0:
            raise SystemExit(f"Native build did not produce {name}")
    return names


def stage(inputs, key, generated=GENERATED, stage_dir=STAGE, built=None):
    if stage_dir.exists():
        shutil.rmtree(stage_dir)
    (stage_dir / "files").mkdir(parents=True)
    hashes = {}
    for name in files_to_stage(generated):
        target = stage_dir / "files" / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(generated / name, target)
        hashes[name] = sha256(target)
    manifest = {"key": key, "key_inputs": inputs, "files": hashes, "built": built or {}}
    (stage_dir / MANIFEST).write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n")
    return manifest


def verify(inputs, key, stage_dir=STAGE):
    try:
        manifest = json.loads((stage_dir / MANIFEST).read_text())
    except (OSError, ValueError):
        raise SystemExit("Cached native library has no readable manifest") from None
    if manifest.get("key") != key or manifest.get("key_inputs") != inputs:
        raise SystemExit("Cached native library was built from different inputs, flags or toolchain")
    files = manifest.get("files")
    if not isinstance(files, dict) or not set(REQUIRED) <= set(files) or not set(files) <= set(REQUIRED + OPTIONAL):
        raise SystemExit("Cached native library manifest lists an unexpected file set")
    present = sorted(str(p.relative_to(stage_dir / "files")) for p in (stage_dir / "files").rglob("*") if not p.is_dir())
    if present != sorted(files):
        raise SystemExit("Cached native library contains files not covered by its manifest")
    for name, expected in files.items():
        path = stage_dir / "files" / name
        if path.is_symlink() or sha256(path) != expected:
            raise SystemExit(f"Cached native library file does not match its manifest: {name}")
    if not (stage_dir / "files" / SYSTEM_LIBS).read_text().strip():
        raise SystemExit("Cached native library has no system library flags")
    return manifest


def install(manifest, generated=GENERATED, stage_dir=STAGE):
    for name in manifest["files"]:
        target = generated / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(stage_dir / "files" / name, target)
        if sha256(target) != manifest["files"][name]:
            raise SystemExit(f"Installed native library file changed while copying: {name}")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("command", choices=("key", "build", "install"))
    parser.add_argument("--profile", required=True, choices=sorted(PROFILES))
    parser.add_argument("--key", help="expected library key (build, install)")
    args = parser.parse_args()
    inputs = key_inputs(args.profile)
    computed = keys(inputs, runner_arch())
    if args.command == "key":
        write_outputs(computed)
        return
    if args.key != computed["key"]:
        raise SystemExit("Library key changed during the job; refusing to mix native builds")
    if args.command == "install":
        manifest = verify(inputs, args.key)
        install(manifest)
        print(f"Installed the cached native Codex library ({manifest['files'][LIBRARY][:12]}…) without compiling.")
        if os.environ.get("GITHUB_STEP_SUMMARY"):
            with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as summary:
                summary.write(f"Reused native Codex library `{args.key}` (built by run {manifest['built'].get('run_id', '?')}).\n")
        return
    env = dict(os.environ, CODEX_PROBE_COMPONENT="bridge", CODEX_PROBE_OPERATION="build",
               CODEX_PROBE_PLATFORM=DEVICE["platform"], CODEX_REUSE_COMPILED_LIBRARY="true",
               CODEX_NATIVE_CACHE_KEY=args.key, **PROFILES[args.profile])
    subprocess.run(["bash", str(PROBE)], check=True, env=env)
    rustc = run_text("rustc", "+" + inputs["pinned"]["toolchain"], "--version")
    built = {"run_id": os.environ.get("GITHUB_RUN_ID", ""), "run_attempt": os.environ.get("GITHUB_RUN_ATTEMPT", ""),
             "workflow": os.environ.get("GITHUB_WORKFLOW", ""), "commit": os.environ.get("GITHUB_SHA", ""), "rustc": rustc}
    manifest = stage(inputs, args.key, built=built)
    verify(inputs, args.key)
    size = (STAGE / "files" / LIBRARY).stat().st_size
    print(f"Staged native Codex library for the exact cache: {size / 1e6:.1f} MB, sha256 {manifest['files'][LIBRARY][:12]}…")


if __name__ == "__main__":
    main()
