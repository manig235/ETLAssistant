#!/usr/bin/env python3
"""Build a per-user offline Windows installer from the verified portable ZIP. No publishing."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

from package_windows import ROOT, VERSION, safe_extract_zip, sha256
from verify_package import verify


def package():
    print("Verifying the portable package before building the installer...", flush=True)
    source = ROOT / "dist" / f"youngmin-windows-x64-v{VERSION}.zip"
    verify(source)
    with tempfile.TemporaryDirectory(prefix="installer-", dir=ROOT / "build") as work:
        stage = Path(work)
        safe_extract_zip(source, stage)
        env = dict(os.environ, CSC_IDENTITY_AUTO_DISCOVERY="false")
        env.setdefault("ELECTRON_BUILDER_CACHE", str(ROOT / "build/installer-cache"))
        subprocess.run([
            shutil.which("node") or "node", str(ROOT / "ui/node_modules/electron-builder/cli.js"),
            "--win", "nsis", "--x64", "--prepackaged", str(stage / "YoungMini"),
            "--config", str(ROOT / "installer/electron-builder.json"), "--publish", "never",
        ], cwd=ROOT / "ui", env=env, check=True)
    output = ROOT / "dist" / f"youngmin-windows-x64-setup-v{VERSION}.exe"
    output.with_suffix(".exe.sha256").write_text(f"{sha256(output)}  {output.name}\n", encoding="ascii")
    print(f"Created installer: {output}")


if __name__ == "__main__":
    package()
