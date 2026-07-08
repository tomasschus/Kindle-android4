package com.kindlereader.app.epub;

import java.io.File;
import java.util.List;

/** Ordered list of chapter files (spine order) extracted from an EPUB. */
public class EpubBook {

    public final List<File> chapters;

    public EpubBook(List<File> chapters) {
        this.chapters = chapters;
    }
}
