import csv
import importlib.util
import json
import sqlite3
import tempfile
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location("build_dictionary", Path(__file__).with_name("build_dictionary.py"))
builder = importlib.util.module_from_spec(spec)
spec.loader.exec_module(builder)


class DictionaryTests(unittest.TestCase):
    def test_curated_filter_inflection_and_bilingual_examples_survive_build(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source, examples, target = root / "words.csv", root / "examples.json", root / "dictionary.db"
            with source.open("w", newline="", encoding="utf-8") as stream:
                writer = csv.DictWriter(stream, fieldnames=["word", "translation", "phonetic", "frq", "exchange"])
                writer.writeheader()
                writer.writerows([
                    {"word": "go", "translation": "v. 去", "frq": "1", "exchange": "p:went/d:gone"},
                    {"word": "saw", "translation": "n. 锯", "frq": "100"},
                    {"word": "see", "translation": "v. 看见", "frq": "2", "exchange": "p:saw"},
                    {"word": "curious", "translation": "adj. 好奇的\\n求知欲强的", "frq": "50", "phonetic": "ˈkjʊəriəs"},
                    {"word": "rareword", "translation": "罕见", "frq": "99000"},
                ])
            examples.write_text(json.dumps([{"word": "curious", "english": "A curious child asked a question.", "chinese": "一个好奇的孩子问了一个问题。"}]))
            builder.build(source, target, examples)
            with sqlite3.connect(target) as db:
                self.assertEqual(db.execute("PRAGMA integrity_check").fetchone()[0], "ok")
                self.assertEqual(db.execute("SELECT count(*) FROM entries").fetchone()[0], 4)
                self.assertEqual(db.execute("SELECT e.headword FROM forms f JOIN entries e ON e.id=f.entry_id WHERE normalized_form='went'").fetchone()[0], "go")
                self.assertEqual(db.execute("SELECT english,chinese FROM examples").fetchone(), ("A curious child asked a question.", "一个好奇的孩子问了一个问题。"))
                self.assertEqual(db.execute("SELECT name FROM sources WHERE id=1").fetchone()[0], "ECDICT")
            manifest = json.loads(target.with_name("dictionary-manifest.json").read_text())
            self.assertEqual(manifest["bytes"], target.stat().st_size)
            self.assertEqual(manifest["counts"]["examples"], 1)

    def test_missing_example_word_does_not_replace_existing_database(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            target = root / "dictionary.db"
            target.write_bytes(b"existing dictionary")
            source = root / "words.csv"
            source.write_text("word,translation,frq\ngo,v. 去,1\n")
            examples = root / "examples.json"
            examples.write_text('[{"word":"missing","english":"Missing.","chinese":"缺失。"}]')
            with self.assertRaises(ValueError): builder.build(source, target, examples)
            self.assertEqual(target.read_bytes(), b"existing dictionary")


if __name__ == "__main__":
    unittest.main()
