#!/usr/bin/env python3
"""Refresh the fixed Windows download from a verified, published versioned ZIP.

Requires GH_TOKEN with repository contents:write. Never moves a version tag or
marks Windows as GitHub Latest (reserved for the Android APK).
"""
from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import re
import tempfile
import urllib.error
import urllib.request

REPOSITORY = "manig235/ETLAssistant"
API = f"https://api.github.com/repos/{REPOSITORY}"
DOWNLOADS = f"https://github.com/{REPOSITORY}/releases/download"
TAG = "windows-latest"
FILENAME = "youngmin-windows-x64.zip"
INSTALLER_FILENAME = "youngmin-windows-x64-setup.exe"


def select_release(releases, installer=False):
    candidates = []
    for release in releases:
        match = re.fullmatch(r"windows-v(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)", release.get("tag_name", ""))
        if not match or release.get("draft") or release.get("prerelease"):
            continue
        version = '.'.join(match.groups())
        name = f"youngmin-windows-x64-setup-v{version}.exe" if installer else f"youngmin-windows-x64-v{version}.zip"
        for asset in release.get("assets", []):
            if (asset.get("name") == name and asset.get("state") == "uploaded"
                    and asset.get("size", 0) > 0
                    and asset.get("browser_download_url") == f"{DOWNLOADS}/{release['tag_name']}/{name}"
                    and re.fullmatch(r"sha256:[0-9a-f]{64}", asset.get("digest") or "")):
                candidates.append((tuple(map(int, match.groups())), release, asset))
    if not candidates:
        raise RuntimeError("No published Windows package with a SHA-256 digest")
    return max(candidates, key=lambda item: item[0])[1:]


class GitHub:
    def __init__(self):
        self.token = os.environ.get("GH_TOKEN") or os.environ["GITHUB_TOKEN"]

    def request(self, path, data=None, method="GET"):
        headers = {"Authorization": f"Bearer {self.token}", "Accept": "application/vnd.github+json",
                   "User-Agent": "YoungMini-Windows-download-publisher", "X-GitHub-Api-Version": "2022-11-28"}
        if data is not None:
            data = json.dumps(data).encode()
            headers["Content-Type"] = "application/json"
        with urllib.request.urlopen(urllib.request.Request(API + path, data=data, headers=headers, method=method), timeout=60) as response:
            body = response.read()
            return json.loads(body) if body else None

    def upload(self, release_id, path):
        headers = {"Authorization": f"Bearer {self.token}", "Content-Type": "application/octet-stream",
                   "Content-Length": str(path.stat().st_size), "User-Agent": "YoungMini-Windows-download-publisher"}
        url = f"https://uploads.github.com/repos/{REPOSITORY}/releases/{release_id}/assets?name={path.name}"
        with path.open("rb") as stream:
            with urllib.request.urlopen(urllib.request.Request(url, data=stream, headers=headers, method="POST"), timeout=300) as response:
                return json.load(response)


def replace_asset(github, release_id, path, name, digest):
    assets = github.request(f"/releases/{release_id}/assets?per_page=100")
    current = next((asset for asset in assets if asset["name"] == name), None)
    if current and current.get("state") == "uploaded" and current.get("digest") == f"sha256:{digest}":
        return
    # Upload and verify before replacing the existing fixed download. A failed
    # transfer leaves the old ZIP available; retries can reuse a staged upload.
    staged = next((asset for asset in assets if asset["name"] == path.name), None)
    if staged and (staged.get("state") != "uploaded" or staged.get("digest") != f"sha256:{digest}"):
        github.request(f"/releases/assets/{staged['id']}", method="DELETE")
        staged = None
    staged = staged or github.upload(release_id, path)
    if staged.get("state") != "uploaded" or staged.get("digest") != f"sha256:{digest}":
        raise RuntimeError("Uploaded asset digest mismatch; fixed download was not replaced")
    if current:
        github.request(f"/releases/assets/{current['id']}", method="DELETE")
    github.request(f"/releases/assets/{staged['id']}", {"name": name}, method="PATCH")


def main():
    github = GitHub()
    releases = []
    for page in range(1, 21):
        batch = github.request(f"/releases?per_page=100&page={page}")
        releases.extend(batch)
        if len(batch) < 100:
            break
    else:
        raise RuntimeError("Release list exceeds pagination limit")
    source, asset = select_release(releases)
    # Installer and portable aliases must refer to the same reviewed version.
    _, installer = select_release([source], installer=True)
    checksum = asset["digest"].removeprefix("sha256:")
    latest_before = github.request("/releases/latest")["tag_name"]
    print(f"Verified source: {source['tag_name']} ({checksum})", flush=True)
    with tempfile.TemporaryDirectory(prefix="youngmin-windows-download-") as directory:
        path = Path(directory) / f"{FILENAME}.{checksum[:16]}.pending"
        digest = hashlib.sha256()
        # Public download uses no repository token, including across redirects.
        request = urllib.request.Request(asset["browser_download_url"], headers={"User-Agent": "YoungMini-Windows-download-publisher"})
        with urllib.request.urlopen(request, timeout=60) as response, path.open("wb") as output:
            while chunk := response.read(1024 * 1024):
                output.write(chunk)
                digest.update(chunk)
        if path.stat().st_size != asset["size"] or digest.hexdigest() != checksum:
            raise RuntimeError("Source ZIP size or SHA-256 mismatch; nothing was published")
        alias = next((release for release in releases if release["tag_name"] == TAG), None)
        if not alias:
            commit = github.request(f"/commits/{source['tag_name']}")["sha"]
            alias = github.request("/releases", {"tag_name": TAG, "target_commitish": commit,
                                   "name": "영민이 Windows 최신 버전", "draft": True, "make_latest": "false"}, method="POST")
        if alias.get("immutable"):
            raise RuntimeError("Fixed download release must allow asset updates")
        replace_asset(github, alias["id"], path, FILENAME, checksum)
        checksum_path = Path(directory) / f"{FILENAME}.sha256.{checksum[:16]}.pending"
        checksum_path.write_bytes(f"{checksum}  {FILENAME}\n".encode())
        checksum_digest = hashlib.sha256(checksum_path.read_bytes()).hexdigest()
        replace_asset(github, alias["id"], checksum_path, FILENAME + ".sha256", checksum_digest)
        installer_checksum = installer["digest"].removeprefix("sha256:")
        installer_path = Path(directory) / f"{INSTALLER_FILENAME}.{installer_checksum[:16]}.pending"
        installer_digest = hashlib.sha256()
        request = urllib.request.Request(installer["browser_download_url"], headers={"User-Agent": "YoungMini-Windows-download-publisher"})
        with urllib.request.urlopen(request, timeout=60) as response, installer_path.open("wb") as output:
            while chunk := response.read(1024 * 1024):
                output.write(chunk)
                installer_digest.update(chunk)
        if installer_path.stat().st_size != installer["size"] or installer_digest.hexdigest() != installer_checksum:
            raise RuntimeError("Installer size or SHA-256 mismatch")
        replace_asset(github, alias["id"], installer_path, INSTALLER_FILENAME, installer_checksum)
        installer_sum = Path(directory) / f"{INSTALLER_FILENAME}.sha256.{installer_checksum[:16]}.pending"
        installer_sum.write_bytes(f"{installer_checksum}  {INSTALLER_FILENAME}\n".encode())
        replace_asset(github, alias["id"], installer_sum, INSTALLER_FILENAME + ".sha256", hashlib.sha256(installer_sum.read_bytes()).hexdigest())
        body = (f"Windows x64 최신 정식 배포본의 고정 다운로드입니다. 현재 **{source['tag_name']}**.\n\n"
                f"[Windows 설치 파일 받기]({DOWNLOADS}/{TAG}/{INSTALLER_FILENAME})\n\n"
                f"[Windows 최신 ZIP 바로 받기]({DOWNLOADS}/{TAG}/{FILENAME})\n\n"
                f"[현재 버전의 변경 사항과 원본]({source['html_url']}) · "
                f"[Android 최신 APK](https://github.com/{REPOSITORY}/releases/latest/download/youngmin.apk)\n\n"
                "설치 파일을 실행하면 시작 메뉴와 앱 제거에 등록됩니다. 설치 중 Windows 로그인 시 자동 실행을 선택할 수 있습니다. "
                "ZIP은 압축을 모두 풀고 기존 영민이를 완전히 종료한 뒤 YoungMini/YoungMini.exe를 실행하세요.\n\n"
                "이 릴리스는 다운로드 파일만 갱신합니다. 아래 자동 생성 Source code 압축파일은 앱 설치 파일이 아닙니다. "
                "소스 코드는 위 버전별 원본에서 확인하세요.\n")
        github.request(f"/releases/{alias['id']}", {"name": f"영민이 Windows 최신 버전 ({source['tag_name']})",
                       "body": body, "draft": False, "prerelease": False, "make_latest": "false"}, method="PATCH")
        latest_after = github.request("/releases/latest")["tag_name"]
        if latest_after != latest_before:
            raise RuntimeError(f"GitHub Latest changed during publishing: {latest_before} -> {latest_after}")
    print(f"Published: {DOWNLOADS}/{TAG}/{FILENAME}", flush=True)
    print(f"Android Latest preserved: {latest_after}", flush=True)


if __name__ == "__main__":
    main()
