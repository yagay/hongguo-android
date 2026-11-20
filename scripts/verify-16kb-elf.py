#!/usr/bin/env python3
"""Fail CI if ANY shipped ARM64 native ELF has 4 KiB-only PT_LOAD alignment.

Checks actual ELF program headers, not ZIP placement or Android manifest flags.
Supports both an APK (lib/arm64-v8a) and an AAR (jni/arm64-v8a).
"""
import struct
import sys
import zipfile
from pathlib import Path

PAGE = 16 * 1024
PREFIXES = ("lib/arm64-v8a/", "jni/arm64-v8a/")


def inspect_elf(name: str, data: bytes) -> list[str]:
    if len(data) < 64 or data[:4] != b"\x7fELF":
        return [f"{name}: invalid ELF header"]
    if data[4] != 2 or data[5] != 1:
        return [f"{name}: expected 64-bit little-endian ELF"]
    phoff = struct.unpack_from("<Q", data, 32)[0]
    phentsize, phnum = struct.unpack_from("<HH", data, 54)
    if phentsize < 56 or phnum == 0 or phoff + phentsize * phnum > len(data):
        return [f"{name}: invalid program header table"]

    problems = []
    loads = 0
    for index in range(phnum):
        offset = phoff + index * phentsize
        p_type, _, p_offset, p_vaddr, _, _, _, p_align = struct.unpack_from(
            "<IIQQQQQQ", data, offset
        )
        if p_type != 1:  # PT_LOAD
            continue
        loads += 1
        if p_align < PAGE:
            problems.append(
                f"{name}: LOAD #{index} alignment {p_align} < {PAGE} (16 KiB)"
            )
        if p_offset % PAGE != p_vaddr % PAGE:
            problems.append(
                f"{name}: LOAD #{index} file/virtual offsets differ modulo 16 KiB"
            )
    if not loads:
        problems.append(f"{name}: ELF has no PT_LOAD segment")
    return problems


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: verify-16kb-elf.py <app.apk|openhongguo.aar>", file=sys.stderr)
        return 2
    archive = Path(sys.argv[1])
    if not archive.is_file():
        print(f"Missing artifact: {archive}", file=sys.stderr)
        return 2
    failures = []
    checked = []
    with zipfile.ZipFile(archive) as z:
        for name in z.namelist():
            if not name.startswith(PREFIXES) or not name.endswith(".so"):
                continue
            data = z.read(name)
            checked.append(name)
            failures.extend(inspect_elf(name, data))
    if not checked:
        print(f"{archive}: no ARM64 native ELF files found; cannot verify", file=sys.stderr)
        return 1
    for name in checked:
        print(f"CHECK {name}")
    if failures:
        for failure in failures:
            print("FAIL " + failure, file=sys.stderr)
        return 1
    print(f"PASS: {len(checked)} ARM64 shared objects support 16 KiB ELF LOAD alignment")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
