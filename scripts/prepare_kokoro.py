#!/usr/bin/env python3
"""Install the pinned Kokoro package, verifying every asset before publishing."""
import hashlib
import json
import os
import shutil
import sys
from pathlib import Path

APP_ROOT = Path(__file__).resolve().parents[1]


def prepare(source, destination):
    lock = json.loads((APP_ROOT / 'data/kokoro-lock.json').read_text())
    for item in lock['files']:
        data = (source / item['source']).read_bytes()
        if len(data) != item['bytes'] or hashlib.sha256(data).hexdigest() != item['sha256']:
            raise ValueError('Kokoro checksum mismatch: ' + item['source'])
    destination.mkdir(parents=True, exist_ok=True)
    for item in lock['files']:
        original = source / item['source']
        target = destination / item['path']
        target.parent.mkdir(parents=True, exist_ok=True)
        staging = target.with_name(target.name + '.part')
        staging.unlink(missing_ok=True)
        # POSIX rename is a no-op when both names refer to the same hard-linked inode.
        # Avoid creating a second name which would otherwise remain in APK assets.
        if target.exists() and os.path.samefile(original, target):
            continue
        # Same-filesystem links avoid retaining duplicate large, immutable model assets.
        try:
            os.link(original, staging)
        except OSError:
            shutil.copyfile(original, staging)
        staging.replace(target)
    manifest = dict(lock)
    manifest['package_sha256'] = hashlib.sha256(json.dumps(lock, sort_keys=True).encode()).hexdigest()
    manifest['files'] = [{k: v for k, v in f.items() if k != 'source'} for f in lock['files']]
    staging = destination / 'manifest.json.part'
    staging.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
    staging.replace(destination / 'manifest.json')
    print('Kokoro assets:', sum(f['bytes'] for f in lock['files']))


if __name__ == '__main__':
    prepare(Path(sys.argv[1]), APP_ROOT / 'models/reader-tts-staged/kokoro')
