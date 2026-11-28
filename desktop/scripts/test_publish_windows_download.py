import unittest
from pathlib import Path
from publish_windows_download import DOWNLOADS, select_release, replace_asset


def release(version="0.5.2"):
    tag = f"windows-v{version}"
    name = f"youngmin-windows-x64-v{version}.zip"
    return {"tag_name": tag, "draft": False, "prerelease": False, "assets": [
        {"name": name, "state": "uploaded", "size": 400,
         "browser_download_url": f"{DOWNLOADS}/{tag}/{name}", "digest": "sha256:" + "a" * 64}]}


class FixedDownloadTest(unittest.TestCase):
    def test_installer_requires_a_verified_matching_asset(self):
        source = release("0.5.3")
        with self.assertRaises(RuntimeError):
            select_release([source], installer=True)
        asset = dict(source["assets"][0])
        asset["name"] = "youngmin-windows-x64-setup-v0.5.3.exe"
        asset["browser_download_url"] = f"{DOWNLOADS}/{source['tag_name']}/{asset['name']}"
        source["assets"].append(asset)
        self.assertEqual(asset, select_release([source], installer=True)[1])
        asset["digest"] = None
        with self.assertRaises(RuntimeError):
            select_release([source], installer=True)

    def test_newest_windows_version_ignores_android_and_alias(self):
        android = release("9.0.0")
        android["tag_name"] = "v9.0.0"
        alias = release("10.0.0")
        alias["tag_name"] = "windows-latest"
        selected, _ = select_release([release(), android, alias, release("0.5.10"), release("0.5.9")])
        self.assertEqual("windows-v0.5.10", selected["tag_name"])

    def test_unpublished_and_unverified_assets_are_rejected(self):
        for key, value in [("draft", True), ("prerelease", True), ("tag_name", "windows-v0.05.2")]:
            invalid = release()
            invalid[key] = value
            with self.subTest(key=key), self.assertRaises(RuntimeError):
                select_release([invalid])
        for key, value in [("state", "new"), ("digest", None), ("size", 0),
                           ("name", "youngmin.apk"), ("browser_download_url", "https://example.com/app.zip")]:
            invalid = release()
            invalid["assets"][0][key] = value
            with self.subTest(key=key), self.assertRaises(RuntimeError):
                select_release([invalid])

    def test_failed_upload_preserves_existing_download(self):
        class FailedUpload:
            def __init__(self):
                self.calls = []

            def request(self, path, data=None, method="GET"):
                self.calls.append(method)
                return [{"id": 1, "name": "app.zip", "digest": "sha256:" + "b" * 64, "state": "uploaded"}]

            def upload(self, release_id, path):
                return {"id": 2, "digest": "sha256:" + "c" * 64, "state": "uploaded"}

        github = FailedUpload()
        with self.assertRaises(RuntimeError):
            replace_asset(github, 42, Path("app.zip.pending"), "app.zip", "a" * 64)
        self.assertEqual(["GET"], github.calls)

    def test_successful_staged_upload_is_verified_before_swap(self):
        class Upload:
            def __init__(self):
                self.calls = []

            def request(self, path, data=None, method="GET"):
                self.calls.append((method, path, data))
                return [{"id": 1, "name": "app.zip", "digest": "sha256:" + "b" * 64, "state": "uploaded"}]

            def upload(self, release_id, path):
                self.calls.append(("UPLOAD", str(path)))
                return {"id": 2, "digest": "sha256:" + "a" * 64, "state": "uploaded"}

        github = Upload()
        replace_asset(github, 42, Path("app.zip.pending"), "app.zip", "a" * 64)
        self.assertEqual(["GET", "UPLOAD", "DELETE", "PATCH"], [call[0] for call in github.calls])
        self.assertEqual(("PATCH", "/releases/assets/2", {"name": "app.zip"}), github.calls[-1])


if __name__ == "__main__":
    unittest.main()
