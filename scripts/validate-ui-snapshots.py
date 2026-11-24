#!/usr/bin/env python3
"""Fail CI when Compose UI screenshots are missing, corrupt, or identical across themes.

The Android emulator runner executes each script line in its own shell. Keep all
multi-line checking here instead of embedding shell loops or heredocs in YAML.
"""
from __future__ import annotations

import hashlib
import struct
import sys
from pathlib import Path

EXPECTED = {
    "theme_classic_light",
    "theme_classic_dark",
    "theme_material3_light",
    "theme_material3_dark",
    "theme_surface_classic_light",
    "theme_surface_material3_light",
    "poster_material3_dark",
    "poster_shared_element",
    "seek_accessibility",
}
PAIRS = (
    ("theme_classic_light", "theme_classic_dark"),
    ("theme_material3_light", "theme_material3_dark"),
    ("theme_surface_classic_light", "theme_surface_material3_light"),
)
PNG_MAGIC = bytes.fromhex("89504e470d0a1a0a")


def verify(root: Path) -> None:
    snapshots = {p.stem: p for p in root.rglob("*.png") if p.is_file()}
    missing = EXPECTED - snapshots.keys()
    if missing:
        raise AssertionError("Missing UI snapshots: " + ", ".join(sorted(missing)))

    for name in EXPECTED:
        path = snapshots[name]
        data = path.read_bytes()
        if len(data) < 33 or not data.startswith(PNG_MAGIC) or data[12:16] != b"IHDR":
            raise AssertionError(f"Corrupt or empty PNG: {path}")
        width, height = struct.unpack(">II", data[16:24])
        if width <= 0 or height <= 0:
            raise AssertionError(f"Invalid screenshot dimensions: {path}")

    for a, b in PAIRS:
        aa = hashlib.sha256(snapshots[a].read_bytes()).digest()
        bb = hashlib.sha256(snapshots[b].read_bytes()).digest()
        if aa == bb:
            raise AssertionError(f"Theme screenshots unexpectedly identical: {a} and {b}")
    print(f"PASS: {len(EXPECTED)} required visual snapshots present and distinct themes verified")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("Usage: validate-ui-snapshots.py <screenshots-dir>")
    verify(Path(sys.argv[1]))
