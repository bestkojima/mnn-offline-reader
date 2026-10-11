"""Build a versioned, read-only common-word dictionary separately from user.db."""
import argparse
import csv
import hashlib
import json
import re
import sqlite3
import unicodedata
from pathlib import Path

APP = Path(__file__).resolve().parents[1]
ECDICT_REVISION = "bc015ed2e24a7abef49fc6dbbb7fe32c1dadaf8b"


def key(text):
    return unicodedata.normalize("NFKC", text).strip().replace("’", "'").lower().strip(".,!?:;\"'()[]{} ")


def number(text):
    try:
        return int(text)
    except (TypeError, ValueError):
        return 0


def common(row):
    return (row.get("oxford") == "1" or number(row.get("collins")) > 0
            or 0 < number(row.get("frq")) <= 30000 or 0 < number(row.get("bnc")) <= 50000
            or bool(set(row.get("tag", "").split()) & {"zk", "gk", "cet4", "cet6", "ky", "toefl", "ielts"}))


def build(source, destination, examples_path):
    examples = json.loads(examples_path.read_text(encoding="utf-8"))
    forced = {key(example["word"]) for example in examples}
    destination.parent.mkdir(parents=True, exist_ok=True)
    staging = destination.with_suffix(".building")
    if staging.exists():
        staging.unlink()
    db = sqlite3.connect(staging)
    db.executescript("""
        PRAGMA user_version=1;
        CREATE TABLE sources(id INTEGER PRIMARY KEY, name TEXT, version TEXT, license TEXT, url TEXT);
        CREATE TABLE entries(id INTEGER PRIMARY KEY, headword TEXT NOT NULL, normalized_key TEXT NOT NULL UNIQUE,
                             phonetic TEXT NOT NULL, source_id INTEGER NOT NULL);
        CREATE TABLE senses(id INTEGER PRIMARY KEY, entry_id INTEGER NOT NULL, meaning TEXT NOT NULL);
        CREATE TABLE forms(normalized_form TEXT NOT NULL, entry_id INTEGER NOT NULL, type TEXT NOT NULL,
                           UNIQUE(normalized_form,entry_id,type));
        CREATE TABLE examples(id INTEGER PRIMARY KEY, entry_id INTEGER NOT NULL, english TEXT NOT NULL,
                              chinese TEXT NOT NULL, source_id INTEGER NOT NULL);
        CREATE INDEX senses_entry ON senses(entry_id);
        CREATE INDEX forms_key ON forms(normalized_form);
        CREATE INDEX examples_entry ON examples(entry_id);
    """)
    db.execute("INSERT INTO sources VALUES(1,?,?,?,?)",
               ("ECDICT", ECDICT_REVISION, "MIT (repository notice)", "https://github.com/skywind3000/ECDICT"))
    db.execute("INSERT INTO sources VALUES(2,?,?,?,?)", ("Project-authored examples", "1", "MNN project license", "local:data/examples.json"))
    with source.open(encoding="utf-8-sig", newline="") as stream:
        for row in csv.DictReader(stream):
            word = key(row["word"])
            if (not common(row) and word not in forced) or not re.fullmatch(r"[a-z][a-z '\-]*", word):
                continue
            meaning = row.get("translation", "").replace("\\n", "\n").strip()
            if not meaning:
                continue
            cursor = db.execute("INSERT OR IGNORE INTO entries(headword,normalized_key,phonetic,source_id) VALUES(?,?,?,1)",
                                (row["word"], word, row.get("phonetic", "")))
            if cursor.rowcount == 0:
                continue
            entry_id = cursor.lastrowid
            db.execute("INSERT INTO senses(entry_id,meaning) VALUES(?,?)", (entry_id, meaning))
            for form in row.get("exchange", "").split("/"):
                kind, separator, values = form.partition(":")
                if not separator or kind == "0":
                    continue
                for value in values.split(","):
                    normalized = key(value)
                    if re.fullmatch(r"[a-z][a-z '\-]*", normalized) and normalized != word:
                        db.execute("INSERT OR IGNORE INTO forms VALUES(?,?,?)", (normalized, entry_id, kind))
    for example in examples:
        entry = db.execute("SELECT id FROM entries WHERE normalized_key=?", (key(example["word"]),)).fetchone()
        if not entry:
            raise ValueError(f"Example word is missing: {example['word']}")
        db.execute("INSERT INTO examples(entry_id,english,chinese,source_id) VALUES(?,?,?,2)",
                   (entry[0], example["english"], example["chinese"]))
    db.commit()
    assert db.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
    counts = {table: db.execute(f"SELECT count(*) FROM {table}").fetchone()[0]
              for table in ("entries", "forms", "examples")}
    db.execute("VACUUM")
    db.close()
    staging.replace(destination)
    manifest = {"schema": 1, "ecdict_revision": ECDICT_REVISION, "ecdict_csv_sha256": hashlib.sha256(source.read_bytes()).hexdigest(),
                "dictionary_sha256": hashlib.sha256(destination.read_bytes()).hexdigest(),
                "bytes": destination.stat().st_size, "counts": counts}
    destination.with_name("dictionary-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(json.dumps(manifest, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("--destination", type=Path, default=APP / "app/src/main/assets/dictionary.db")
    parser.add_argument("--examples", type=Path, default=APP / "data/examples.json")
    args = parser.parse_args()
    build(args.source, args.destination, args.examples)
