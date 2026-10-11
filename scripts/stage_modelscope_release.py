#!/usr/bin/env python3
"""Stage only the app's pinned TTS resources; never copy download caches."""
import hashlib
import json
import os
import shutil
from pathlib import Path

APP = Path(__file__).resolve().parents[1]
RELEASE = APP / 'models/reader-modelscope-release'


def stage():
    packs = []
    for name, repository in [('supertonic', 'dr3334/Supertonic-3-INT8-MNN'),
                              ('kokoro', 'dr3334/Kokoro-82M-v1.0-MNN')]:
        source = APP / 'models/reader-tts-staged' / name
        manifest = json.loads((source / 'manifest.json').read_text())
        files = list(manifest['files'])
        if name == 'supertonic':
            license_file = source / 'LICENSE'
            files.append({'path': 'LICENSE', 'bytes': license_file.stat().st_size,
                          'sha256': hashlib.sha256(license_file.read_bytes()).hexdigest()})
        destination = RELEASE / name
        destination.mkdir(parents=True, exist_ok=True)
        for item in files:
            original = source / item['path']
            if original.stat().st_size != item['bytes'] or hashlib.sha256(original.read_bytes()).hexdigest() != item['sha256']:
                raise ValueError('Source checksum mismatch: ' + str(original))
            target = destination / item['path']
            target.parent.mkdir(parents=True, exist_ok=True)
            if target.exists() and os.path.samefile(original, target):
                continue
            target.unlink(missing_ok=True)
            os.link(original, target)
        manifest['files'] = files
        (destination / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
        shutil.copyfile(APP / 'app/src/main/assets/NOTICE.txt', destination / 'NOTICE.txt')
        if name == 'supertonic':
            description = '''# Supertonic 3 MNN int8 — Android Reader release

Fixed, unmodified resources previously bundled by MnnEnglishReader. Original model:
https://huggingface.co/Supertone/supertonic-3 ; conversion:
https://huggingface.co/yunfengwang/supertonic-tts-mnn at c0425ea0b5884cdb9c23a99b2373dcdb47f142fa.
License: BigScience Open RAIL-M; see LICENSE and NOTICE.txt. This is not an Apache relicensing.
Four int8 graphs, model configuration, Unicode indexer and ten F1–F5/M1–M5 voice styles.
31 languages excluding Chinese. 44,100 Hz mono audio. Use <en>text</en> or another supported language tag.
'''
        else:
            description = '''---
license: apache-2.0
---
# Kokoro-82M v1.0 MNN — Android Reader Mandarin release

Fixed, unmodified resources previously bundled by MnnEnglishReader, not v1.1-zh.
Conversion: https://huggingface.co/wangjazz/kokoro-82m-mnn at c161c9db569b944c35e06f5c3042e4c575aee4a9.
Original model: https://huggingface.co/hexgrad/Kokoro-82M (Apache-2.0).
Frontend: https://huggingface.co/csukuangfj/kokoro-multi-lang-v1_0 at f7b96bb6bef5c5da4d3aa4f4e0498fbbf62dc78b.
Voice source: https://huggingface.co/onnx-community/Kokoro-82M-v1.0-ONNX at 1939ad2a8e416c0acfeecc08a694d14ef25f2231.
Selected Mandarin voices: zf_xiaoxiao (female), zm_yunxi (male). See MODEL-LICENSE, FRONTEND-LICENSE and NOTICE.txt.
MNN Module inputs: style (1×256), input_ids (1×N int32), speed (1 float32); output waveform, 24,000 Hz mono.
The app uses a lexicon frontend, at most 200 phoneme IDs plus leading/trailing zero padding.
Voice binary is little-endian float32, 510×256; select row at unpadded phoneme length.
Chinese tokens must come from the matching tokenizer/lexicons; do not add Supertonic language tags.
'''
        description += '''
Download only the paths listed in manifest.json. The app pins a ModelScope commit revision,
uses HTTPS Range requests to resume .part files, then verifies size and SHA-256 before atomic publication.
No reconversion or requantization was performed for this upload.

Runtime: Alibaba MNN built from source, Android arm64, -O3 -DNDEBUG, LOW_MEMORY and ARM82 enabled.
Java frontend → JNI → MNN Module API. Source and build instructions:
https://github.com/bestkojima/mnn-offline-reader ; see README for standalone build instructions.
Host real synthesis and cancellation/recovery passed; this does not establish Android latency or voice quality.
After a verified download, synthesis stays offline; selected text is never included in download requests.
'''
        (destination / 'README.md').write_text(description)
        packs.append({'id': name, 'repository': repository, 'source': str(destination),
                      'download_bytes': sum(f['bytes'] for f in files)})
    (RELEASE / 'release-plan.json').write_text(json.dumps(packs, indent=2) + '\n')
    return packs


if __name__ == '__main__':
    print(json.dumps(stage(), indent=2))
