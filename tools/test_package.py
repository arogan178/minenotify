#!/usr/bin/env python3
"""Verify the release users download, including deterministic archive bytes."""

import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parent.parent
version = re.search(r'^mod_version=(.+)$', (ROOT / 'gradle.properties').read_text(), re.M)[1]
command = [sys.executable, str(ROOT / 'tools/package_release.py')]
sentinel = ROOT / 'tools/.minenotify-package-check.env'
assert not sentinel.exists()
try:
    sentinel.write_text('Untracked private scratch data must not ship.\n')
    subprocess.run(command, check=True, cwd=ROOT)
finally:
    sentinel.unlink(missing_ok=True)
paths = sorted((ROOT / 'dist').glob(f'minenotify-{version}-*.zip'))
assert len(paths) == 3, 'Both install targets and source bundle must exist'
baseline = {path.name: path.read_bytes() for path in paths}
file = ROOT / 'README.md'
stat = file.stat()
try:
    os.utime(file, (stat.st_atime, stat.st_mtime + 60))
    subprocess.run(command, check=True, cwd=ROOT)
finally:
    os.utime(file, ns=(stat.st_atime_ns, stat.st_mtime_ns))
assert baseline == {path.name: path.read_bytes() for path in paths}, 'File timestamps changed release bytes'
for path in paths:
    with ZipFile(path) as archive:
        names = archive.namelist()
        assert names == sorted(names) and len(names) == len(set(names)), 'Archive entries must be unique and ordered'
        for name in names:
            parts = Path(name).parts
            assert not {'run', '.git', '.gradle', '__pycache__', '.work', 'config', '.claude', '.codex'}.intersection(parts), name
            assert not name.endswith(('minenotify.json', '.pyc')), name
            assert name != 'tools/.minenotify-package-check.env', 'Untracked scratch data entered source bundle'
        assert 'tools/setup.py' in names and 'docs/history.md' in names
        if path.name.endswith('-source.zip'):
            assert 'versions/mc26.2/build.gradle' in names
            assert '.github/workflows/checks.yml' in names
            assert archive.getinfo('gradlew').external_attr >> 16 & 0o111, 'Source wrapper must remain executable'
        else:
            jar_name = next(name for name in names if name.endswith('.jar'))
            import io
            with ZipFile(io.BytesIO(archive.read(jar_name))) as jar:
                metadata = json.loads(jar.read('fabric.mod.json'))
                assert metadata['id'] == 'minenotify' and metadata['version'] == version
readme = ROOT / 'README.md'
original = readme.read_bytes()
try:
    readme.write_bytes(original + b'\nTemporary private scratch content.\n')
    result = subprocess.run(command, cwd=ROOT, capture_output=True, text=True)
    assert result.returncode != 0 and 'Commit source changes' in result.stderr, 'Dirty tracked source must not be packaged'
finally:
    readme.write_bytes(original)
manifest = ROOT / 'dist'  / f'minenotify-{version}-SHA256SUMS.txt'
for line in manifest.read_text().splitlines():
    digest, name = line.split('  ', 1)
    assert hashlib.sha256((ROOT / 'dist' / name).read_bytes()).hexdigest() == digest, name
print('Release bundles verified: matching metadata, private configs excluded, reproducible bytes, valid checksums.')
