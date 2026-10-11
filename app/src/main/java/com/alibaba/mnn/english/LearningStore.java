package com.alibaba.mnn.english;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.List;

final class LearningStore extends SQLiteOpenHelper {
    LearningStore(Context context) { super(context, "user.db", null, 1); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE bookmarks(word TEXT PRIMARY KEY, selected TEXT NOT NULL, created INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE history(text TEXT PRIMARY KEY, accessed INTEGER NOT NULL)");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        throw new IllegalStateException("No migration exists for this user database version");
    }
    boolean saved(String word) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT 1 FROM bookmarks WHERE word=?", new String[]{word})) { return c.moveToFirst(); }
    }
    boolean toggle(String word, String selected) {
        SQLiteDatabase db = getWritableDatabase();
        if (saved(word)) { db.delete("bookmarks", "word=?", new String[]{word}); return false; }
        ContentValues value = new ContentValues(); value.put("word", word); value.put("selected", selected); value.put("created", System.currentTimeMillis());
        db.insertOrThrow("bookmarks", null, value); return true;
    }
    void record(String text) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues value = new ContentValues(); value.put("text", text); value.put("accessed", System.currentTimeMillis());
        db.insertWithOnConflict("history", null, value, SQLiteDatabase.CONFLICT_REPLACE);
        db.execSQL("DELETE FROM history WHERE text NOT IN (SELECT text FROM history ORDER BY accessed DESC LIMIT 200)");
    }
    List<String> bookmarks() {
        List<String> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT word FROM bookmarks ORDER BY created DESC", null)) { while (c.moveToNext()) result.add(c.getString(0)); }
        return result;
    }
}
