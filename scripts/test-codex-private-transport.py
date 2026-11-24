#!/usr/bin/env python3
"""Exercise the real pinned x86 runtime against a local fake Responses provider.

No user credentials are read and no OpenAI request is made. A new CODEX_HOME is
used, and the model turn executes actual JavaScript in the renamed child helper.
Run with --codex and --host pointing to the original v0.170.0 x86_64 binaries.
"""

import argparse
import importlib.util
import json
import os
from pathlib import Path
import queue
import subprocess
import tempfile
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


def main(codex, host):
    spec = importlib.util.spec_from_file_location("prepare_runtime", Path(__file__).with_name("prepare-codex-runtime.py"))
    runtime = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(runtime)
    original_hash = runtime.sha256(Path(codex).read_bytes())
    host_hash = runtime.sha256(Path(host).read_bytes())
    # Unknown or modified inputs must fail before a build can package them.
    try:
        runtime.patch_codex(Path(codex).read_bytes() + b"tampered")
    except ValueError:
        pass
    else:
        raise AssertionError("modified upstream binary was accepted")

    with tempfile.TemporaryDirectory(prefix="codex-private-stdio-") as temporary:
        root = Path(temporary)
        binary_dir = root / "runtime"
        metadata = root / "receipt.json"
        runtime.prepare(codex, host, binary_dir, metadata)
        receipt = json.loads(metadata.read_text())
        assert receipt["architecture"] == "x86_64", "run the live integration test on Linux x86_64"
        assert runtime.sha256(Path(codex).read_bytes()) == original_hash
        assert runtime.sha256(Path(host).read_bytes()) == host_hash
        assert receipt["packaged_codex_sha256"] == runtime.sha256((binary_dir / "libcodex.so").read_bytes())
        assert sorted(p.name for p in binary_dir.iterdir()) == ["libcodex-modehost.so", "libcodex.so"]

        requests = []
        errors = []

        class Model(BaseHTTPRequestHandler):
            def log_message(self, *_args):
                pass

            def do_GET(self):
                self.send_error(404)

            def do_POST(self):
                body = self.rfile.read(int(self.headers.get("Content-Length", "0")))
                requests.append(body)
                if len(requests) == 1:
                    events = [
                        {"type": "response.created", "response": {"id": "response-1"}},
                        {"type": "response.output_item.done", "item": {
                            "type": "custom_tool_call", "call_id": "private-call", "name": "exec",
                            "input": "text('PRIVATE_STDIO_OK'); await new Promise(resolve => setTimeout(resolve, 700));",
                        }},
                    ]
                else:
                    events = [{"type": "response.output_item.done", "item": {
                        "type": "message", "role": "assistant", "id": "message-1",
                        "content": [{"type": "output_text", "text": "Private transport completed."}],
                    }}]
                events.append({"type": "response.completed", "response": {
                    "id": f"response-{len(requests)}", "usage": {"input_tokens": 0, "output_tokens": 0, "total_tokens": 0},
                }})
                payload = "".join(f"event: {event['type']}\ndata: {json.dumps(event)}\n\n" for event in events).encode()
                self.send_response(200)
                self.send_header("Content-Type", "text/event-stream")
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)

        server = ThreadingHTTPServer(("127.0.0.1", 0), Model)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        codex_home = root / "test-home"
        codex_home.mkdir()
        (codex_home / "config.toml").write_text(f'''
model = "mock-model"
model_provider = "local_test"
approval_policy = "never"
sandbox_mode = "read-only"
web_search = "disabled"
cli_auth_credentials_store = "ephemeral"
[features]
code_mode_only = true
apps = false
plugins = false
shell_tool = false
unified_exec = false
[analytics]
enabled = false
[feedback]
enabled = false
[otel]
metrics_exporter = "none"
[model_providers.local_test]
name = "Local test only"
base_url = "http://127.0.0.1:{server.server_port}/v1"
wire_api = "responses"
request_max_retries = 0
stream_max_retries = 0
''')
        environment = {key: value for key, value in os.environ.items() if key in ("PATH", "LANG")}
        environment["CODEX_HOME"] = str(codex_home)
        process = subprocess.Popen(
            [str(binary_dir / "libcodex.so"), "app-server", "--listen", "stdio://", "--enable", "code_mode_host"],
            cwd=root, env=environment, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
        )
        messages = queue.Queue()
        observed_helpers = {}
        watching = threading.Event()
        watching.set()

        def read_output():
            for line in process.stdout:
                messages.put(json.loads(line))

        def read_errors():
            errors.extend(process.stderr)

        def watch_helper():
            while watching.is_set():
                try:
                    children = set()
                    # Linux records children under the particular thread that spawned them.
                    for task in Path(f"/proc/{process.pid}/task").iterdir():
                        children.update((task / "children").read_text().split())
                    for pid in children:
                        command = Path(f"/proc/{pid}/cmdline").read_bytes().split(b"\0")
                        if command[0].decode() != str(binary_dir / "libcodex-modehost.so"):
                            continue
                        assert command[1:3] == [b"--listen", b"grpc+stdio://"], command
                        descriptors = {fd.name: os.readlink(fd) for fd in Path(f"/proc/{pid}/fd").iterdir()}
                        assert descriptors["0"].startswith("pipe:["), descriptors
                        assert descriptors["1"].startswith("pipe:["), descriptors
                        # The helper must own no TCP socket, including a short-lived listener.
                        sockets = {value[8:-1] for value in descriptors.values() if value.startswith("socket:[")}
                        for table in ("tcp", "tcp6"):
                            for row in Path(f"/proc/{pid}/net/{table}").read_text().splitlines()[1:]:
                                assert row.split()[9] not in sockets, "code-mode host owns a TCP socket"
                        observed_helpers[pid] = True
                except (FileNotFoundError, ProcessLookupError):
                    pass
                except BaseException as error:
                    observed_helpers["error"] = error
                    return
                time.sleep(0.01)

        threading.Thread(target=read_output, daemon=True).start()
        threading.Thread(target=read_errors, daemon=True).start()
        watcher = threading.Thread(target=watch_helper, daemon=True)
        watcher.start()

        def send(identifier, method, params):
            process.stdin.write(json.dumps({"id": identifier, "method": method, "params": params}) + "\n")
            process.stdin.flush()

        def until(predicate):
            deadline = time.monotonic() + 20
            while time.monotonic() < deadline:
                message = messages.get(timeout=max(0.01, deadline - time.monotonic()))
                if predicate(message):
                    assert "error" not in message, message
                    return message
            raise AssertionError("timed out waiting for app-server response")

        try:
            send(1, "initialize", {"clientInfo": {"name": "private_transport_test", "version": "1"}})
            until(lambda message: message.get("id") == 1)
            process.stdin.write('{"method":"initialized","params":{}}\n')
            process.stdin.flush()
            send(2, "thread/start", {"cwd": str(root), "approvalPolicy": "never", "sandbox": "read-only"})
            thread = until(lambda message: message.get("id") == 2)["result"]["thread"]["id"]
            send(3, "turn/start", {"threadId": thread, "input": [{"type": "text", "text": "Run the private code-mode test."}]})
            until(lambda message: message.get("id") == 3)
            completed = until(lambda message: message.get("method") == "turn/completed")
            assert completed["params"]["turn"]["status"] == "completed", completed
            assert len(requests) == 2, len(requests)
            follow_up = json.loads(requests[1])
            outputs = [item for item in follow_up["input"] if item.get("type") == "custom_tool_call_output"]
            assert any("PRIVATE_STDIO_OK" in json.dumps(item.get("output")) for item in outputs), outputs
            assert "error" not in observed_helpers, observed_helpers
            assert observed_helpers, "the real helper child was not observed"
            print("PASS: upstream inputs unchanged, unknown SHA rejected, renamed helper executed JavaScript over grpc+stdio, fd0/fd1 are pipes, helper owns no TCP socket")
        except BaseException:
            print("".join(errors[-12:]))
            raise
        finally:
            watching.clear()
            watcher.join(timeout=2)
            process.stdin.close()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
            server.shutdown()
            server.server_close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--codex", required=True)
    parser.add_argument("--host", required=True)
    args = parser.parse_args()
    main(args.codex, args.host)
