#!/usr/bin/env python3
"""Reuse completed execution tests only for unchanged runtime/delegate sources.

The app-server bootstrap and device compilation still run. This is intended for
packaging/bootstrap fixes after the independent execution tests already passed.
"""
import json
import os
from pathlib import Path
import re
import subprocess

def command(*args):
    return subprocess.check_output(args, text=True).strip()

run_id = os.environ.get("PROOF_RUN_ID", "").strip()
verified = False
if run_id:
    if not re.fullmatch(r"[0-9]+", run_id):
        raise SystemExit("Runtime proof must be a numeric Actions run ID")
    repo = os.environ["GITHUB_REPOSITORY"]
    def api(path):
        return json.loads(command("gh", "api", f"repos/{repo}/{path}"))
    run = api(f"actions/runs/{run_id}")
    workflow = api("actions/workflows/ios-native-codex-ipa.yml")
    if run["workflow_id"] != workflow["id"] or run["event"] != "workflow_dispatch" or run.get("head_repository", {}).get("full_name") != repo:
        raise SystemExit("Runtime proof is not an installable-app build from this repository")
    jobs = api(f"actions/runs/{run_id}/jobs?per_page=100")["jobs"]
    for name in ["javascript-tool-probe", "codex-code-mode-delegate"]:
        matching = [job for job in jobs if job["name"].split(" / ")[-1] == name]
        if len(matching) != 1 or matching[0]["conclusion"] != "success":
            raise SystemExit(f"Runtime proof is missing a successful {name} job")
    revision = run["head_sha"]
    subprocess.run(["git", "fetch", "--no-tags", "--depth=1", "origin", revision], check=True)
    paths = ["ios/NativeCodex/bridge", "ios/NativeCodex/javascript", "ios/NativeCodex/code-mode-check", "ios/NativeCodex/JavaScriptCoreProbe.swift", "ios/NativeCodex/native-static-libs.txt"]
    subprocess.run(["git", "diff", "--quiet", revision, "--", *paths], check=True)
    old_script = command("git", "show", f"{revision}:.github/scripts/ios-codex-probe.sh")
    new_script = Path(".github/scripts/ios-codex-probe.sh").read_text()
    for key in ["REVISION", "ARCHIVE_SHA256", "TOOLCHAIN"]:
        old = re.search(rf"^{key}=(.+)$", old_script, re.M)
        new = re.search(rf"^{key}=(.+)$", new_script, re.M)
        if old is None or new is None or old[1] != new[1]:
            raise SystemExit(f"Runtime proof uses a different pinned {key}")
    print(f"Reusing completed JavaScript/iOS and Codex delegate tests from run {run_id}; runtime and delegate source files are unchanged.")
    verified = True

with Path(os.environ["GITHUB_OUTPUT"]).open("a") as output:
    output.write(f"verified={'true' if verified else 'false'}\n")
