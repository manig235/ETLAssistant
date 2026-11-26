#!/usr/bin/env python3
"""Verify same-source execution tests, optionally while device compilation runs.

A pending proof may allow compilation to start, but the IPA upload has a second,
mandatory check that waits for both execution-test jobs to succeed.
"""
import json
import os
from pathlib import Path
import re
import subprocess
import time


def command(*args):
    return subprocess.check_output(args, text=True).strip()


run_id = os.environ.get("PROOF_RUN_ID", "").strip()
verified = False
pending = False
if run_id:
    if not re.fullmatch(r"[0-9]+", run_id):
        raise SystemExit("Runtime proof must be a numeric Actions run ID")
    repo = os.environ["GITHUB_REPOSITORY"]

    def api(path):
        return json.loads(command("gh", "api", f"repos/{repo}/{path}"))

    run = api(f"actions/runs/{run_id}")
    workflows = [api("actions/workflows/" + name)["id"] for name in ["ios-native-codex-ipa.yml", "ios-code-mode-probe.yml"]]
    if run["workflow_id"] not in workflows or run["event"] != "workflow_dispatch" or run.get("head_repository", {}).get("full_name") != repo:
        raise SystemExit("Runtime proof is not an approved execution-test workflow from this repository")
    revision = run["head_sha"]
    subprocess.run(["git", "fetch", "--no-tags", "--depth=1", "origin", revision], check=True)
    paths = ["ios/NativeCodex/bridge", "ios/NativeCodex/javascript", "ios/NativeCodex/code-mode-check", "ios/NativeCodex/JavaScriptCoreProbe.swift", "ios/NativeCodex/native-static-libs.txt", ".github/workflows/ios-code-mode-probe.yml"]
    subprocess.run(["git", "diff", "--quiet", revision, "--", *paths], check=True)
    old_script = command("git", "show", f"{revision}:.github/scripts/ios-codex-probe.sh")
    new_script = Path(".github/scripts/ios-codex-probe.sh").read_text()
    for key in ["REVISION", "ARCHIVE_SHA256", "TOOLCHAIN"]:
        old = re.search(rf"^{key}=(.+)$", old_script, re.M)
        new = re.search(rf"^{key}=(.+)$", new_script, re.M)
        if old is None or new is None or old[1] != new[1]:
            raise SystemExit(f"Runtime proof uses a different pinned {key}")

    deadline = time.monotonic() + int(os.environ.get("PROOF_WAIT_SECONDS", "0"))
    while True:
        jobs = api(f"actions/runs/{run_id}/jobs?per_page=100")["jobs"]
        successful = []
        for name in ["javascript-tool-probe", "codex-code-mode-delegate", "codex-thread-resume"]:
            matching = [job for job in jobs if job["name"].split(" / ")[-1] == name]
            if len(matching) > 1:
                raise SystemExit(f"Ambiguous execution proof: {name}")
            if matching and matching[0]["conclusion"] not in [None, "success"]:
                raise SystemExit(f"Execution proof failed: {name}: {matching[0]['conclusion']}")
            successful.append(len(matching) == 1 and matching[0]["conclusion"] == "success")
        if all(successful):
            verified = True
            print(f"Verified macOS/iOS interpreter, Codex delegate and thread resume tests from run {run_id}.")
            break
        run = api(f"actions/runs/{run_id}")
        if run["status"] == "completed":
            raise SystemExit("Completed proof run does not contain all successful execution-test jobs")
        if time.monotonic() < deadline:
            print(f"Waiting for execution proof {run_id}; IPA upload remains blocked.", flush=True)
            time.sleep(15)
            continue
        if os.environ.get("PROOF_ALLOW_PENDING") == "1":
            pending = True
            print(f"Proof {run_id} is still running. Device compilation may start; upload must re-verify it.")
            break
        raise SystemExit("Execution proof is not yet successful; refusing IPA upload")

with Path(os.environ["GITHUB_OUTPUT"]).open("a") as output:
    output.write(f"verified={'true' if verified else 'false'}\n")
    output.write(f"pending={'true' if pending else 'false'}\n")
