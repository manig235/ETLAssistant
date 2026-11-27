#!/usr/bin/env python3
"""Give exported XCTest screenshots readable names and collect App Store sizes.

Reads `<attachments>/manifest.json` written by `xcresulttool export attachments`,
copies each PNG to `<out>/screenshots/<name>.png`, writes `<out>/screenshots.json`
with pixel sizes, and copies 13-inch iPad App Store sizes (2064x2752, 2048x2732
and their landscape forms) to `<out>/appstore-ipad-13/`.

XCTest stores iPad landscape screenshots as portrait pixels plus an EXIF orientation
tag. The copies under `appstore-ipad-13/` are made upright (real landscape pixels,
RGB, no metadata) with `ios-upright-png.swift` on macOS; if that is not possible the
rotated file is left out of the App Store folder.
"""
from __future__ import annotations

import json
import re
import shutil
import struct
import subprocess
import sys
import tempfile
from pathlib import Path

APP_STORE_IPAD_13 = {(2064, 2752), (2752, 2064), (2048, 2732), (2732, 2048)}


def png_size(path: Path) -> tuple[int, int]:
    with path.open("rb") as stream:
        header = stream.read(24)
    if header[:8] != b"\x89PNG\r\n\x1a\n":
        return (0, 0)
    return struct.unpack(">II", header[16:24])


def png_orientation(path: Path) -> int:
    """EXIF orientation from a PNG eXIf chunk (1 = upright)."""
    data = path.read_bytes()
    offset = 8
    while offset + 8 <= len(data):
        length, kind = struct.unpack(">I4s", data[offset:offset + 8])
        if kind == b"eXIf":
            exif = data[offset + 8:offset + 8 + length]
            endian = ">" if exif[:2] == b"MM" else "<"
            (ifd,) = struct.unpack(endian + "I", exif[4:8])
            (count,) = struct.unpack(endian + "H", exif[ifd:ifd + 2])
            for index in range(count):
                entry = exif[ifd + 2 + index * 12:ifd + 14 + index * 12]
                tag, _, _ = struct.unpack(endian + "HHI", entry[:8])
                if tag == 0x0112:
                    return struct.unpack(endian + "H", entry[8:10])[0]
            return 1
        if kind == b"IDAT":
            return 1
        offset += 12 + length
    return 1


def upright_tool() -> Path | None:
    if sys.platform != "darwin":
        return None
    source = Path(__file__).with_name("ios-upright-png.swift")
    binary = Path(tempfile.gettempdir()) / "ios-upright-png"
    try:
        subprocess.run(["xcrun", "swiftc", "-O", str(source), "-o", str(binary)], check=True)
    except (OSError, subprocess.CalledProcessError) as error:
        print(f"Cannot build upright tool: {error}")
        return None
    return binary


def main() -> int:
    attachments, out = Path(sys.argv[1]), Path(sys.argv[2])
    manifest = attachments / "manifest.json"
    if not manifest.is_file():
        print("No attachments manifest; nothing to export.")
        return 0
    shots = out / "screenshots"
    store = out / "appstore-ipad-13"
    shots.mkdir(parents=True, exist_ok=True)
    summary = []
    tool = None
    tool_checked = False
    for test in json.loads(manifest.read_text()):
        for item in test.get("attachments", []):
            source = attachments / item["exportedFileName"]
            if source.suffix.lower() != ".png" or not source.is_file():
                continue
            name = re.sub(r"_\d+_[0-9A-Fa-f-]+\.png$", ".png", item["suggestedHumanReadableName"])
            name = re.sub(r"[^\w.\-]", "_", name)
            target = shots / name
            shutil.copyfile(source, target)
            width, height = png_size(target)
            orientation = png_orientation(target)
            entry = {"name": name, "device": item.get("deviceName"), "width": width, "height": height,
                     "exifOrientation": orientation}
            if (width, height) in APP_STORE_IPAD_13:
                store.mkdir(exist_ok=True)
                if orientation == 1:
                    shutil.copyfile(target, store / name)
                    entry["appStoreIPad13"] = True
                else:
                    if not tool_checked:
                        tool, tool_checked = upright_tool(), True
                    result = subprocess.run([str(tool), str(target), str(store / name)]) if tool else None
                    size = png_size(store / name) if result and result.returncode == 0 else (0, 0)
                    if size in APP_STORE_IPAD_13 and png_orientation(store / name) == 1:
                        entry["appStoreIPad13"] = True
                        entry["appStoreSize"] = list(size)
                    else:
                        (store / name).unlink(missing_ok=True)
            summary.append(entry)
    summary.sort(key=lambda entry: entry["name"])
    (out / "screenshots.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n")
    for entry in summary:
        flag = " (App Store 13-inch iPad)" if entry.get("appStoreIPad13") else ""
        turn = f" orientation={entry['exifOrientation']}" if entry["exifOrientation"] != 1 else ""
        print(f"{entry['name']}: {entry['width']}x{entry['height']}{turn}{flag}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
