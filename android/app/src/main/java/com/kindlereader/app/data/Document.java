package com.kindlereader.app.data;

/**
 * Mirrors the `Document` shape from docs/API.md, plus local-only fields
 * tracking download state.
 */
public class Document {

    public static final String STATUS_NONE = "NONE";
    public static final String STATUS_QUEUED = "QUEUED";
    public static final String STATUS_DOWNLOADING = "DOWNLOADING";
    public static final String STATUS_DOWNLOADED = "DOWNLOADED";
    public static final String STATUS_FAILED = "FAILED";

    public String id;
    public String title;
    public String filename;
    public long sizeBytes;
    public Integer pageCount; // null-able, matches API contract
    public String checksum;
    public String epubStatus; // "ready" | "failed" | null -- server-side PDF->EPUB conversion state
    public boolean hasPdf = true; // false only for documents uploaded directly as EPUB
    public String createdAt;
    public String updatedAt;

    // Local-only:
    public String localPath;
    public String localChecksum;
    public String downloadStatus = STATUS_NONE;
    public int downloadProgress; // 0-100
    public int lastReadPage;

    public boolean isDownloaded() {
        return STATUS_DOWNLOADED.equals(downloadStatus) && localPath != null;
    }

    /**
     * True when the server has a ready-made EPUB conversion of this (still
     * PDF-named) document -- in which case we download and read that instead
     * of the original PDF, via the reflowable WebView-based reader.
     */
    public boolean isEpub() {
        return "ready".equals(epubStatus);
    }
}
