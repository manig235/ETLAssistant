#!/usr/bin/env python3
"""Verify the complete Windows distribution, native shell, UI and backend classes."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import struct
import zipfile


def verify(path: Path) -> None:
    with path.open("rb") as stream:
        digest = hashlib.file_digest(stream, "sha256").hexdigest()
    expected = path.with_suffix(".zip.sha256").read_text().split()[0]
    assert digest == expected, "Distribution checksum mismatch"
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        assert len(names) == len(set(names)), "Duplicate ZIP entries"
        manifest = json.loads(archive.read("YoungMini/SHA256SUMS.json"))
        for relative, expected in manifest.items():
            with archive.open("YoungMini/" + relative) as stream:
                assert hashlib.file_digest(stream, "sha256").hexdigest() == expected, "File checksum mismatch: " + relative
        binary = archive.read("YoungMini/YoungMini.exe")
        pe = struct.unpack_from("<I", binary, 0x3C)[0]
        assert binary[:2] == b"MZ" and struct.unpack_from("<H", binary, pe + 4)[0] == 0x8664
        assert struct.unpack_from("<H", binary, pe + 24 + 68)[0] == 2, "Launcher opens a console window"
        required = {
            "kr/snu/youngmini/desktop/BridgeMainKt.class", "kr/snu/youngmini/desktop/ChatSession.class",
            "kr/snu/youngmini/desktop/DesktopEngine.class", "kr/snu/youngmini/desktop/Agenda.class",
            "kr/snu/youngmini/desktop/ProjectStore.class", "kr/snu/youngmini/desktop/PdfJobs.class", "kr/snu/youngmini/desktop/ToolContexts.class", "kr/snu/etlassistant/mcp/McpServer.class",
            "org/apache/pdfbox/Loader.class", "com/sun/jna/platform/win32/Crypt32Util.class",
        }
        classes = set()
        for name in names:
            if name.startswith("YoungMini/lib/") and name.endswith(".jar"):
                with zipfile.ZipFile(io.BytesIO(archive.read(name))) as jar:
                    classes.update(jar.namelist())
        assert not required - classes, "Missing application classes: " + repr(required - classes)
        assert {"YoungMini/d3dcompiler_47.dll", "YoungMini/dxcompiler.dll", "YoungMini/vk_swiftshader.dll", "YoungMini/vulkan-1.dll"}.issubset(names)
        assert "YoungMini/resources/app/dist/index.html" in names
        assert "YoungMini/resources/app/dist/fonts/PretendardVariable.woff2" in names
        assert "YoungMini/resources/app/electron/main.cjs" in names
        startup = json.loads(archive.read("YoungMini/resources/app/electron/initial-state.json"))
        assert startup["projects"][0]["id"] == "integrated" and startup["agenda"]["notices"] == []
        assert any("KaTeX_Main-Regular-" in n and n.endswith(".woff2") for n in names)
        assert any("pdf.worker.min-" in n and n.endswith(".mjs") for n in names), "Missing offline PDF.js worker"
        entry = json.loads(archive.read("YoungMini/resources/app/package.json"))
        assert entry["main"] == "electron/main.cjs" and entry["version"] == "0.3.1"
        assert "YoungMini/jre/bin/javaw.exe" in names
        assert "YoungMini/runtime/codex.exe" in names
        assert "YoungMini/runtime/codex-code-mode-host.exe" in names
        assert "YoungMini/licenses/Codex-LICENSE.txt" in names
    print(f"PASS: {len(manifest)} files verified; Windows GUI shell, bundled Montage/Markdown UI, offline fonts, JRE, Codex and backend classes present")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("zip", type=Path)
    verify(parser.parse_args().zip)
