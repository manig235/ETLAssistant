#!/usr/bin/env python3
"""Package the official Montage/Electron UI and Kotlin backend into a Windows x64 ZIP.

Run ./gradlew -p desktop test stageWindows and npm --prefix desktop/ui run build first.
Archives and Codex binaries are SHA-256 pinned. Nothing is uploaded or published.
"""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import shutil
import struct
import tempfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
VERSION = "0.5.1"
CODEX_VERSION = "rust-v0.170.0"
ELECTRON_VERSION = "45.5.0"
ELECTRON_HASH = "20155c8b8aa5b1f575f3757bdf21490e0bac2f19a65a26ce9ba99bcf9f2c69bd"
ASSETS = (
    ("codex-x86_64-pc-windows-msvc.exe", "b1634415b6ecb87fea80691580dfe49f83fd46f0ad93f128ba029604d7faa158",
     "8d06dc231dba649c7d48d3eecab239c452a1ac498c41dd6492a6f301b90a22ce", "codex.exe"),
    ("codex-code-mode-host-x86_64-pc-windows-msvc.exe", "030b40d53eb7fb86ee45e6655c85cef651165b228307034f5f67f5a1e9d3a778",
     "4dd22666894cbad119023ed64c973105050146f59847bacb8094dfe456efbb62", "codex-code-mode-host.exe"),
)
JRE_NAME = "OpenJDK17U-jre_x64_windows_hotspot_17.0.21.1_2.zip"
JRE_URL = "https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.21.1%2B2/" + JRE_NAME
JRE_HASH = "739d8f2fd286dc2736d55cc8c9260980baefd275ad48cda242335bb0f08e0795"


def sha256(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def fetch(url: str, expected: str, cache: Path) -> Path:
    target = cache / url.rsplit("/", 1)[-1]
    if target.is_file() and sha256(target) == expected:
        return target
    cache.mkdir(parents=True, exist_ok=True)
    partial = target.with_name(target.name + ".part")
    print("Downloading " + target.name, flush=True)
    try:
        with urllib.request.urlopen(url, timeout=60) as response, partial.open("wb") as stream:
            shutil.copyfileobj(response, stream)
        if sha256(partial) != expected:
            raise RuntimeError("SHA-256 mismatch: " + target.name)
        partial.replace(target)
    finally:
        partial.unlink(missing_ok=True)
    return target


def safe_extract_zip(archive: Path, destination: Path, strip_root: bool = False) -> None:
    with zipfile.ZipFile(archive) as source:
        for entry in source.infolist():
            path = PurePosixPath(entry.filename)
            if path.is_absolute() or ".." in path.parts or "\\" in entry.filename or ":" in entry.filename:
                raise RuntimeError("Unsafe ZIP member")
            parts = path.parts[1:] if strip_root else path.parts
            if not parts:
                continue
            output = destination.joinpath(*parts)
            if entry.is_dir():
                output.mkdir(parents=True, exist_ok=True)
            else:
                output.parent.mkdir(parents=True, exist_ok=True)
                with source.open(entry) as stream, output.open("wb") as writer:
                    shutil.copyfileobj(stream, writer)


def package(cache: Path) -> Path:
    libs = ROOT / "build/windows-stage/lib"
    ui = ROOT / "ui/dist"
    if not list(libs.glob(f"youngmini-desktop-{VERSION}.jar")):
        raise RuntimeError("Run ./gradlew -p desktop stageWindows first")
    if not (ui / "index.html").is_file() or not (ui / "robot.png").is_file():
        raise RuntimeError("Run npm --prefix desktop/ui ci and npm --prefix desktop/ui run build first")
    distribution = ROOT / "dist"
    distribution.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="youngmini-package-") as work:
        app = Path(work) / "YoungMini"
        electron = fetch(f"https://github.com/electron/electron/releases/download/v{ELECTRON_VERSION}/electron-v{ELECTRON_VERSION}-win32-x64.zip", ELECTRON_HASH, cache)
        safe_extract_zip(electron, app)
        (app / "electron.exe").rename(app / "YoungMini.exe")
        (app / "resources/default_app.asar").unlink(missing_ok=True)
        binary = (app / "YoungMini.exe").read_bytes()
        pe = struct.unpack_from("<I", binary, 0x3C)[0]
        assert struct.unpack_from("<H", binary, pe + 4)[0] == 0x8664 and struct.unpack_from("<H", binary, pe + 24 + 68)[0] == 2
        shell = app / "resources/app"
        shutil.copytree(ROOT / "ui/electron", shell / "electron")
        shutil.copytree(ui, shell / "dist")
        (shell / "package.json").write_text(json.dumps({"name": "youngmini", "productName": "영민이", "version": VERSION, "main": "electron/main.cjs"}, ensure_ascii=False), encoding="utf-8")
        shutil.copytree(libs, app / "lib")
        runtime = app / "runtime"
        runtime.mkdir()
        receipt = {"codex": CODEX_VERSION, "jre": "Temurin 17.0.21.1+2", "electron": ELECTRON_VERSION, "montage": "@montage-ui/core 4.4.0", "binaries": {}}
        for name, archive_hash, binary_hash, installed in ASSETS:
            archive = fetch(f"https://github.com/openai/codex/releases/download/{CODEX_VERSION}/{name}.zip", archive_hash, cache)
            with zipfile.ZipFile(archive) as source:
                entry = next(entry for entry in source.infolist() if PurePosixPath(entry.filename).name == name)
                with source.open(entry) as reader, (runtime / installed).open("wb") as writer:
                    shutil.copyfileobj(reader, writer)
            if sha256(runtime / installed) != binary_hash:
                raise RuntimeError("Runtime binary hash mismatch")
            receipt["binaries"][installed] = binary_hash
        safe_extract_zip(fetch(JRE_URL, JRE_HASH, cache), app / "jre", strip_root=True)
        assert (app / "jre/bin/java.exe").is_file()
        for name in ("README.md", "THIRD_PARTY_NOTICES.md", "PRIVACY.md", "VALIDATION.md", "FEATURE_PARITY.md"):
            shutil.copyfile(ROOT / name, app / name)
        shutil.copytree(ROOT / "licenses", app / "licenses")
        # Carry the upstream license texts for all bundled runtime npm dependencies.
        lock = json.loads((ROOT / "ui/package-lock.json").read_text())
        for location, info in lock.get("packages", {}).items():
            if not location.startswith("node_modules/") or info.get("dev"):
                continue
            module = ROOT / "ui" / location
            destination = app / "licenses/npm" / location.removeprefix("node_modules/").replace("/", "_")
            for pattern in ("LICENSE*", "LICENCE*", "COPYING*", "NOTICE*"):
                for notice in module.glob(pattern):
                    if notice.is_file():
                        destination.mkdir(parents=True, exist_ok=True)
                        shutil.copyfile(notice, destination / notice.name)
        (runtime / "receipt.json").write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
        manifest = {str(p.relative_to(app)).replace("\\", "/"): sha256(p) for p in sorted(app.rglob("*")) if p.is_file()}
        (app / "SHA256SUMS.json").write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        output = distribution / f"youngmin-windows-x64-v{VERSION}.zip"
        with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
            for p in sorted(app.rglob("*")):
                if p.is_file():
                    archive.write(p, p.relative_to(app.parent))
        digest = sha256(output)
        output.with_suffix(".zip.sha256").write_text(f"{digest}  {output.name}\n", encoding="ascii")
        print(f"Created {output} ({output.stat().st_size / 1024 / 1024:.1f} MB)\nSHA-256 {digest}", flush=True)
        return output


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cache", type=Path, default=ROOT / "build/download-cache")
    package(parser.parse_args().cache.resolve())
