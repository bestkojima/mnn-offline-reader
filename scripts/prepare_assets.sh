#!/usr/bin/env bash
set -euo pipefail
reader_root="$(cd "$(dirname "$0")/.." && pwd)"
reader_repo="$reader_root"
reader_dictionary="$reader_repo/models/english-reader-dictionary"
mkdir -p "$reader_dictionary"
if [[ ! -f "$reader_dictionary/ecdict.csv" ]]; then
    curl -fL --retry 2 --max-time 300 \
        https://raw.githubusercontent.com/skywind3000/ECDICT/bc015ed2e24a7abef49fc6dbbb7fe32c1dadaf8b/ecdict.csv \
        -o "$reader_dictionary/ecdict.csv.part"
    mv "$reader_dictionary/ecdict.csv.part" "$reader_dictionary/ecdict.csv"
fi
python3 - "$reader_dictionary/ecdict.csv" <<'PY'
import hashlib, sys
from pathlib import Path
expected = "1a6947e04785db63613a92e14903cdae7954f7e84860b10e68e5c7cbb3f9c3cf"
if hashlib.sha256(Path(sys.argv[1]).read_bytes()).hexdigest() != expected:
    raise SystemExit("ECDICT CSV checksum mismatch; existing data was not overwritten")
PY
python3 "$reader_root/scripts/build_dictionary.py" "$reader_dictionary/ecdict.csv"
