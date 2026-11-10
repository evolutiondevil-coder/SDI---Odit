#!/usr/bin/env python3
"""Writes manifest.json with version, size and SHA-256 of every pack. Run after changing a pack
(and remember to raise that pack's "version"). Usage: python3 tools/build_manifest.py"""
import glob
import hashlib
import json
import os
from datetime import date

packs = []
for path in sorted(glob.glob("packs/*.json")):
    raw = open(path, "rb").read()
    p = json.loads(raw)
    entry = {
        "id": p["id"],
        "version": p["version"],
        "file": path,
        "bytes": len(raw),
        "sha256": hashlib.sha256(raw).hexdigest(),
        "title": p.get("title", ""),
    }
    if p.get("makes"):
        entry["makes"] = p["makes"]
    assert os.path.basename(path) == p["id"] + ".json", f"{path}: id must match the file name"
    packs.append(entry)

manifest = {"schema": 1, "updated": str(date.today()), "packs": packs}
with open("manifest.json", "w", encoding="utf-8") as f:
    json.dump(manifest, f, ensure_ascii=False, indent=1)
print(f"{len(packs)} packs")
