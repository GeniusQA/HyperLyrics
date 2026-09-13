#!/usr/bin/env python3
"""Build and sign an HLE Provider Pack from an Android APK."""

from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import subprocess
import tempfile
import zipfile


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", required=True, type=pathlib.Path)
    parser.add_argument("--manifest", required=True, type=pathlib.Path)
    parser.add_argument("--private-key", required=True, type=pathlib.Path)
    parser.add_argument("--output", required=True, type=pathlib.Path)
    args = parser.parse_args()

    with zipfile.ZipFile(args.apk) as apk:
        dex_names = sorted(
            name for name in apk.namelist()
            if name == "classes.dex" or name.startswith("classes") and name.endswith(".dex")
        )
        if dex_names != ["classes.dex"]:
            raise SystemExit(f"Provider Pack v1 requires exactly one classes.dex: {dex_names}")
        classes_dex = apk.read("classes.dex")
        forbidden_symbols = (
            b"io/github/libxposed/api/XposedModule",
            b"io/github/libxposed/api/XposedInterface",
        )
        if any(symbol in classes_dex for symbol in forbidden_symbols):
            raise SystemExit(
                "Provider Pack plugin code must not reference libxposed API; "
                "use the static host callback API instead"
            )

    manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    manifest["classesSha256"] = hashlib.sha256(classes_dex).hexdigest()
    manifest_bytes = json.dumps(
        manifest,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")

    with tempfile.TemporaryDirectory() as temp_dir:
        temp = pathlib.Path(temp_dir)
        payload = temp / "payload.bin"
        signature = temp / "signature.ed25519"
        payload.write_bytes(manifest_bytes + b"\0" + classes_dex)
        subprocess.run(
            [
                "openssl", "pkeyutl", "-sign", "-rawin",
                "-inkey", str(args.private_key),
                "-in", str(payload),
                "-out", str(signature),
            ],
            check=True,
        )

        args.output.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(args.output, "w", compression=zipfile.ZIP_DEFLATED) as pack:
            def write_entry(name: str, data: bytes) -> None:
                entry = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
                entry.compress_type = zipfile.ZIP_DEFLATED
                entry.external_attr = 0
                pack.writestr(entry, data)

            write_entry("manifest.json", manifest_bytes)
            write_entry("classes.dex", classes_dex)
            write_entry("signature.ed25519", signature.read_bytes())

    print(args.output)


if __name__ == "__main__":
    main()
