#!/usr/bin/env python3
"""Package existing builds without runtime configs, credentials, or caches."""

import hashlib
from pathlib import Path
import re
import subprocess
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED

ROOT = Path(__file__).resolve().parent.parent
SOURCE_DIRS = ('src', 'tools', 'docs', 'gradle', '.github',
               'versions/mc26.2/src', 'versions/mc26.2/gradle')
SOURCE_FILES = ('README.md', 'LICENSE', 'AGENTS.md', '.gitignore', '.gitattributes',
                'build.gradle', 'settings.gradle', 'gradle.properties', 'gradlew', 'gradlew.bat')
INSTALL_FILES = ('README.md', 'LICENSE', 'tools/notify.py', 'tools/setup.py',
                 'docs/setup.md', 'docs/history.md', 'docs/protocol.md', 'docs/release.md')


def write_bundle(path, files):
    with ZipFile(path, 'w', compression=ZIP_DEFLATED, compresslevel=9) as bundle:
        for name, file in sorted(files.items()):
            info = ZipInfo(name, date_time=(2020, 1, 1, 0, 0, 0))
            info.create_system = 3
            mode = 0o755 if name.endswith('gradlew') else 0o644
            info.external_attr = (0o100000 | mode) << 16
            info.compress_type = ZIP_DEFLATED
            bundle.writestr(info, file.read_bytes(), compresslevel=9)


def main():
    version = re.search(r'^mod_version=(.+)$', (ROOT / 'gradle.properties').read_text(), re.M)[1]
    targets = {
        'mc1.21.1': ROOT / 'build/libs' / f'minenotify-{version}.jar',
        'mc26.2': ROOT / 'versions/mc26.2/build/libs' / f'minenotify-{version}-mc26.2.jar',
    }
    try:
        result = subprocess.run(['git', '-C', str(ROOT), 'diff', '--quiet', 'HEAD'], stderr=subprocess.PIPE)
    except OSError:
        raise SystemExit('Release packaging requires Git and a clean Git checkout.') from None
    if result.returncode == 1:
        raise SystemExit('Commit source changes before packaging a release. A clean Git checkout is required.')
    if result.returncode != 0:
        raise SystemExit('Release packaging requires Git and a clean Git checkout.')
    for target, jar in targets.items():
        if not jar.is_file():
            raise SystemExit(f'Build {target} before packaging. Missing {jar.relative_to(ROOT)}.')
    out = ROOT / 'dist'
    out.mkdir(exist_ok=True)
    outputs = []
    for target, jar in targets.items():
        path = out / f'minenotify-{version}-{target}.zip'
        write_bundle(path, {jar.name: jar, **{name: ROOT / name for name in INSTALL_FILES}})
        outputs.append(path)
    try:
        tracked = subprocess.check_output(['git', '-C', str(ROOT), 'ls-files', '-z'], stderr=subprocess.PIPE)
    except (OSError, subprocess.CalledProcessError):
        raise SystemExit('Source packaging requires a Git checkout with tracked source files.') from None
    files = {}
    for name in tracked.decode('utf-8').split('\0'):
        if name and (name in SOURCE_FILES or name.startswith(tuple(directory + '/' for directory in SOURCE_DIRS))
                     or name.startswith('versions/mc26.2/') and name.removeprefix('versions/mc26.2/') in SOURCE_FILES):
            files[name] = ROOT / name
    source = out / f'minenotify-{version}-source.zip'
    write_bundle(source, files)
    outputs.append(source)
    manifest = out / f'minenotify-{version}-SHA256SUMS.txt'
    manifest.write_text(''.join(f'{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.name}\n'
                               for path in sorted(outputs)), encoding='utf-8', newline='\n')
    for path in [*outputs, manifest]:
        print(f'{path.relative_to(ROOT)} ({path.stat().st_size:,} bytes)')


if __name__ == '__main__':
    main()
