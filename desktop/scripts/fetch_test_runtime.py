#!/usr/bin/env python3
"""Fetch the pinned Linux x64 runtime for credential-free integration tests."""
import os
from pathlib import Path
import shutil
import tarfile

from package_windows import fetch

ROOT = Path(__file__).resolve().parents[1]
runtime = ROOT / "build/test-runtime"
runtime.mkdir(parents=True, exist_ok=True)
for name, digest, installed in (
    ("codex-x86_64-unknown-linux-musl", "d054916ccd28a53820376b8d2f7c875184ca8f40fcd44bbabeb8ee240ce247f5", "codex"),
    ("codex-code-mode-host-x86_64-unknown-linux-musl", "18ab29d970586890085c3eba1d55068fd66262154855e51a54f3708522fa4dc5", "codex-code-mode-host"),
):
    archive = fetch(f"https://github.com/openai/codex/releases/download/rust-v0.170.0/{name}.tar.gz", digest, ROOT / "build/download-cache")
    with tarfile.open(archive) as source:
        entry = next(entry for entry in source if Path(entry.name).name == name and entry.isfile())
        with source.extractfile(entry) as reader, (runtime / installed).open("wb") as writer:
            shutil.copyfileobj(reader, writer)
    os.chmod(runtime / installed, 0o755)
print(runtime / "codex")
