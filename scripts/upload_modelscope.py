#!/usr/bin/env python3
"""Publish the exact staged release, verify the remote listing, emit pinned app catalog.

Run with: uv run --with modelscope-hub python scripts/upload_modelscope.py
Credentials are read from the configured SDK, with legacy stored-cookie fallback.
No credential is written to an app resource or upload report.
"""
import hashlib
import json
import pickle
import subprocess
from datetime import datetime
from pathlib import Path
from zoneinfo import ZoneInfo

import requests
from modelscope_hub import HubApi
from modelscope_hub.config import get_default_config

APP = Path(__file__).resolve().parents[1]
RELEASE = APP / 'models/reader-modelscope-release'


def main():
    config = get_default_config()
    if not config.token:
        cookie_path = Path.home() / '.modelscope/credentials/cookies'
        if cookie_path.exists():
            # Trusted, pre-existing local SDK credential file; do not print cookies.
            cookies = pickle.loads(cookie_path.read_bytes())
            token = next((c.value for c in cookies if c.name == 'm_session_id'), None)
            api = HubApi(token=token)
        else:
            api = HubApi()
    else:
        api = HubApi()
    user = api.whoami().username
    plan = json.loads((RELEASE / 'release-plan.json').read_text())
    catalog, reports = [], []
    for pack in plan:
        repo = pack['repository']
        if repo.split('/')[0] != user:
            raise ValueError('Repository owner must match the configured account')
        source = Path(pack['source'])
        files = {str(f.relative_to(source)): {'bytes': f.stat().st_size,
                    'sha256': hashlib.sha256(f.read_bytes()).hexdigest()}
                 for f in source.rglob('*') if f.is_file()}
        if not api.repo_exists(repo, 'model'):
            api.create_repo(repo, 'model', visibility='public',
                            license='apache-2.0' if pack['id'] == 'kokoro' else 'other',
                            description='Pinned MNN Android Reader TTS resources; see original model license in README')
        print('Uploading', repo, 'files:', len(files), flush=True)
        result = api.upload_folder(repo, 'model', source, commit_message='Publish pinned Android Reader TTS download resources',
                                   max_workers=2, disable_tqdm=True, tracker_path=RELEASE / (pack['id'] + '-upload-cache.json'))
        if isinstance(result, dict) and result.get('failed_files'):
            raise RuntimeError('ModelScope reports failed files')
        revision = subprocess.check_output(['git', 'ls-remote', 'https://modelscope.cn/' + repo + '.git',
                                            'refs/heads/master'], text=True, timeout=60).split()[0]
        remote = {f.path: f for f in api.list_repo_files(repo, 'model', revision=revision) if not f.is_dir}
        for path, expected in files.items():
            actual = remote.get(path)
            if not actual or actual.size != expected['bytes']:
                raise ValueError('Remote file missing or size mismatch: ' + path)
            digest = actual.sha256 or (actual.lfs or {}).get('oid', '').removeprefix('sha256:')
            if not digest:
                response = requests.get('https://modelscope.cn/api/v1/models/' + repo + '/repo',
                                        params={'Revision': revision, 'FilePath': path}, timeout=60)
                response.raise_for_status()
                digest = hashlib.sha256(response.content).hexdigest()
            if digest != expected['sha256']:
                raise ValueError('Remote checksum mismatch: ' + path)
        manifest = json.loads((source / 'manifest.json').read_text())
        manifest.update({'id': pack['id'], 'name': '中文语音' if pack['id'] == 'kokoro' else '英语及其他语言语音',
                         'modelscope_repository': repo, 'modelscope_revision': revision,
                         'directory': pack['id'] + '-' + (manifest.get('package_sha256') or manifest['revision'])})
        catalog.append(manifest)
        report = {'date': datetime.now(ZoneInfo('Asia/Shanghai')).isoformat(), 'source': str(source),
                  'repository': repo, 'url': 'https://modelscope.cn/models/' + repo, 'revision': revision,
                  'commit_message': 'Publish pinned Android Reader TTS download resources',
                  'status': 'verified', 'uploaded_files': len(files), 'remote_files': len(remote),
                  'download_bytes': pack['download_bytes'], 'files': files}
        reports.append(report)
        (RELEASE / (pack['id'] + '-verification.json')).write_text(json.dumps(report, indent=2) + '\n')
        print('Verified', repo, revision, len(files), 'files', flush=True)
    (APP / 'data/modelscope-release.json').write_text(json.dumps(reports, ensure_ascii=False, indent=2) + '\n')
    (APP / 'app/src/main/assets/model-catalog.json').write_text(json.dumps({'packs': catalog}, ensure_ascii=False, indent=2) + '\n')


if __name__ == '__main__':
    main()
