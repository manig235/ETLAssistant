#!/usr/bin/env python3
"""Check that a built Youngmin.app and its extensions support iPhone and iPad.

The app must list device families 1 and 2, keep iPhone portrait-only, list all
four iPad orientations (required for Split View, Slide Over and Stage Manager)
and must not opt out of multitasking with UIRequiresFullScreen.
"""
import json
import plistlib
import sys
from pathlib import Path

IPAD_ORIENTATIONS = {
    "UIInterfaceOrientationPortrait",
    "UIInterfaceOrientationPortraitUpsideDown",
    "UIInterfaceOrientationLandscapeLeft",
    "UIInterfaceOrientationLandscapeRight",
}


def load(path: Path) -> dict:
    with path.open("rb") as stream:
        return plistlib.load(stream)


def check(app: Path) -> list[str]:
    errors = []
    info = load(app / "Info.plist")
    bundles = {"app": info}
    for appex in sorted((app / "PlugIns").glob("*.appex")):
        bundles[appex.stem] = load(appex / "Info.plist")
    for name, plist in bundles.items():
        family = plist.get("UIDeviceFamily")
        if sorted(family or []) != [1, 2]:
            errors.append(f"{name}: UIDeviceFamily is {family}, expected [1, 2]")
    ipad = set(info.get("UISupportedInterfaceOrientations~ipad") or [])
    if ipad != IPAD_ORIENTATIONS:
        errors.append(f"app: UISupportedInterfaceOrientations~ipad is {sorted(ipad)}")
    iphone = info.get("UISupportedInterfaceOrientations~iphone") or info.get("UISupportedInterfaceOrientations")
    if iphone != ["UIInterfaceOrientationPortrait"]:
        errors.append(f"app: iPhone orientations changed to {iphone}")
    if info.get("UIRequiresFullScreen"):
        errors.append("app: UIRequiresFullScreen disables iPad multitasking")
    if "UILaunchScreen" not in info:
        errors.append("app: UILaunchScreen is missing")
    summary = {
        name: {
            "UIDeviceFamily": plist.get("UIDeviceFamily"),
            "orientations~ipad": plist.get("UISupportedInterfaceOrientations~ipad"),
            "orientations~iphone": plist.get("UISupportedInterfaceOrientations~iphone"),
            "orientations": plist.get("UISupportedInterfaceOrientations"),
            "UIRequiresFullScreen": plist.get("UIRequiresFullScreen"),
            "UIApplicationSceneManifest": plist.get("UIApplicationSceneManifest"),
        }
        for name, plist in bundles.items()
    }
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return errors


def main() -> int:
    if len(sys.argv) != 2:
        print(f"usage: {sys.argv[0]} path/to/Youngmin.app", file=sys.stderr)
        return 2
    app = Path(sys.argv[1])
    if not (app / "Info.plist").is_file():
        print(f"No built app at {app}", file=sys.stderr)
        return 1
    errors = check(app)
    for error in errors:
        print(f"error: {error}", file=sys.stderr)
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
