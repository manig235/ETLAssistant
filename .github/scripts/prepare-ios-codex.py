#!/usr/bin/env python3
"""Install our bridge into a verified Codex source tree without updating dependencies."""
from pathlib import Path
import shutil
import sys
import tomllib

root = Path(__file__).resolve().parents[2]
workspace = Path(sys.argv[1]).resolve() / "codex-rs"
bridge = workspace / "youngmin-ios-bridge"
shutil.copytree(root / "ios/NativeCodex/bridge", bridge, dirs_exist_ok=True)

manifest = workspace / "Cargo.toml"
text = manifest.read_text()
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
lock = workspace / "Cargo.lock"
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
