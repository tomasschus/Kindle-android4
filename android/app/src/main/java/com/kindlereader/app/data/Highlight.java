package com.kindlereader.app.data;

import java.util.ArrayList;
import java.util.List;

/**
 * Mirrors the `Highlight` shape from docs/API.md, plus local-only sync
 * bookkeeping fields (dirty / pendingDelete / localId).
 */
public class Highlight {

    /** Prefix used for highlights created offline that don't have a server id yet. */
    public static final String LOCAL_ID_PREFIX = "local-";

    public String id;
    public String documentId;
    public int page;
    public List<HighlightRect> rects = new ArrayList<HighlightRect>();
    public String color = "#FFEB3B";
    public String note;
    public String createdAt;
    public String updatedAt;
    public boolean deleted;

    // Local-only sync bookkeeping:
    /** True if this row has local changes not yet pushed to the server. */
    public boolean dirty;
    /** True if the row was created offline and never got a server id. */
    public boolean isLocalOnly() {
        return id != null && id.startsWith(LOCAL_ID_PREFIX);
    }
}
