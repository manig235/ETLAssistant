#!/usr/bin/env python3
"""Give exported XCTest screenshots readable names and collect App Store sizes.

Reads `<attachments>/manifest.json` written by `xcresulttool export attachments`,
copies each PNG to `<out>/screenshots/<name>.png`, writes `<out>/screenshots.json`
with pixel sizes, and copies 13-inch iPad App Store sizes (2064x2752, 2048x2732
and their landscape forms) to `<out>/appstore-ipad-13/`.
"""
import json
import re
import shutil
import struct
import sys
from pathlib import Path

APP_STORE_IPAD_13 = {(2064, 2752), (2752, 2064), (2048, 2732), (2732, 2048)}


def png_size(path: Path) -> tuple[int, int]:
    with path.open("rb") as stream:
        header = stream.read(24)
    if header[:8] != b"\x89PNG\r\n\x1a\n":
        return (0, 0)
    return struct.unpack(">II", header[16:24])


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
            entry = {"name": name, "device": item.get("deviceName"), "width": width, "height": height}
            if (width, height) in APP_STORE_IPAD_13:
                store.mkdir(exist_ok=True)
                shutil.copyfile(target, store / name)
                entry["appStoreIPad13"] = True
            summary.append(entry)
    summary.sort(key=lambda entry: entry["name"])
    (out / "screenshots.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n")
    for entry in summary:
        flag = " (App Store 13-inch iPad)" if entry.get("appStoreIPad13") else ""
        print(f"{entry['name']}: {entry['width']}x{entry['height']}{flag}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
