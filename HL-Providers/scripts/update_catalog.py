#!/usr/bin/env python3
"""Update a single provider entry inside the signed catalog document."""

from __future__ import annotations

import argparse
import json
import pathlib


CANONICAL_DISPLAY_NAMES = {
    "qqmusic": "QQ音乐",
    "kugou": "酷狗音乐",
    "lxmusic": "LX音乐",
    "qishui": "汽水音乐",
    "salt-player": "椒盐音乐",
    "spotify": "Spotify",
}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--catalog", required=True, type=pathlib.Path)
    parser.add_argument("--provider", required=True)
    parser.add_argument("--version-name", required=True)
    parser.add_argument("--version-code", required=True, type=int)
    parser.add_argument("--asset-url", required=True)
    parser.add_argument("--sha256", required=True)
    parser.add_argument("--target-packages", required=True)
    args = parser.parse_args()

    catalog = json.loads(args.catalog.read_text(encoding="utf-8"))

    if args.provider == "qqmusic":
        catalog["providers"] = [
            item for item in catalog["providers"] if item["id"] != "qqmusic-hd"
        ]

    for item in catalog["providers"]:
        if item["id"] in CANONICAL_DISPLAY_NAMES:
            item["displayName"] = CANONICAL_DISPLAY_NAMES[item["id"]]

    provider = next(item for item in catalog["providers"] if item["id"] == args.provider)
    provider.update(
        targetPackages=json.loads(args.target_packages),
        available=True,
        versionName=args.version_name,
        versionCode=int(args.version_code),
        assetUrl=args.asset_url,
        sha256=args.sha256,
    )

    lines = ["{", f'  "schemaVersion": {catalog["schemaVersion"]},', '  "providers": [']
    for index, item in enumerate(catalog["providers"]):
        suffix = "," if index + 1 < len(catalog["providers"]) else ""
        encoded = json.dumps(item, ensure_ascii=False, separators=(",", ":"))
        lines.append(f"    {encoded}{suffix}")
    lines.extend(["  ]", "}"])
    args.catalog.write_text("\n".join(lines) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
