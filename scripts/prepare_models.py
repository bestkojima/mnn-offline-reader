"""Stage pinned v3/int8 release resources outside the APK, with content checksums."""
import argparse
import hashlib
import json
import shutil
from pathlib import Path

REVISION = "c0425ea0b5884cdb9c23a99b2373dcdb47f142fa"
APP = Path(__file__).resolve().parents[1]
MODEL_NAMES = ("duration_predictor", "text_encoder", "vector_estimator", "vocoder")


def prepare(source, destination):
    lock = json.loads((APP / "data/model-lock.json").read_text())
    expected = {item["path"]: item for item in lock["files"]}
    files = [f"mnn_models/int8/{name}.mnn" for name in MODEL_NAMES]
    files += ["mnn_models/tts.json", "mnn_models/unicode_indexer.json"]
    files += [f"voice_styles/{gender}{n}.json" for gender in "FM" for n in range(1, 6)]
    config = json.loads((source / "v3/mnn_models/tts.json").read_text())
    assert config["ae"]["sample_rate"] == 44100
    metadata = []
    for relative in files:
        original = source / "v3" / relative
        if not original.is_file() or original.stat().st_size < 10:
            raise ValueError(f"Missing model asset: {original}")
        checksum = hashlib.sha256(original.read_bytes()).hexdigest()
        if original.stat().st_size != expected[relative]["bytes"] or checksum != expected[relative]["sha256"]:
            raise ValueError(f"Asset does not match pinned v3/int8 revision: {original}")
        target = destination / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(original, target)
        metadata.append({"path": relative, "bytes": target.stat().st_size,
                         "sha256": hashlib.sha256(target.read_bytes()).hexdigest()})
    license_path = source / "MODEL-LICENSE"
    if not license_path.is_file():
        raise ValueError("MODEL-LICENSE is required; retain the upstream model license")
    if hashlib.sha256(license_path.read_bytes()).hexdigest() != lock["license_sha256"]:
        raise ValueError("Model license does not match the pinned upstream release")
    shutil.copyfile(license_path, destination / "LICENSE")
    (destination / "manifest.json").write_text(json.dumps({
        "repository": "yunfengwang/supertonic-tts-mnn", "revision": REVISION,
        "version": "v3", "precision": "int8", "files": metadata,
    }, indent=2) + "\n")
    print(f"Staged {len(metadata)} assets, {sum(f['bytes'] for f in metadata):,} bytes: {destination}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("--destination", type=Path, default=APP / "models/reader-tts-staged/supertonic")
    args = parser.parse_args()
    prepare(args.source, args.destination)
