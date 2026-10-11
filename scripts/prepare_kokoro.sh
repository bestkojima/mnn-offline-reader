#!/usr/bin/env bash
set -euo pipefail
reader_root="$(cd "$(dirname "$0")/.." && pwd)"
reader_repo="$reader_root"
reader_kokoro="$reader_repo/models/english-reader-kokoro"
export HF_ENDPOINT="${HF_ENDPOINT:-https://hf-mirror.com}"
uvx --from huggingface_hub hf download wangjazz/kokoro-82m-mnn tts.mnn tokenizer.txt tts_config.json \
    --revision c161c9db569b944c35e06f5c3042e4c575aee4a9 --local-dir "$reader_kokoro" --max-workers 2
uvx --from huggingface_hub hf download csukuangfj/kokoro-multi-lang-v1_0 lexicon-zh.txt lexicon-us-en.txt LICENSE \
    --revision f7b96bb6bef5c5da4d3aa4f4e0498fbbf62dc78b --local-dir "$reader_kokoro/frontend" --max-workers 2
uvx --from huggingface_hub hf download onnx-community/Kokoro-82M-v1.0-ONNX voices/zf_xiaoxiao.bin voices/zm_yunxi.bin \
    --revision 1939ad2a8e416c0acfeecc08a694d14ef25f2231 --local-dir "$reader_kokoro/voices-source" --max-workers 2
python3 "$reader_root/scripts/prepare_kokoro.py" "$reader_kokoro"
