from pathlib import Path
import shutil
import re

REPO_APK_DIR = Path("repo/apk")

shutil.rmtree(REPO_APK_DIR, ignore_errors=True)
REPO_APK_DIR.mkdir(parents=True, exist_ok=True)

APK_ARTIFACTS_DIR = Path.home().joinpath("apk-artifacts")
print(f"Looking for APKs under {APK_ARTIFACTS_DIR}")

for apk in APK_ARTIFACTS_DIR.glob("**/*.apk"):
    name = apk.name
    # Remove common build suffixes before .apk: -release, -unsigned, -release-unsigned
    base_name = re.sub(r"(-release(?:-unsigned)?|-unsigned)(?=\.apk$)", "", name)

    dest = REPO_APK_DIR.joinpath(base_name)
    # If dest exists, append a numeric suffix to avoid collisions
    if dest.exists():
        stem = dest.stem
        suffix = dest.suffix
        i = 1
        while True:
            candidate = REPO_APK_DIR.joinpath(f"{stem}_{i}{suffix}")
            if not candidate.exists():
                dest = candidate
                break
            i += 1

    print(f"Copying {apk} -> {dest}")
    try:
        shutil.copy2(str(apk), str(dest))
    except Exception as e:
        print(f"Failed to copy {apk} to {dest}: {e}")
