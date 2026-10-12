#!/usr/bin/env python3
"""Package existing builds without runtime configs, credentials, or caches."""

from pathlib import Path
import re
from zipfile import ZipFile, ZIP_DEFLATED

root = Path(__file__).resolve().parent.parent
version = re.search(r"^mod_version=(.+)$", (root / "gradle.properties").read_text(), re.M)[1]
targets = {
    "mc1.21.1": root / "build" / "libs" / f"minenotify-{version}.jar",
    "mc26.2": root / "versions" / "mc26.2" / "build" / "libs" / f"minenotify-{version}-mc26.2.jar",
}
for target, jar in targets.items():
    if not jar.is_file():
        raise SystemExit(f"Build {target} before packaging. Missing {jar.relative_to(root)}.")
out = root / "dist"
out.mkdir(exist_ok=True)
source_dirs = ["src", "tools", "docs", "gradle", "versions/mc26.2/src", "versions/mc26.2/gradle"]
source_files = ["README.md", "LICENSE", ".gitignore", "build.gradle", "settings.gradle", "gradle.properties", "gradlew", "gradlew.bat"]
source_files += [f"versions/mc26.2/{name}" for name in ["build.gradle", "settings.gradle", "gradle.properties", "gradlew", "gradlew.bat"]]
for target, jar in targets.items():
    with ZipFile(out / f"minenotify-{version}-{target}.zip", "w", ZIP_DEFLATED) as bundle:
        bundle.write(jar, jar.name)
        for name in ["README.md", "LICENSE", "tools/notify.py", "docs/protocol.md", "docs/release.md"]:
            bundle.write(root / name, name)
with ZipFile(out / f"minenotify-{version}-source.zip", "w", ZIP_DEFLATED) as bundle:
    for name in source_files:
        bundle.write(root / name, name)
    for directory in source_dirs:
        for file in sorted((root / directory).rglob("*")):
            if file.is_file() and "__pycache__" not in file.parts:
                bundle.write(file, file.relative_to(root))
for file in sorted(out.glob(f"minenotify-{version}-*.zip")):
    print(f"{file.relative_to(root)} ({file.stat().st_size:,} bytes)")
