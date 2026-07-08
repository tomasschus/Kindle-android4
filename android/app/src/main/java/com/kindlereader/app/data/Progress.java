package com.kindlereader.app.data;

/** Mirrors the `Progress` shape from docs/API.md. */
public class Progress {
    public String documentId;
    public int page;
    public String updatedAt;
    /** True if changed locally (e.g. offline) and not yet pushed. */
    public boolean dirty;
}
