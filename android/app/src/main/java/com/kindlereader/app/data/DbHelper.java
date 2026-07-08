package com.kindlereader.app.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * Plain SQLiteOpenHelper (no Room, see android/README.md) backing local
 * storage for documents, highlights and reading progress. Also stores the
 * `lastSyncAt` cursor used by the incremental sync algorithm in docs/API.md.
 */
public class DbHelper extends SQLiteOpenHelper {

    private static final String DB_NAME = "kindlereader.db";
    private static final int DB_VERSION = 1;

    private static final String T_DOCUMENTS = "documents";
    private static final String T_HIGHLIGHTS = "highlights";
    private static final String T_PROGRESS = "progress";
    private static final String T_META = "meta";

    private static DbHelper instance;

    public static synchronized DbHelper getInstance(Context context) {
        if (instance == null) {
            instance = new DbHelper(context.getApplicationContext());
        }
        return instance;
    }

    private DbHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + T_DOCUMENTS + " (" +
                "id TEXT PRIMARY KEY," +
                "title TEXT," +
                "filename TEXT," +
                "size_bytes INTEGER," +
                "page_count INTEGER," +
                "checksum TEXT," +
                "created_at TEXT," +
                "updated_at TEXT," +
                "local_path TEXT," +
                "local_checksum TEXT," +
                "download_status TEXT," +
                "download_progress INTEGER DEFAULT 0," +
                "last_read_page INTEGER DEFAULT 0" +
                ")");

        db.execSQL("CREATE TABLE " + T_HIGHLIGHTS + " (" +
                "id TEXT PRIMARY KEY," +
                "document_id TEXT," +
                "page INTEGER," +
                "rects TEXT," +
                "color TEXT," +
                "note TEXT," +
                "created_at TEXT," +
                "updated_at TEXT," +
                "deleted INTEGER DEFAULT 0," +
                "dirty INTEGER DEFAULT 0" +
                ")");
        db.execSQL("CREATE INDEX idx_highlights_doc ON " + T_HIGHLIGHTS + " (document_id)");

        db.execSQL("CREATE TABLE " + T_PROGRESS + " (" +
                "document_id TEXT PRIMARY KEY," +
                "page INTEGER," +
                "updated_at TEXT," +
                "dirty INTEGER DEFAULT 0" +
                ")");

        db.execSQL("CREATE TABLE " + T_META + " (" +
                "key TEXT PRIMARY KEY," +
                "value TEXT" +
                ")");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // v1 is the first schema version; nothing to migrate yet.
    }

    // ----------------------------------------------------------------------
    // Meta (lastSyncAt, etc)
    // ----------------------------------------------------------------------

    public String getMeta(String key) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.query(T_META, new String[]{"value"}, "key=?", new String[]{key}, null, null, null);
        try {
            if (c.moveToFirst()) {
                return c.getString(0);
            }
            return null;
        } finally {
            c.close();
        }
    }

    public void setMeta(String key, String value) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("key", key);
        cv.put("value", value);
        db.insertWithOnConflict(T_META, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    // ----------------------------------------------------------------------
    // Documents
    // ----------------------------------------------------------------------

    public void upsertDocument(Document d) {
        SQLiteDatabase db = getWritableDatabase();
        Document existing = getDocument(d.id);
        ContentValues cv = new ContentValues();
        cv.put("id", d.id);
        cv.put("title", d.title);
        cv.put("filename", d.filename);
        cv.put("size_bytes", d.sizeBytes);
        cv.put("page_count", d.pageCount == null ? null : d.pageCount);
        cv.put("checksum", d.checksum);
        cv.put("created_at", d.createdAt);
        cv.put("updated_at", d.updatedAt);
        if (existing != null) {
            // Preserve local-only download bookkeeping across metadata updates.
            cv.put("local_path", existing.localPath);
            cv.put("local_checksum", existing.localChecksum);
            cv.put("download_status", existing.downloadStatus);
            cv.put("download_progress", existing.downloadProgress);
            cv.put("last_read_page", existing.lastReadPage);
        } else {
            cv.put("download_status", Document.STATUS_NONE);
            cv.put("download_progress", 0);
            cv.put("last_read_page", 0);
        }
        db.insertWithOnConflict(T_DOCUMENTS, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public void updateDownloadState(String id, String status, int progress, String localPath, String localChecksum) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("download_status", status);
        cv.put("download_progress", progress);
        if (localPath != null) {
            cv.put("local_path", localPath);
        }
        if (localChecksum != null) {
            cv.put("local_checksum", localChecksum);
        }
        db.update(T_DOCUMENTS, cv, "id=?", new String[]{id});
    }

    public void updateLastReadPage(String documentId, int page) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("last_read_page", page);
        db.update(T_DOCUMENTS, cv, "id=?", new String[]{documentId});
    }

    public Document getDocument(String id) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.query(T_DOCUMENTS, null, "id=?", new String[]{id}, null, null, null);
        try {
            if (c.moveToFirst()) {
                return documentFromCursor(c);
            }
            return null;
        } finally {
            c.close();
        }
    }

    public List<Document> getAllDocuments() {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.query(T_DOCUMENTS, null, null, null, null, null, "title COLLATE NOCASE ASC");
        List<Document> out = new ArrayList<Document>();
        try {
            while (c.moveToNext()) {
                out.add(documentFromCursor(c));
            }
            return out;
        } finally {
            c.close();
        }
    }

    public void deleteDocument(String id) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete(T_DOCUMENTS, "id=?", new String[]{id});
        db.delete(T_HIGHLIGHTS, "document_id=?", new String[]{id});
        db.delete(T_PROGRESS, "document_id=?", new String[]{id});
    }

    private Document documentFromCursor(Cursor c) {
        Document d = new Document();
        d.id = c.getString(c.getColumnIndexOrThrow("id"));
        d.title = c.getString(c.getColumnIndexOrThrow("title"));
        d.filename = c.getString(c.getColumnIndexOrThrow("filename"));
        d.sizeBytes = c.getLong(c.getColumnIndexOrThrow("size_bytes"));
        int pageCountIdx = c.getColumnIndexOrThrow("page_count");
        d.pageCount = c.isNull(pageCountIdx) ? null : Integer.valueOf(c.getInt(pageCountIdx));
        d.checksum = c.getString(c.getColumnIndexOrThrow("checksum"));
        d.createdAt = c.getString(c.getColumnIndexOrThrow("created_at"));
        d.updatedAt = c.getString(c.getColumnIndexOrThrow("updated_at"));
        d.localPath = c.getString(c.getColumnIndexOrThrow("local_path"));
        d.localChecksum = c.getString(c.getColumnIndexOrThrow("local_checksum"));
        String status = c.getString(c.getColumnIndexOrThrow("download_status"));
        d.downloadStatus = status == null ? Document.STATUS_NONE : status;
        d.downloadProgress = c.getInt(c.getColumnIndexOrThrow("download_progress"));
        d.lastReadPage = c.getInt(c.getColumnIndexOrThrow("last_read_page"));
        return d;
    }

    // ----------------------------------------------------------------------
    // Highlights
    // ----------------------------------------------------------------------

    public void upsertHighlight(Highlight h) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = highlightToValues(h);
        db.insertWithOnConflict(T_HIGHLIGHTS, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** Replaces a locally-generated id with the server-assigned one after a successful POST. */
    public void replaceHighlightId(String oldId, String newId) {
        SQLiteDatabase db = getWritableDatabase();
        db.execSQL("UPDATE " + T_HIGHLIGHTS + " SET id=? WHERE id=?", new Object[]{newId, oldId});
    }

    public void markHighlightClean(String id) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("dirty", 0);
        db.update(T_HIGHLIGHTS, cv, "id=?", new String[]{id});
    }

    public void hardDeleteHighlight(String id) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete(T_HIGHLIGHTS, "id=?", new String[]{id});
    }

    public List<Highlight> getHighlightsForDocument(String documentId) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.query(T_HIGHLIGHTS, null, "document_id=? AND deleted=0", new String[]{documentId},
                null, null, "page ASC");
        List<Highlight> out = new ArrayList<Highlight>();
        try {
            while (c.moveToNext()) {
                out.add(highlightFromCursor(c));
            }
            return out;
        } finally {
            c.close();
        }
    }

    public List<Highlight> getDirtyHighlights() {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.query(T_HIGHLIGHTS, null, "dirty=1", null, null, null, "created_at ASC");
        List<Highlight> out = new ArrayList<Highlight>();
        try {
            while (c.moveToNext()) {
                out.add(highlightFromCursor(c));
            }
            return out;
        } finally {
            c.close();
        }
    }

    private ContentValues highlightToValues(Highlight h) {
        ContentValues cv = new ContentValues();
        cv.put("id", h.id);
        cv.put("document_id", h.documentId);
        cv.put("page", h.page);
        cv.put("rects", HighlightRect.listToJsonString(h.rects));
        cv.put("color", h.color);
        cv.put("note", h.note);
        cv.put("created_at", h.createdAt);
        cv.put("updated_at", h.updatedAt);
        cv.put("deleted", h.deleted ? 1 : 0);
        cv.put("dirty", h.dirty ? 1 : 0);
        return cv;
    }

    private Highlight highlightFromCursor(Cursor c) {
        Highlight h = new Highlight();
        h.id = c.getString(c.getColumnIndexOrThrow("id"));
        h.documentId = c.getString(c.getColumnIndexOrThrow("document_id"));
        h.page = c.getInt(c.getColumnIndexOrThrow("page"));
        h.rects = HighlightRect.listFromJsonString(c.getString(c.getColumnIndexOrThrow("rects")));
        h.color = c.getString(c.getColumnIndexOrThrow("color"));
        h.note = c.getString(c.getColumnIndexOrThrow("note"));
        h.createdAt = c.getString(c.getColumnIndexOrThrow("created_at"));
        h.updatedAt = c.getString(c.getColumnIndexOrThrow("updated_at"));
        h.deleted = c.getInt(c.getColumnIndexOrThrow("deleted")) != 0;
        h.dirty = c.getInt(c.getColumnIndexOrThrow("dirty")) != 0;
        return h;
    }

    // ----------------------------------------------------------------------
    // Progress
    // ----------------------------------------------------------------------

    public void setLocalProgress(String documentId, int page, String updatedAt, boolean dirty) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("document_id", documentId);
        cv.put("page", page);
        cv.put("updated_at", updatedAt);
        cv.put("dirty", dirty ? 1 : 0);
        db.insertWithOnConflict(T_PROGRESS, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public Progress getProgress(String documentId) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.query(T_PROGRESS, null, "document_id=?", new String[]{documentId}, null, null, null);
        try {
            if (c.moveToFirst()) {
                return progressFromCursor(c);
            }
            return null;
        } finally {
            c.close();
        }
    }

    public List<Progress> getDirtyProgress() {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.query(T_PROGRESS, null, "dirty=1", null, null, null, null);
        List<Progress> out = new ArrayList<Progress>();
        try {
            while (c.moveToNext()) {
                out.add(progressFromCursor(c));
            }
            return out;
        } finally {
            c.close();
        }
    }

    public void markProgressClean(String documentId) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("dirty", 0);
        db.update(T_PROGRESS, cv, "document_id=?", new String[]{documentId});
    }

    private Progress progressFromCursor(Cursor c) {
        Progress p = new Progress();
        p.documentId = c.getString(c.getColumnIndexOrThrow("document_id"));
        p.page = c.getInt(c.getColumnIndexOrThrow("page"));
        p.updatedAt = c.getString(c.getColumnIndexOrThrow("updated_at"));
        p.dirty = c.getInt(c.getColumnIndexOrThrow("dirty")) != 0;
        return p;
    }
}
