package com.kindlereader.app.epub;

import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Minimal EPUB (2/3) reader: unzips the container and walks
 * META-INF/container.xml -> the OPF package document -> its spine, to
 * produce the ordered chapter list a WebView-based reader needs.
 *
 * Deliberately uses android.util.Xml's pull parser (built into every Android
 * version since API 1) rather than a third-party EPUB library, to avoid
 * pulling in AndroidX transitively (see android/README.md) on a project
 * targeting API 15.
 */
public final class EpubParser {

    private EpubParser() {
    }

    /** Unzips {@code epubFile} into {@code destDir}, replacing any previous contents. */
    public static void extract(File epubFile, File destDir) throws IOException {
        deleteRecursive(destDir);
        if (!destDir.mkdirs() && !destDir.isDirectory()) {
            throw new IOException("Could not create " + destDir);
        }
        String destRoot = destDir.getCanonicalPath() + File.separator;

        ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(epubFile)));
        try {
            byte[] buffer = new byte[8192];
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                File outFile = new File(destDir, entry.getName());
                // Zip-slip guard: refuse entries that would escape destDir.
                if (!outFile.getCanonicalPath().startsWith(destRoot)) {
                    continue;
                }
                File parent = outFile.getParentFile();
                if (parent != null && !parent.exists()) {
                    parent.mkdirs();
                }
                FileOutputStream fos = new FileOutputStream(outFile);
                try {
                    int read;
                    while ((read = zis.read(buffer)) != -1) {
                        fos.write(buffer, 0, read);
                    }
                } finally {
                    fos.close();
                }
            }
        } finally {
            zis.close();
        }
    }

    /** Parses the already-extracted EPUB at {@code extractedDir} into spine order. */
    public static EpubBook parse(File extractedDir) throws IOException {
        File opfFile = findOpfFile(extractedDir);
        File opfDir = opfFile.getParentFile();

        Map<String, String> manifest = new HashMap<String, String>(); // id -> href
        List<String> spineIds = new ArrayList<String>();

        InputStream in = new FileInputStream(opfFile);
        try {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(in, "UTF-8");
            boolean inManifest = false;
            boolean inSpine = false;
            int eventType = parser.getEventType();
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {
                    String name = parser.getName();
                    if ("manifest".equals(name)) {
                        inManifest = true;
                    } else if ("spine".equals(name)) {
                        inSpine = true;
                    } else if (inManifest && "item".equals(name)) {
                        String id = parser.getAttributeValue(null, "id");
                        String href = parser.getAttributeValue(null, "href");
                        if (id != null && href != null) {
                            manifest.put(id, href);
                        }
                    } else if (inSpine && "itemref".equals(name)) {
                        String idref = parser.getAttributeValue(null, "idref");
                        if (idref != null) {
                            spineIds.add(idref);
                        }
                    }
                } else if (eventType == XmlPullParser.END_TAG) {
                    String name = parser.getName();
                    if ("manifest".equals(name)) {
                        inManifest = false;
                    } else if ("spine".equals(name)) {
                        inSpine = false;
                    }
                }
                eventType = parser.next();
            }
        } catch (Exception e) {
            throw new IOException("Malformed OPF: " + e.getMessage(), e);
        } finally {
            in.close();
        }

        List<File> chapters = new ArrayList<File>();
        for (int i = 0; i < spineIds.size(); i++) {
            String href = manifest.get(spineIds.get(i));
            if (href == null) {
                continue;
            }
            chapters.add(resolveHref(opfDir, href));
        }
        if (chapters.isEmpty()) {
            // Malformed/missing spine: fall back to any HTML-ish manifest
            // entries so there's still something to read.
            for (String href : manifest.values()) {
                String lower = href.toLowerCase(java.util.Locale.US);
                if (lower.endsWith(".xhtml") || lower.endsWith(".html") || lower.endsWith(".htm")) {
                    chapters.add(resolveHref(opfDir, href));
                }
            }
        }
        if (chapters.isEmpty()) {
            throw new IOException("EPUB has no readable chapters");
        }
        return new EpubBook(chapters);
    }

    private static File resolveHref(File baseDir, String href) throws IOException {
        try {
            return new File(baseDir, URLDecoder.decode(href, "UTF-8"));
        } catch (Exception e) {
            return new File(baseDir, href);
        }
    }

    private static File findOpfFile(File extractedDir) throws IOException {
        File container = new File(extractedDir, "META-INF/container.xml");
        if (container.exists()) {
            String opfRelativePath = readRootfilePath(container);
            if (opfRelativePath != null) {
                File f = new File(extractedDir, opfRelativePath);
                if (f.exists()) {
                    return f;
                }
            }
        }
        File found = findFileByExtension(extractedDir, ".opf");
        if (found != null) {
            return found;
        }
        throw new IOException("No OPF package file found in EPUB");
    }

    private static String readRootfilePath(File containerXml) throws IOException {
        InputStream in = new FileInputStream(containerXml);
        try {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(in, "UTF-8");
            int eventType = parser.getEventType();
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG && "rootfile".equals(parser.getName())) {
                    return parser.getAttributeValue(null, "full-path");
                }
                eventType = parser.next();
            }
        } catch (Exception e) {
            throw new IOException("Malformed container.xml: " + e.getMessage(), e);
        } finally {
            in.close();
        }
        return null;
    }

    private static File findFileByExtension(File dir, String extension) {
        File[] children = dir.listFiles();
        if (children == null) {
            return null;
        }
        for (int i = 0; i < children.length; i++) {
            File child = children[i];
            if (child.isDirectory()) {
                File found = findFileByExtension(child, extension);
                if (found != null) {
                    return found;
                }
            } else if (child.getName().toLowerCase(java.util.Locale.US).endsWith(extension)) {
                return child;
            }
        }
        return null;
    }

    public static void deleteRecursive(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (int i = 0; i < children.length; i++) {
                deleteRecursive(children[i]);
            }
        }
        file.delete();
    }
}
