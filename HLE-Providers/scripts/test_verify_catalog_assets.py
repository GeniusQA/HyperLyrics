#!/usr/bin/env python3

import unittest

from verify_catalog_assets import disable_missing_assets


class VerifyCatalogAssetsTest(unittest.TestCase):
    def test_keeps_available_entry_when_release_asset_exists(self) -> None:
        catalog = {
            "providers": [{
                "id": "qqmusic",
                "available": True,
                "versionName": "1.0.1",
                "versionCode": 2,
                "assetUrl": "https://github.com/juren233/HLE-Providers/releases/download/qqmusic-v1.0.1/qqmusic-1.0.1.hlp",
                "sha256": "abc",
            }],
        }

        disabled = disable_missing_assets(
            catalog,
            "juren233/HLE-Providers",
            asset_loader=lambda _repository, _tag, _token: {"qqmusic-1.0.1.hlp"},
        )

        self.assertEqual([], disabled)
        self.assertTrue(catalog["providers"][0]["available"])

    def test_disables_entry_and_removes_download_metadata_when_release_is_missing(self) -> None:
        catalog = {
            "providers": [{
                "id": "kuwo",
                "available": True,
                "versionName": "1.0.0",
                "versionCode": 1,
                "assetUrl": "https://github.com/juren233/HLE-Providers/releases/download/kuwo-v1.0.0/kuwo-1.0.0.hlp",
                "sha256": "abc",
            }],
        }

        disabled = disable_missing_assets(
            catalog,
            "juren233/HLE-Providers",
            asset_loader=lambda _repository, _tag, _token: None,
        )

        self.assertEqual(["kuwo"], disabled)
        self.assertEqual({"id": "kuwo", "available": False}, catalog["providers"][0])

    def test_does_not_query_entries_already_marked_unavailable(self) -> None:
        catalog = {"providers": [{"id": "kugou", "available": False}]}

        disabled = disable_missing_assets(
            catalog,
            "juren233/HLE-Providers",
            asset_loader=lambda *_args: self.fail("loader must not be called"),
        )

        self.assertEqual([], disabled)


if __name__ == "__main__":
    unittest.main()
