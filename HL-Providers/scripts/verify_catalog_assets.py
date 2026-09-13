#!/usr/bin/env python3
"""Disable catalog entries whose declared GitHub Release asset is missing."""

from __future__ import annotations

import argparse
import json
import os
import pathlib
import urllib.error
import urllib.parse
import urllib.request
from collections.abc import Callable


CATALOG_ASSET_FIELDS = ("versionName", "versionCode", "assetUrl", "sha256")


def parse_release_asset_url(url: str, repository: str) -> tuple[str, str] | None:
    parsed = urllib.parse.urlparse(url)
    expected_prefix = f"/{repository}/releases/download/"
    if parsed.scheme != "https" or parsed.netloc != "github.com" or not parsed.path.startswith(expected_prefix):
        return None
    remainder = parsed.path[len(expected_prefix):]
    tag, separator, asset_name = remainder.partition("/")
    if not separator or not tag or not asset_name:
        return None
    return urllib.parse.unquote(tag), urllib.parse.unquote(asset_name)


def fetch_release_assets(repository: str, tag: str, token: str | None) -> set[str] | None:
    encoded_tag = urllib.parse.quote(tag, safe="")
    request = urllib.request.Request(
        f"https://api.github.com/repos/{repository}/releases/tags/{encoded_tag}",
        headers={
            "Accept": "application/vnd.github+json",
            "User-Agent": "HLE-Providers-catalog-verifier",
            **({"Authorization": f"Bearer {token}"} if token else {}),
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            release = json.load(response)
    except urllib.error.HTTPError as error:
        if error.code == 404:
            return None
        raise
    return {asset["name"] for asset in release.get("assets", [])}


def disable_missing_assets(
    catalog: dict,
    repository: str,
    asset_loader: Callable[[str, str, str | None], set[str] | None] = fetch_release_assets,
    token: str | None = None,
) -> list[str]:
    disabled: list[str] = []
    release_cache: dict[str, set[str] | None] = {}
    for provider in catalog.get("providers", []):
        if not provider.get("available", False):
            continue
        parsed = parse_release_asset_url(provider.get("assetUrl", ""), repository)
        if parsed is None:
            available = False
        else:
            tag, asset_name = parsed
            if tag not in release_cache:
                release_cache[tag] = asset_loader(repository, tag, token)
            assets = release_cache[tag]
            available = assets is not None and asset_name in assets
        if available:
            continue
        provider["available"] = False
        for field in CATALOG_ASSET_FIELDS:
            provider.pop(field, None)
        disabled.append(provider["id"])
    return disabled


def write_catalog(path: pathlib.Path, catalog: dict) -> None:
    lines = ["{", f'  "schemaVersion": {catalog["schemaVersion"]},', '  "providers": [']
    for index, item in enumerate(catalog["providers"]):
        suffix = "," if index + 1 < len(catalog["providers"]) else ""
        lines.append(f"    {json.dumps(item, ensure_ascii=False, separators=(',', ':'))}{suffix}")
    lines.extend(["  ]", "}"])
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--catalog", required=True, type=pathlib.Path)
    parser.add_argument("--repository", required=True)
    args = parser.parse_args()

    catalog = json.loads(args.catalog.read_text(encoding="utf-8"))
    disabled = disable_missing_assets(
        catalog,
        args.repository,
        token=os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN"),
    )
    write_catalog(args.catalog, catalog)
    if disabled:
        print("Disabled missing Provider assets: " + ", ".join(disabled))
    else:
        print("All available Provider assets exist in GitHub Releases")


if __name__ == "__main__":
    main()
