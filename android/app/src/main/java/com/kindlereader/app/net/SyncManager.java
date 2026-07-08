package com.kindlereader.app.net;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.kindlereader.app.data.DbHelper;
import com.kindlereader.app.data.Document;
import com.kindlereader.app.data.Highlight;
import com.kindlereader.app.data.HighlightRect;
import com.kindlereader.app.data.Progress;
import com.kindlereader.app.util.IsoDate;
import com.kindlereader.app.util.Prefs;
import com.kindlereader.app.util.Sha256;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Implements the "Incremental sync" algorithm from docs/API.md:
 *
 *  1. Load lastSyncAt from local SQLite (null on first run).
 *  2. GET /api/sync?since=lastSyncAt.
 *  3. Upsert documents into local DB; queue downloads for new/changed files.
 *  4. Remove documents in documentsDeleted (and their local file + highlights).
 *  5. Upsert highlights (apply tombstones as deletes); push local-only
 *     highlights created offline first, then merge.
 *  6. Push local progress changes made offline, then store serverTime as the
 *     new lastSyncAt.
 *
 * All network work happens on a background executor; callbacks are delivered
 * on the main thread.
 */
public class SyncManager {

    public interface SyncListener {
        /** Called once metadata sync (steps 1-6, minus file downloads) finishes. */
        void onSyncFinished(boolean success, String errorMessage);

        /** Called as each queued document finishes downloading (or fails). */
        void onDocumentDownloaded(String documentId, boolean success);
    }

    private static final String META_LAST_SYNC_AT = "lastSyncAt";

    private final Context appContext;
    private final DbHelper db;
    private final ApiClient api;
    private final Prefs prefs;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public SyncManager(Context context) {
        this.appContext = context.getApplicationContext();
        this.db = DbHelper.getInstance(appContext);
        this.api = new ApiClient(appContext);
        this.prefs = new Prefs(appContext);
    }

    public void syncNow(final SyncListener listener) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    doSync(listener);
                    postSuccess(listener);
                } catch (ApiException e) {
                    postError(listener, e.getMessage());
                } catch (Exception e) {
                    postError(listener, String.valueOf(e.getMessage()));
                }
            }
        });
    }

    private void postSuccess(final SyncListener listener) {
        if (listener == null) return;
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                listener.onSyncFinished(true, null);
            }
        });
    }

    private void postError(final SyncListener listener, final String message) {
        if (listener == null) return;
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                listener.onSyncFinished(false, message);
            }
        });
    }

    private void doSync(SyncListener listener) throws ApiException {
        // --- Push local changes first, so the server's response to our GET
        // reflects (and doesn't clobber) what we just sent. ---
        pushDirtyHighlights();
        pushDirtyProgress();

        // --- Pull. ---
        String since = db.getMeta(META_LAST_SYNC_AT);
        JSONObject response = api.sync(since);

        String serverTime = response.optString("serverTime", null);

        applyDocuments(response.optJSONArray("documents"));
        applyDeletedDocuments(response.optJSONArray("documentsDeleted"));
        applyHighlights(response.optJSONArray("highlights"));
        applyProgress(response.optJSONArray("progress"));

        if (serverTime != null) {
            db.setMeta(META_LAST_SYNC_AT, serverTime);
        }

        queueDownloads(listener);
    }

    private void pushDirtyHighlights() {
        List<Highlight> dirty = db.getDirtyHighlights();
        for (int i = 0; i < dirty.size(); i++) {
            Highlight h = dirty.get(i);
            try {
                if (h.deleted) {
                    if (!h.isLocalOnly()) {
                        api.deleteHighlight(h.id);
                    }
                    db.hardDeleteHighlight(h.id);
                } else if (h.isLocalOnly()) {
                    JSONObject created = api.createHighlight(h.documentId, h.page,
                            HighlightRect.listToJsonString(h.rects), h.color, h.note);
                    String serverId = created.optString("id", null);
                    if (serverId != null) {
                        db.replaceHighlightId(h.id, serverId);
                        db.markHighlightClean(serverId);
                    }
                } else {
                    api.updateHighlight(h.id, HighlightRect.listToJsonString(h.rects), h.color, h.note);
                    db.markHighlightClean(h.id);
                }
            } catch (ApiException e) {
                // Leave it dirty; we'll retry on the next sync. Offline-first:
                // a single failed push must not abort the whole sync.
            }
        }
    }

    private void pushDirtyProgress() {
        List<Progress> dirty = db.getDirtyProgress();
        for (int i = 0; i < dirty.size(); i++) {
            Progress p = dirty.get(i);
            try {
                api.putProgress(p.documentId, p.page);
                db.markProgressClean(p.documentId);
            } catch (ApiException e) {
                // Retry next sync.
            }
        }
    }

    private void applyDocuments(JSONArray documents) {
        if (documents == null) return;
        for (int i = 0; i < documents.length(); i++) {
            JSONObject o = documents.optJSONObject(i);
            if (o == null) continue;
            Document d = new Document();
            d.id = o.optString("id");
            d.title = o.optString("title", d.id);
            d.filename = o.optString("filename", "");
            d.sizeBytes = o.optLong("sizeBytes", 0);
            d.pageCount = o.isNull("pageCount") ? null : Integer.valueOf(o.optInt("pageCount"));
            d.checksum = o.optString("checksum", "");
            d.createdAt = o.optString("createdAt", null);
            d.updatedAt = o.optString("updatedAt", null);
            db.upsertDocument(d);
        }
    }

    private void applyDeletedDocuments(JSONArray deletedIds) {
        if (deletedIds == null) return;
        for (int i = 0; i < deletedIds.length(); i++) {
            String id = deletedIds.optString(i, null);
            if (id == null) continue;
            Document existing = db.getDocument(id);
            if (existing != null && existing.localPath != null) {
                new File(existing.localPath).delete();
            }
            db.deleteDocument(id);
        }
    }

    private void applyHighlights(JSONArray highlights) {
        if (highlights == null) return;
        for (int i = 0; i < highlights.length(); i++) {
            JSONObject o = highlights.optJSONObject(i);
            if (o == null) continue;
            boolean tombstone = o.optBoolean("deleted", false);
            String id = o.optString("id");
            if (tombstone) {
                db.hardDeleteHighlight(id);
                continue;
            }
            Highlight h = new Highlight();
            h.id = id;
            h.documentId = o.optString("documentId");
            h.page = o.optInt("page", 0);
            h.rects = HighlightRect.listFromJsonArray(o.optJSONArray("rects"));
            h.color = o.optString("color", "#FFEB3B");
            h.note = o.isNull("note") ? null : o.optString("note", null);
            h.createdAt = o.optString("createdAt", null);
            h.updatedAt = o.optString("updatedAt", null);
            h.deleted = false;
            h.dirty = false;
            db.upsertHighlight(h);
        }
    }

    private void applyProgress(JSONArray progress) {
        if (progress == null) return;
        for (int i = 0; i < progress.length(); i++) {
            JSONObject o = progress.optJSONObject(i);
            if (o == null) continue;
            String documentId = o.optString("documentId");
            Progress local = db.getProgress(documentId);
            if (local != null && local.dirty) {
                // We have an unpushed local change; don't let the pull clobber it.
                continue;
            }
            db.setLocalProgress(documentId, o.optInt("page", 0), o.optString("updatedAt", IsoDate.nowIso()), false);
        }
    }

    private void queueDownloads(final SyncListener listener) {
        List<Document> all = db.getAllDocuments();
        List<Document> toDownload = new ArrayList<Document>();
        for (int i = 0; i < all.size(); i++) {
            Document d = all.get(i);
            boolean needsDownload = !d.isDownloaded()
                    || d.localChecksum == null
                    || !d.localChecksum.equals(d.checksum);
            if (needsDownload) {
                toDownload.add(d);
            }
        }
        for (int i = 0; i < toDownload.size(); i++) {
            downloadOne(toDownload.get(i), listener);
        }
    }

    private void downloadOne(final Document d, final SyncListener listener) {
        db.updateDownloadState(d.id, Document.STATUS_DOWNLOADING, 0, null, null);
        File dir = new File(appContext.getFilesDir(), "documents");
        final File dest = new File(dir, d.id + ".pdf");
        try {
            api.downloadDocument(d.id, dest, 0, new ApiClient.DownloadProgressListener() {
                @Override
                public void onProgress(long bytesRead, long totalBytes) {
                    int pct = totalBytes > 0 ? (int) (bytesRead * 100 / totalBytes) : 0;
                    db.updateDownloadState(d.id, Document.STATUS_DOWNLOADING, pct, null, null);
                }
            });
            String actualChecksum = null;
            try {
                actualChecksum = Sha256.hexOf(dest);
            } catch (Exception ignored) {
                // If hashing fails we still keep the file; checksum compare on
                // next sync will simply re-download it.
            }
            db.updateDownloadState(d.id, Document.STATUS_DOWNLOADED, 100, dest.getAbsolutePath(), actualChecksum);
            notifyDownloaded(listener, d.id, true);
        } catch (ApiException e) {
            db.updateDownloadState(d.id, Document.STATUS_FAILED, 0, null, null);
            notifyDownloaded(listener, d.id, false);
        }
    }

    private void notifyDownloaded(final SyncListener listener, final String id, final boolean success) {
        if (listener == null) return;
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                listener.onDocumentDownloaded(id, success);
            }
        });
    }

    /** Called from ReaderActivity when the user creates/edits/deletes a highlight offline-first. */
    public void pushHighlightsAsync() {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                pushDirtyHighlights();
            }
        });
    }

    public void pushProgressAsync() {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                pushDirtyProgress();
            }
        });
    }
}
