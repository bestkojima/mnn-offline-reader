package com.alibaba.mnn.english;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.util.ArrayList;
import java.util.List;

final class DictionaryStore {
    private final ModelAssets assets;
    private SQLiteDatabase database;
    DictionaryStore(ModelAssets assets) { this.assets = assets; }

    LookupResult lookup(String text) throws Exception {
        if (database == null) database = SQLiteDatabase.openDatabase(assets.dictionary().getPath(), null, SQLiteDatabase.OPEN_READONLY);
        String key = TextNormalizer.lookupKey(text);
        LookupResult result = new LookupResult(text);
        try (Cursor cursor = database.rawQuery("SELECT id,headword,phonetic FROM ("
                + "SELECT id,headword,phonetic,0 AS priority FROM entries WHERE normalized_key=? "
                + "UNION ALL SELECT e.id,e.headword,e.phonetic,1 AS priority FROM forms f JOIN entries e ON e.id=f.entry_id "
                + "WHERE f.normalized_form=?) ORDER BY priority,id LIMIT 1", new String[]{key, key})) {
            if (!cursor.moveToFirst()) return result;
            long id = cursor.getLong(0);
            result.headword = cursor.getString(1);
            result.phonetic = cursor.getString(2);
            try (Cursor senses = database.rawQuery("SELECT meaning FROM senses WHERE entry_id=? ORDER BY id", new String[]{Long.toString(id)})) {
                while (senses.moveToNext()) result.meanings.add(senses.getString(0));
            }
            try (Cursor examples = database.rawQuery("SELECT english,chinese FROM examples WHERE entry_id=? ORDER BY id LIMIT 3", new String[]{Long.toString(id)})) {
                while (examples.moveToNext()) result.examples.add(new Example(examples.getString(0), examples.getString(1)));
            }
        }
        return result;
    }

    static final class LookupResult {
        final String selected;
        String headword = "", phonetic = "";
        final List<String> meanings = new ArrayList<>();
        final List<Example> examples = new ArrayList<>();
        LookupResult(String selected) { this.selected = selected; }
        boolean found() { return !headword.isEmpty(); }
    }
    static final class Example {
        final String english, chinese;
        Example(String english, String chinese) { this.english = english; this.chinese = chinese; }
    }
}
