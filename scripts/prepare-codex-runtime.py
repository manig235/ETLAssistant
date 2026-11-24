#!/usr/bin/env python3
"""Prepare the pinned runtime without changing downloaded upstream binaries.

The upstream helper basename and Android-compatible basename are both 20 bytes.
Only their occurrences in the ELF .rodata section change; Rust string lengths,
addresses, instructions, and file size stay intact. Unknown inputs fail closed.
"""

import argparse
import hashlib
import json
from pathlib import Path
import shutil
import struct
import tempfile
import os

VERSION = "rust-v0.170.0"
UPSTREAM_NAME = b"codex-code-mode-host"
ANDROID_NAME = b"libcodex-modehost.so"
PINS = {
    183: {  # ELF EM_AARCH64, Android release runtime
        "architecture": "aarch64",
        "elf_type": 2,
        "codex_sha256": "6a95492ca9d85ee76bd9fc5a4d8f72dd98a6130d1bb409bcfc50e61573106616",
        "host_sha256": "3c0e282eab51c572b4494f94cf20a7ddbaebcacf82408d36438eaa7f62891042",
        "offsets": [228692874, 230646580, 232374738],
    },
    62: {  # ELF EM_X86_64, same release used for local transport integration tests
        "architecture": "x86_64",
        "elf_type": 3,
        "codex_sha256": "880819aab735adcf16f36cd628940b9f1e652a36c01d10a5d32f83cc84902971",
        "host_sha256": "ffc1ad7023864bfc209c408887d6834a8f644201eff4432fb9558b7f1c40e9dc",
        "offsets": [238987, 2657768],
    },
}


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def machine(data):
    if len(data) < 64 or data[:7] != b"\x7fELF\x02\x01\x01":
        raise ValueError("expected a little-endian ELF64 runtime")
    if struct.unpack_from("<H", data, 16)[0] not in (2, 3):
        raise ValueError("expected an ELF executable")
    return struct.unpack_from("<H", data, 18)[0]


def rodata_range(data):
    section_offset = struct.unpack_from("<Q", data, 40)[0]
    entry_size, count, names_index = struct.unpack_from("<HHH", data, 58)
    if entry_size != 64 or not 0 < names_index < count or section_offset + count * entry_size > len(data):
        raise ValueError("invalid or missing ELF section table")

    def section(index):
        return struct.unpack_from("<IIQQQQIIQQ", data, section_offset + index * entry_size)

    names = section(names_index)
    strings = data[names[4]:names[4] + names[5]]
    for index in range(count):
        entry = section(index)
        if strings[entry[0]:].split(b"\0", 1)[0] == b".rodata":
            flags, start, size = entry[2], entry[4], entry[5]
            if flags & 5 or not flags & 2 or start + size > len(data):
                raise ValueError("helper basename must be in allocated, non-executable, read-only .rodata")
            return start, start + size
    raise ValueError("missing .rodata section")


def patch_codex(data):
    pin = PINS.get(machine(data))
    if pin is None or struct.unpack_from("<H", data, 16)[0] != pin["elf_type"] or sha256(data) != pin["codex_sha256"]:
        raise ValueError("Codex binary differs from the pinned upstream SHA-256; review/update the patch before upgrading")
    start, end = rodata_range(data)
    offsets = []
    cursor = 0
    while True:
        cursor = data.find(UPSTREAM_NAME, cursor)
        if cursor < 0:
            break
        offsets.append(cursor)
        cursor += len(UPSTREAM_NAME)
    if len(UPSTREAM_NAME) != len(ANDROID_NAME) or offsets != pin["offsets"]:
        raise ValueError("unexpected helper-basename occurrences")
    if any(offset < start or offset + len(UPSTREAM_NAME) > end for offset in offsets):
        raise ValueError("refusing to change data outside .rodata")
    patched = data.replace(UPSTREAM_NAME, ANDROID_NAME)
    if len(patched) != len(data) or UPSTREAM_NAME in patched:
        raise ValueError("helper-basename replacement failed")
    return patched, pin


def prepare(codex, host, destination, metadata, android_only=False):
    original = Path(codex).read_bytes()
    patched, pin = patch_codex(original)
    if android_only and machine(original) != 183:
        raise ValueError("Android packaging requires the pinned aarch64 runtime")
    helper = Path(host).read_bytes()
    if machine(helper) != machine(original) or sha256(helper) != pin["host_sha256"]:
        raise ValueError("code-mode host differs from the pinned upstream SHA-256")
    destination = Path(destination)
    destination.mkdir(parents=True, exist_ok=True)
    output = destination / "libcodex.so"
    for target in (output, destination / ANDROID_NAME.decode()):
        for source in (Path(codex), Path(host)):
            if target.resolve() == source.resolve() or target.exists() and os.path.samefile(target, source):
                raise ValueError("generated outputs must not overwrite upstream input files")
    with tempfile.NamedTemporaryFile(dir=destination, delete=False) as temporary:
        temporary.write(patched)
        temporary_path = Path(temporary.name)
    temporary_path.chmod(0o755)
    os.replace(temporary_path, output)
    shutil.copyfile(host, destination / ANDROID_NAME.decode())
    (destination / ANDROID_NAME.decode()).chmod(0o755)
    # A prior build must not leave the old network-listener helper in packaging inputs.
    (destination / "libcodex-code-mode-host.so").unlink(missing_ok=True)
    receipt = {
        "version": VERSION,
        "architecture": pin["architecture"],
        "transport": "grpc+stdio://",
        "upstream_codex_sha256": sha256(original),
        "packaged_codex_sha256": sha256(patched),
        "upstream_host_sha256": sha256(helper),
        "packaged_host_sha256": sha256(helper),
        "upstream_helper_basename": UPSTREAM_NAME.decode(),
        "packaged_helper_basename": ANDROID_NAME.decode(),
        "replacement_offsets": pin["offsets"],
    }
    metadata = Path(metadata)
    metadata.parent.mkdir(parents=True, exist_ok=True)
    metadata.write_text(json.dumps(receipt, indent=2, sort_keys=True) + "\n")
    print(json.dumps(receipt, sort_keys=True))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--codex", required=True)
    parser.add_argument("--host", required=True)
    parser.add_argument("--destination", required=True)
    parser.add_argument("--metadata", required=True)
    parser.add_argument("--android-only", action="store_true")
    args = parser.parse_args()
    try:
        prepare(args.codex, args.host, args.destination, args.metadata, args.android_only)
    except (OSError, ValueError, struct.error) as error:
        parser.exit(1, f"Cannot prepare the private code-mode runtime: {error}\nRun scripts/fetch-codex.sh for the pinned release.\n")
