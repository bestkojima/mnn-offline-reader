#!/usr/bin/env python3
import hashlib
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
import prepare_kokoro


class KokoroAssetsTest(unittest.TestCase):
    def fixture(self, root):
        (root / 'data').mkdir()
        source = root / 'source'
        source.mkdir()
        data = b'pinned-model-fixture'
        (source / 'tts.mnn').write_bytes(data)
        lock = {'version': 'kokoro-v1.0', 'files': [{'source': 'tts.mnn', 'path': 'tts.mnn',
                'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest()}]}
        (root / 'data/kokoro-lock.json').write_text(json.dumps(lock))
        return source, root / 'assets'

    def test_repeated_preparation_does_not_package_hard_link_staging_files(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            source, target = self.fixture(root)
            with patch.object(prepare_kokoro, 'APP_ROOT', root):
                prepare_kokoro.prepare(source, target)
                original_manifest = (target / 'manifest.json').read_bytes()
                prepare_kokoro.prepare(source, target)
            self.assertEqual([], list(target.rglob('*.part')))
            self.assertEqual(original_manifest, (target / 'manifest.json').read_bytes())
            self.assertEqual((source / 'tts.mnn').read_bytes(), (target / 'tts.mnn').read_bytes())

    def test_invalid_download_does_not_replace_published_assets(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            source, target = self.fixture(root)
            with patch.object(prepare_kokoro, 'APP_ROOT', root):
                prepare_kokoro.prepare(source, target)
                manifest = (target / 'manifest.json').read_bytes()
                model = (target / 'tts.mnn').read_bytes()
                (source / 'tts.mnn').unlink()
                (source / 'tts.mnn').write_bytes(b'bad-download')
                with self.assertRaisesRegex(ValueError, 'checksum mismatch'):
                    prepare_kokoro.prepare(source, target)
            self.assertEqual(manifest, (target / 'manifest.json').read_bytes())
            self.assertEqual(model, (target / 'tts.mnn').read_bytes())


if __name__ == '__main__':
    unittest.main()
