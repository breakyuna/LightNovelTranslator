#!/usr/bin/env python3
"""Capture allowlisted implementation inputs, never runtime/user directories.

This is source bookkeeping, not a build or test runner. Output must be outside
both repositories. Archives contain working files (including untracked inputs),
HEAD-to-worktree binary patches, deletions, modes and per-file SHA-256 hashes.
"""
import argparse
import hashlib
import io
import json
from pathlib import Path
import subprocess
import tarfile

SCOPES = {
    'lnt': ['app/src', 'app/schemas', 'core', 'tools/rebuild', 'docs', 'gradle', 'gradlew', 'gradlew.bat',
            'app/build.gradle.kts', 'app/proguard-rules.pro', 'build.gradle.kts',
            'settings.gradle.kts', 'gradle.properties', '.github', '.gitignore', 'AGENTS.md', 'README.md'],
    'lg': ['src', 'builtin', 'package.json', 'package-lock.json', 'tsconfig.json',
           'buildtools', 'tsconfig.*.json', '.github', '.gitignore', 'DESIGN.md', 'PRODUCT.md',
           'docs', 'AGENTS.md', 'HANDOFF.md', 'README*.md'],
}
FORBIDDEN = {'.env', 'local.properties', 'keystore.properties', 'config.json'}


def git(root, *args):
    return subprocess.check_output(['git', '-C', str(root), *args])


def capture(name, root, destination):
    listed = git(root, 'ls-files', '-z', '--cached', '--others', '--exclude-standard',
                 '--', *SCOPES[name]).decode().split('\0')
    paths = sorted(set(filter(None, listed)))
    manifest = {'head': git(root, 'rev-parse', 'HEAD').decode().strip(),
                'branch': git(root, 'branch', '--show-current').decode().strip(),
                'scopes': SCOPES[name], 'files': [], 'deleted': []}
    output = destination / (name + '.tar.gz')
    with tarfile.open(output, 'w:gz') as archive:
        for relative in paths:
            path = root / relative
            if path.name in FORBIDDEN or path.suffix.lower() in {'.jks', '.keystore', '.pem', '.key'}:
                raise ValueError('Review forbidden snapshot input: ' + relative)
            if path.is_symlink():
                raise ValueError('Review symbolic link: ' + relative)
            if not path.exists():
                manifest['deleted'].append(relative)
                continue
            data = path.read_bytes()
            info = tarfile.TarInfo('source/' + relative)
            info.size = len(data)
            info.mode = path.stat().st_mode & 0o777
            archive.addfile(info, io.BytesIO(data))
            manifest['files'].append({'path': relative, 'size': len(data),
                                      'mode': info.mode, 'sha256': hashlib.sha256(data).hexdigest()})
        patch = git(root, 'diff', '--binary', 'HEAD', '--', *SCOPES[name])
        for filename, data in [('tracked.patch', patch), ('manifest.json',
                json.dumps(manifest, ensure_ascii=False, indent=2).encode() + b'\n')]:
            info = tarfile.TarInfo(filename)
            info.size = len(data)
            info.mode = 0o600
            archive.addfile(info, io.BytesIO(data))
    # Re-read every archived source entry; no extraction or source mutation.
    with tarfile.open(output, 'r:gz') as archive:
        for entry in manifest['files']:
            data = archive.extractfile('source/' + entry['path']).read()
            if hashlib.sha256(data).hexdigest() != entry['sha256']:
                raise ValueError('Archive digest mismatch')
    return {'archive': output.name, 'sha256': hashlib.sha256(output.read_bytes()).hexdigest(),
            'head': manifest['head'], 'branch': manifest['branch'],
            'files': len(manifest['files']), 'deleted': manifest['deleted']}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--lg', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    lnt = Path(__file__).resolve().parents[2]
    lg = args.lg.resolve()
    output = args.output.resolve()
    for root in (lnt, lg):
        if output == root or root in output.parents:
            parser.error('Output must be outside both repositories')
    output.mkdir(parents=True, exist_ok=False)
    output.chmod(0o700)
    result = {name: capture(name, root, output) for name, root in [('lnt', lnt), ('lg', lg)]}
    (output / 'receipt.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
