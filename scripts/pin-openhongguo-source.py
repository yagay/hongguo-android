#!/usr/bin/env python3
"""Pin an upstream commit without changing signer or playback patch code.

Used by the trusted daily build job and by the promotion job. The updated
source is compiled only in the unprivileged job; signing secrets are never
available to that job.
"""
from __future__ import annotations
from pathlib import Path
import re
import sys

BASE = Path(__file__).resolve().parent.parent
TARGET = BASE / "scripts/build-openhongguo-16kb.sh"


def pin_source(upstream_sha: str, path: Path = TARGET) -> bool:
    if not re.fullmatch(r"[a-f0-9]{40}", upstream_sha):
        raise ValueError("expected a full, lowercase, 40-character git SHA")
    source = path.read_text(encoding="utf-8")
    expected = {
        "SOURCE_SHA": r'[a-f0-9]{40}',
        "SOURCE_TAG": r'[^"\n]+',
        "PATCH_ID": r'[^"\n]+',
    }
    new_values = {
        "SOURCE_SHA": upstream_sha,
        "SOURCE_TAG": "main@" + upstream_sha[:8],
        "PATCH_ID": "v7-upstream-auto-" + upstream_sha[:12] + "-player-empty-strictid",
    }
    updated = source
    for key, old_pattern in expected.items():
        pattern = r'(?m)^' + key + r'="' + old_pattern + r'"$'
        if len(re.findall(pattern, updated)) != 1:
            raise RuntimeError(f"Cannot safely pin: invalid {key} assignment")
        updated = re.sub(
            pattern, lambda _: f'{key}="{new_values[key]}"', updated, count=1,
        )
    if updated == source:
        return False
    path.write_text(updated, encoding="utf-8")
    return True


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("usage: pin-openhongguo-source.py <full-upstream-SHA>")
    print("changed" if pin_source(sys.argv[1]) else "unchanged")
