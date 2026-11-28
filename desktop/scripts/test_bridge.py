#!/usr/bin/env python3
"""Smoke-test the actual private UI/backend pipe with isolated app data and no account."""
import argparse
import json
import os
from pathlib import Path
import queue
import subprocess
import tempfile
import threading
import time


def check(java, codex):
    root = Path(__file__).resolve().parents[1]
    with tempfile.TemporaryDirectory(prefix="youngmini-bridge-") as work:
        env = {k: v for k, v in os.environ.items() if k not in {"JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS", "OPENAI_API_KEY", "CODEX_API_KEY"}}
        env["YOUNGMIN_CODEX"] = str(Path(codex).resolve())
        child = subprocess.Popen([java, "-Djava.awt.headless=true", "-Dfile.encoding=UTF-8", f"-Dyoungmini.data={work}", "-cp", str(root / "build/windows-stage/lib/*"), "kr.snu.youngmini.desktop.BridgeMainKt"],
                                 env=env, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, encoding="utf-8")
        messages = queue.Queue()
        threading.Thread(target=lambda: [messages.put(json.loads(line)) for line in child.stdout], daemon=True).start()

        def until(predicate):
            deadline = time.monotonic() + 20
            while time.monotonic() < deadline:
                m = messages.get(timeout=max(.1, deadline-time.monotonic()))
                assert m["type"] != "fatal", m
                if m["type"] == "state" and predicate(m["state"]):
                    return m["state"]
            raise AssertionError("Backend state timed out")

        def send(**command):
            child.stdin.write(json.dumps(command) + "\n"); child.stdin.flush()

        try:
            s = until(lambda s: s["ready"])
            assert not s["loggedIn"] and not s["etlConnected"]
            first = s["activeChatId"]
            send(action="newChat")
            s = until(lambda s: len(s["sessions"]) == 2)
            second = s["activeChatId"]
            send(action="setChoice", chatId=second, model="fixture-model", effort="high")
            send(action="setDraft", chatId=second, text="첫 줄\n둘째 줄")
            s = until(lambda s: any(c["id"] == second and c["draft"] == "첫 줄\n둘째 줄" for c in s["sessions"]))
            assert s["sessions"][1]["effort"] == "high" and s["sessions"][0]["effort"] is None
            send(action="openChat", chatId=first)
            s = until(lambda s: s["activeChatId"] == first)
            assert s["visibleChatIds"] == [first]
        finally:
            child.stdin.close()
            try:
                child.wait(timeout=8)
            except subprocess.TimeoutExpired:
                child.kill(); child.wait(); raise AssertionError("Backend did not shut down after its private input closed")
        assert child.returncode == 0
        assert not list(Path(work).rglob("auth.json"))
    print("PASS: actual UI/backend JSON pipe starts, isolates session settings/drafts, switches conversations, and exits cleanly; no user account used")


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--java", required=True)
    p.add_argument("--codex", required=True)
    a = p.parse_args()
    check(a.java, a.codex)
