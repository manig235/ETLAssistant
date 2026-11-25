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
version = tomllib.loads(text)["workspace"]["package"]["version"]
# Release packaging updates workspace.package.version but retains 0.0.0 for
# local crates in Cargo.lock. Normalize only those local package versions;
# registry/git versions and checksums remain exactly as upstream locked them.
blocks = lock_text.split("[[package]]")
lock_text = "[[package]]".join(
    block.replace('version = "0.0.0"', f'version = "{version}"')
    if "source = " not in block else block
    for block in blocks
)
# rustls-native-certs treats iOS as Unix and searches desktop certificate files.
# Supply the already-locked Mozilla roots when that store is empty, preserving
# explicit SSL_CERT_FILE/SSL_CERT_DIR overrides and normal chain/hostname checks.
http_manifest = workspace / "http-client/Cargo.toml"
with http_manifest.open("a") as file:
    file.write('\n[target.\'cfg(target_os = "ios")\'.dependencies]\nwebpki-roots = "=1.0.10"\n')
native_roots = workspace / "http-client/src/native_roots.rs"
roots_text = native_roots.read_text()
anchor = "    let _ = roots.add_parsable_certificates(certs);"
assert roots_text.count(anchor) == 1
native_roots.write_text(roots_text.replace(anchor, anchor + '''
    #[cfg(target_os = "ios")]
    if roots.is_empty()
        && std::env::var_os("SSL_CERT_FILE").is_none()
        && std::env::var_os("SSL_CERT_DIR").is_none()
    {
        roots.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());
    }'''))
blocks = lock_text.split("[[package]]")
for index, block in enumerate(blocks):
    if '\nname = "codex-http-client"\n' in block:
        parsed = tomllib.loads("[[package]]" + block)["package"][0]
        dependencies = sorted(parsed["dependencies"] + ["webpki-roots"])
        start = block.index("dependencies = [\n")
        end = block.index("\n]", start) + 2
        new_dependencies = "dependencies = [\n" + "".join(f' "{dep}",\n' for dep in dependencies) + "]"
        blocks[index] = block[:start] + new_dependencies + block[end:]
        break
else:
    raise AssertionError("missing codex-http-client lock entry")
lock_text = "[[package]]".join(blocks)
mxc_revision = "b21120168d70befe12356411423266bfef168446"
old_mxc = f'mxc-sdk = {{ git = "https://github.com/microsoft/mxc", rev = "{mxc_revision}" }}'
assert old_mxc in text
text = text.replace(old_mxc, 'mxc-sdk = { path = "../youngmin-vendor/mxc/src/mxc-sdk" }')
mxc_source = f'source = "git+https://github.com/microsoft/mxc?rev={mxc_revision}#{mxc_revision}"\n'
assert lock_text.count(mxc_source) == 1
lock_text = lock_text.replace(mxc_source, "")
dispatch = workspace.parent / "youngmin-vendor/mxc/src/mxc-sdk/src/core/mxc_engine/dispatch.rs"
dispatch_text = dispatch.read_text()
policy_log = "    crate::mxc_engine::run::log_policy_hash(request, logger);"
assert dispatch_text.count(policy_log) == 2
assert dispatch_text.count("    ensure_host_supported()?;") == 2
# The module containing the logger is desktop-only. iOS already returns an
# UnsupportedContainment error before this point; preserve that rejection.
dispatch.write_text(dispatch_text.replace(policy_log,
    '#[cfg(any(target_os = "windows", target_os = "linux", target_os = "macos"))]\n' + policy_log))
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
with lock.open("a") as file:
    file.write(f'\n[[package]]\nname = "youngmin-codex-bridge"\nversion = "{version}"\ndependencies = [\n')
    for dependency in sorted(deps):
        file.write(f' "{identifiers[dependency]}",\n')
    file.write("]\n")
