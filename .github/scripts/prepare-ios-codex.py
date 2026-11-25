#!/usr/bin/env python3
"""Install our bridge into a verified Codex source tree without updating dependencies."""
from pathlib import Path
import shutil
import sys
import tomllib

root = Path(__file__).resolve().parents[2]
workspace = Path(sys.argv[1]).resolve() / "codex-rs"
manifest = workspace / "Cargo.toml"
text = manifest.read_text()
lock = workspace / "Cargo.lock"
lock_text = lock.read_text()
v8_revision = "90a65f887a0c0f048f3784afbcb320b373c8a2e0"
v8 = workspace.parent / "youngmin-vendor/rusty-v8"
assert tomllib.loads((v8 / "Cargo.toml").read_text())["package"]["version"] == "152.2.0"
old_patch = f'v8 = {{ git = "https://github.com/openai-oss-forks/rusty_v8.git", rev = "{v8_revision}" }}'
assert old_patch in text
text = text.replace(old_patch, 'v8 = { path = "../youngmin-vendor/rusty-v8" }')
# This is the exact same source revision. Its unused Cargo manifest is enough
# for workspace resolution; Cargo must not clone Chromium's nested submodules.
old_source = f'source = "git+https://github.com/openai-oss-forks/rusty_v8.git?rev={v8_revision}#{v8_revision}"\n'
assert lock_text.count(old_source) == 1
lock.write_text(lock_text.replace(old_source, ""))
manifest.write_text(text)
if len(sys.argv) > 2 and sys.argv[2] == "upstream":
    raise SystemExit(0)

bridge = workspace / "youngmin-ios-bridge"
shutil.copytree(root / "ios/NativeCodex/bridge", bridge, dirs_exist_ok=True)

assert '"youngmin-ios-bridge"' not in text
text = text.replace("members = [", 'members = [\n    "youngmin-ios-bridge",', 1)
manifest.write_text(text)

# Codex enables native keyrings on desktop only. keyring 3.6.3 also has a native
# iOS implementation; without this feature its fallback is an in-memory mock.
keyring = workspace / "keyring-store/Cargo.toml"
with keyring.open("a") as file:
    file.write('\n[target.\'cfg(target_os = "ios")\'.dependencies]\nkeyring = { workspace = true, features = ["apple-native"] }\n')

# Add only our new local package to the upstream lockfile. Reuse exact dependency
# identifiers from app-server; never re-resolve or upgrade upstream packages.
packages = tomllib.loads(lock.read_text())["package"]
server = next(p for p in packages if p["name"] == "codex-app-server")
deps = tomllib.loads((bridge / "Cargo.toml").read_text())["dependencies"]
identifiers = {d.split(" ")[0]: d for d in server["dependencies"]}
identifiers["codex-app-server"] = "codex-app-server"
version = tomllib.loads(text)["workspace"]["package"]["version"]
with lock.open("a") as file:
    file.write(f'\n[[package]]\nname = "youngmin-codex-bridge"\nversion = "{version}"\ndependencies = [\n')
    for dependency in sorted(deps):
        file.write(f' "{identifiers[dependency]}",\n')
    file.write("]\n")
