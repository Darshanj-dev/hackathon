#!/usr/bin/env python3
"""Download the Google AI Edge Gallery-listed model, pinned to its published revision.
Run in a virtual environment with huggingface_hub installed. Never disables TLS verification.
"""
import argparse
import hashlib
import json
import logging
import os
from pathlib import Path
import sys

REPO = 'litert-community/gemma-4-E2B-it-litert-lm'
REVISION = '6e5c4f1e395deb959c494953478fa5cec4b8008f'
FILENAME = 'gemma-4-E2B-it.litertlm'
EXPECTED_BYTES = 2588147712
EXPECTED_SHA256 = '181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c'

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=Path.home() / 'Downloads' / 'FindBack-model')
    args = parser.parse_args()
    os.environ.setdefault("HF_HUB_DISABLE_XET", "1")
    logging.getLogger("huggingface_hub").setLevel(logging.ERROR)
    from huggingface_hub import HfApi, hf_hub_download
    info = HfApi().model_info(REPO, revision=REVISION, files_metadata=True)
    file = next((f for f in info.siblings if f.rfilename == FILENAME), None)
    if file is None or file.lfs is None:
        raise RuntimeError('Official file metadata has no LFS SHA256; refusing an unverified download.')
    expected_hash = file.lfs.sha256
    if expected_hash != EXPECTED_SHA256:
        raise RuntimeError("Host checksum differs from the pinned Google Gallery model; refusing download.")
    if file.size != EXPECTED_BYTES:
        raise RuntimeError('Pinned model size differs from the Google Gallery listing; do not substitute another model.')
    args.output.mkdir(parents=True, exist_ok=True)
    print('Downloading Gemma 4 E2B from the Google Gallery-listed repository (2.59 GB).')
    downloaded = Path(hf_hub_download(repo_id=REPO, filename=FILENAME, revision=REVISION, local_dir=args.output))
    digest = hashlib.sha256()
    with downloaded.open('rb') as stream:
        for chunk in iter(lambda: stream.read(8 * 1024 * 1024), b''):
            digest.update(chunk)
    if downloaded.stat().st_size != EXPECTED_BYTES or digest.hexdigest() != expected_hash:
        raise RuntimeError('Model checksum verification failed. Do not import this file.')
    (args.output / (FILENAME + '.sha256')).write_text(f'{expected_hash}  {FILENAME}\n')
    (args.output / 'model-source.json').write_text(json.dumps({'repo': REPO, 'revision': REVISION, 'sha256': expected_hash, 'bytes': EXPECTED_BYTES}, indent=2))
    print(f'Verified model: {downloaded}')
    print('Transfer this .litertlm file to your phone, then tap Import Gemma in FindBack.')

if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print(f'Download not completed ({type(error).__name__}). Check network access and available disk space.', file=sys.stderr)
        print('For HTTP 401/403 from Hugging Face, open the model page and check access; log in with hf auth login if required. Never put credentials in source files.', file=sys.stderr)
        sys.exit(1)
