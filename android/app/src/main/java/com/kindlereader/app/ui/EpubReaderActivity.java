package com.kindlereader.app.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.TextView;
import android.widget.ToggleButton;
import android.widget.Toast;

import com.kindlereader.app.R;
import com.kindlereader.app.data.DbHelper;
import com.kindlereader.app.data.Document;
import com.kindlereader.app.data.Highlight;
import com.kindlereader.app.data.Progress;
import com.kindlereader.app.epub.EpubBook;
import com.kindlereader.app.epub.EpubParser;
import com.kindlereader.app.net.SyncManager;
import com.kindlereader.app.util.ColorModeHelper;
import com.kindlereader.app.util.FullscreenHelper;
import com.kindlereader.app.util.HighlightColors;
import com.kindlereader.app.util.IsoDate;
import com.kindlereader.app.util.Prefs;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reflowable reader for EPUB documents: unlike {@link ReaderActivity} (fixed
 * PDF pages), this walks the book's spine chapter by chapter in a WebView,
 * letting the platform text engine reflow each chapter to the screen width.
 * There's no cross-chapter concept of "page" the way there is for a PDF, so
 * reading progress is tracked at chapter granularity -- coarser than PDF
 * page-level progress, but reuses the same Progress sync machinery.
 *
 * Highlighting here works differently from {@link ReaderActivity}'s
 * page-rects: since text reflows, a highlight is anchored to a text quote
 * plus surrounding context (see Highlight.anchorQuote/anchorPrefix/
 * anchorSuffix and docs/API.md) -- the same TextQuoteSelector approach the
 * W3C Web Annotation model uses. Selecting text in the WebView (long-press +
 * drag) triggers the color picker; existing highlights are re-found and
 * wrapped in a <mark> each time a chapter loads via injected JS (see
 * buildHighlightSetupScript/buildWrapCallsScript). Re-anchoring is
 * best-effort: if the chapter content changed enough that the quote+context
 * can no longer be found, that highlight just silently doesn't render (it's
 * not lost -- still synced -- in case the content match improves later).
 */
public class EpubReaderActivity extends AppCompatActivity {

    public static final String EXTRA_DOCUMENT_ID = "document_id";

    private static final long PROGRESS_SAVE_DEBOUNCE_MS = 800L;
    private static final int ANCHOR_CONTEXT_CHARS = 30;

    private WebView webView;
    private View topBar;
    private View bottomToolbar;
    private TextView pageIndicator;
    private Button btnSwitchFormat;
    private ToggleButton btnModeLight, btnModeDark, btnModeGray, btnFullscreen;

    private DbHelper db;
    private SyncManager syncManager;
    private Prefs prefs;

    private Document document;
    private List<File> chapters;
    private int currentChapter;
    private boolean fullscreenActive;
    private final Map<Integer, List<Highlight>> highlightsByChapter = new HashMap<Integer, List<Highlight>>();

    private final Handler handler = new Handler();
    private final Runnable saveProgressRunnable = new Runnable() {
        @Override
        public void run() {
            persistProgress(currentChapter);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_epub_reader);

        final String documentId = getIntent().getStringExtra(EXTRA_DOCUMENT_ID);
        if (documentId == null) {
            finish();
            return;
        }

        db = DbHelper.getInstance(this);
        syncManager = new SyncManager(this);
        prefs = new Prefs(this);

        webView = (WebView) findViewById(R.id.web_view);
        topBar = findViewById(R.id.top_bar);
        bottomToolbar = findViewById(R.id.bottom_toolbar);
        pageIndicator = (TextView) findViewById(R.id.text_page_indicator);
        btnModeLight = (ToggleButton) findViewById(R.id.btn_mode_light);
        btnModeDark = (ToggleButton) findViewById(R.id.btn_mode_dark);
        btnModeGray = (ToggleButton) findViewById(R.id.btn_mode_gray);
        btnFullscreen = (ToggleButton) findViewById(R.id.btn_fullscreen);
        Button btnBack = (Button) findViewById(R.id.btn_back);
        Button btnPrevChapter = (Button) findViewById(R.id.btn_prev_chapter);
        Button btnNextChapter = (Button) findViewById(R.id.btn_next_chapter);
        btnSwitchFormat = (Button) findViewById(R.id.btn_switch_format);
        Button btnFontSmaller = (Button) findViewById(R.id.btn_font_smaller);
        Button btnFontBigger = (Button) findViewById(R.id.btn_font_bigger);

        webView.getSettings().setDefaultTextEncodingName("UTF-8");
        // Needed for the font-size / reading-mode / highlighting CSS+JS
        // injection below -- javascript: URLs are silently a no-op without
        // this. Safe here since the WebView only ever loads local file://
        // chapters we extracted ourselves, never remote/untrusted content.
        webView.getSettings().setJavaScriptEnabled(true);
        webView.addJavascriptInterface(new HighlightBridge(), "AndroidHighlight");
        webView.setWebChromeClient(new android.webkit.WebChromeClient() {
            @Override
            public boolean onConsoleMessage(android.webkit.ConsoleMessage cm) {
                android.util.Log.d("EpubReaderJS", cm.message() + " [" + cm.sourceId() + ":" + cm.lineNumber() + "]");
                return true;
            }
        });
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                injectReadableStyle(view);
                view.loadUrl("javascript:" + buildHighlightSetupScript());
                view.loadUrl("javascript:" + buildWrapCallsScript(currentChapter));
            }
        });

        final GestureDetector tapDetector = new GestureDetector(this,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onSingleTapConfirmed(MotionEvent e) {
                        toggleChrome();
                        return true;
                    }
                });
        webView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                tapDetector.onTouchEvent(event);
                // Always let WebView also handle the event (scrolling, etc).
                return false;
            }
        });

        btnBack.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        btnPrevChapter.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                loadChapter(currentChapter - 1);
            }
        });
        btnNextChapter.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                loadChapter(currentChapter + 1);
            }
        });
        btnSwitchFormat.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                switchToPdf();
            }
        });
        btnFontSmaller.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                adjustFontSize(-Prefs.EPUB_FONT_SIZE_STEP);
            }
        });
        btnFontBigger.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                adjustFontSize(Prefs.EPUB_FONT_SIZE_STEP);
            }
        });

        wireModeButtons();

        btnFullscreen.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                setFullscreen(isChecked);
            }
        });

        new LoadTask(documentId).execute();
    }

    @Override
    protected void onStop() {
        super.onStop();
        handler.removeCallbacks(saveProgressRunnable);
        persistProgress(currentChapter);
        syncManager.pushProgressAsync();
    }

    // ------------------------------------------------------------------
    // Loading
    // ------------------------------------------------------------------

    private class LoadTask extends AsyncTask<Void, Void, LoadResult> {
        private final String documentId;

        LoadTask(String documentId) {
            this.documentId = documentId;
        }

        @Override
        protected LoadResult doInBackground(Void... params) {
            LoadResult result = new LoadResult();
            result.document = db.getDocument(documentId);
            result.progress = db.getProgress(documentId);
            result.highlights = db.getHighlightsForDocument(documentId);
            if (result.document != null && result.document.isDownloaded()) {
                try {
                    result.book = EpubParser.parse(epubExtractDir(documentId));
                } catch (IOException e) {
                    result.error = e.getMessage();
                }
            }
            return result;
        }

        @Override
        protected void onPostExecute(LoadResult result) {
            if (result.document == null || !result.document.isDownloaded()) {
                Toast.makeText(EpubReaderActivity.this,
                        getString(R.string.error_open_epub, "not downloaded"), Toast.LENGTH_LONG).show();
                finish();
                return;
            }
            if (result.book == null || result.book.chapters.isEmpty()) {
                Toast.makeText(EpubReaderActivity.this,
                        getString(R.string.error_open_epub, String.valueOf(result.error)), Toast.LENGTH_LONG).show();
                finish();
                return;
            }
            document = result.document;
            chapters = result.book.chapters;
            btnSwitchFormat.setVisibility(document.hasPdf ? View.VISIBLE : View.GONE);

            highlightsByChapter.clear();
            for (int i = 0; i < result.highlights.size(); i++) {
                addHighlightToMap(result.highlights.get(i));
            }

            ColorModeHelper.apply(webView, prefs.getReaderMode());
            syncModeButtons(prefs.getReaderMode());

            int startChapter = result.progress != null ? result.progress.page : document.lastReadPage;
            loadChapter(startChapter);
        }
    }

    private static class LoadResult {
        Document document;
        Progress progress;
        List<Highlight> highlights = new ArrayList<Highlight>();
        EpubBook book;
        String error;
    }

    private void addHighlightToMap(Highlight h) {
        List<Highlight> list = highlightsByChapter.get(h.page);
        if (list == null) {
            list = new ArrayList<Highlight>();
            highlightsByChapter.put(h.page, list);
        }
        list.add(h);
    }

    private File epubExtractDir(String documentId) {
        return new File(new File(getFilesDir(), "documents"), documentId + "_epub");
    }

    // ------------------------------------------------------------------
    // Chapter navigation
    // ------------------------------------------------------------------

    private void loadChapter(int index) {
        if (chapters == null || chapters.isEmpty()) {
            return;
        }
        currentChapter = Math.max(0, Math.min(index, chapters.size() - 1));
        webView.loadUrl(Uri.fromFile(chapters.get(currentChapter)).toString());
        updateChapterIndicator();
        handler.removeCallbacks(saveProgressRunnable);
        handler.postDelayed(saveProgressRunnable, PROGRESS_SAVE_DEBOUNCE_MS);
    }

    private void updateChapterIndicator() {
        pageIndicator.setText(getString(R.string.page_indicator_format,
                currentChapter + 1, Math.max(chapters.size(), 1)));
    }

    /**
     * Base typography so chapters read like a proper reflowable e-book, not a
     * bare web page. Uses a fixed element id so {@link #adjustFontSize} can
     * update the current chapter's font size in place, without reloading it.
     */
    private void injectReadableStyle(WebView view) {
        int fontSize = prefs.getEpubFontSize();
        view.loadUrl("javascript:(function(){"
                + "var s=document.getElementById('kr-style');"
                + "if(!s){s=document.createElement('style');s.id='kr-style';document.head.appendChild(s);}"
                + "s.innerHTML='body{font-family:serif;font-size:" + fontSize + "px;line-height:1.5;"
                + "margin:20px;max-width:100%;} img{max-width:100%;height:auto;}';"
                + "})()");
    }

    private void adjustFontSize(int deltaSp) {
        prefs.setEpubFontSize(prefs.getEpubFontSize() + deltaSp);
        injectReadableStyle(webView);
    }

    // ------------------------------------------------------------------
    // Highlighting: text-quote anchored (see class javadoc), driven by a
    // small JS bridge. Text selection (long-press + drag) prompts for a
    // color; tapping an existing highlight (a <mark>) prompts to delete it.
    // ------------------------------------------------------------------

    private final class HighlightBridge {
        @JavascriptInterface
        public void onTextSelected(final String quote, final String prefix, final String suffix) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    promptHighlightColor(quote, prefix, suffix);
                }
            });
        }

        @JavascriptInterface
        public void onHighlightTapped(final String highlightId) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    confirmDeleteHighlight(highlightId);
                }
            });
        }
    }

    private void promptHighlightColor(final String quote, final String prefix, final String suffix) {
        if (quote == null || quote.trim().length() == 0) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.action_pick_color)
                .setItems(HighlightColors.NAMES, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        createHighlight(quote, prefix, suffix, HighlightColors.HEX[which]);
                    }
                })
                .setNegativeButton(R.string.action_cancel, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        webView.loadUrl("javascript:window.__krClearSelection && window.__krClearSelection()");
                    }
                })
                .show();
    }

    private void createHighlight(String quote, String prefix, String suffix, String colorHex) {
        Highlight h = new Highlight();
        h.id = Highlight.LOCAL_ID_PREFIX + UUID.randomUUID().toString();
        h.documentId = document.id;
        h.page = currentChapter;
        h.anchorQuote = quote;
        h.anchorPrefix = prefix;
        h.anchorSuffix = suffix;
        h.color = colorHex;
        h.note = null;
        h.createdAt = IsoDate.nowIso();
        h.updatedAt = h.createdAt;
        h.deleted = false;
        h.dirty = true;

        db.upsertHighlight(h);
        addHighlightToMap(h);
        syncManager.pushHighlightsAsync();

        webView.loadUrl("javascript:window.__krClearSelection && window.__krClearSelection();"
                + "window.__krWrapHighlight && window.__krWrapHighlight("
                + jsString(h.id) + "," + jsString(prefix) + "," + jsString(quote) + ","
                + jsString(suffix) + "," + jsString(colorHex) + ")");
        Toast.makeText(this, R.string.highlight_saved, Toast.LENGTH_SHORT).show();
    }

    private void confirmDeleteHighlight(final String highlightId) {
        final Highlight h = findHighlight(highlightId);
        if (h == null) {
            return;
        }
        new AlertDialog.Builder(this)
                .setMessage(R.string.action_delete_highlight)
                .setPositiveButton(R.string.action_delete_highlight, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        deleteHighlight(h);
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private Highlight findHighlight(String id) {
        List<Highlight> list = highlightsByChapter.get(currentChapter);
        if (list == null) {
            return null;
        }
        for (int i = 0; i < list.size(); i++) {
            if (id.equals(list.get(i).id)) {
                return list.get(i);
            }
        }
        return null;
    }

    private void deleteHighlight(Highlight h) {
        List<Highlight> list = highlightsByChapter.get(h.page);
        if (list != null) {
            list.remove(h);
        }
        if (h.isLocalOnly()) {
            db.hardDeleteHighlight(h.id);
        } else {
            h.deleted = true;
            h.dirty = true;
            h.updatedAt = IsoDate.nowIso();
            db.upsertHighlight(h);
            syncManager.pushHighlightsAsync();
        }
        // Simplest correct way to remove its <mark> from the DOM: just
        // reload the chapter, which re-renders from the (now smaller) list.
        webView.loadUrl(Uri.fromFile(chapters.get(currentChapter)).toString());
        Toast.makeText(this, R.string.highlight_deleted, Toast.LENGTH_SHORT).show();
    }

    /**
     * One-time-per-page-load setup: TreeWalker-based helpers to convert
     * between DOM Range boundaries and plain-text offsets (so quote/prefix/
     * suffix anchoring is exact, not an approximation over rendered/visual
     * text), a selectionchange listener that reports new selections to
     * {@link HighlightBridge#onTextSelected}, and a wrap function used both
     * for rendering existing highlights and the one just created.
     */
    private String buildHighlightSetupScript() {
        return "(function(){"
                + "function fullText(){"
                + "  var w=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT,null,false);"
                + "  var t='',n;while((n=w.nextNode())){t+=n.nodeValue;}return t;"
                + "}"
                + "function offsetOf(node,offset){"
                + "  var w=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT,null,false);"
                + "  var total=0,n;"
                + "  while((n=w.nextNode())){if(n===node){return total+offset;}total+=n.nodeValue.length;}"
                + "  return -1;"
                + "}"
                + "function rangeFromOffsets(start,end){"
                + "  var w=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT,null,false);"
                + "  var total=0,n,sn=null,so=0,en=null,eo=0;"
                + "  while((n=w.nextNode())){"
                + "    var len=n.nodeValue.length;"
                + "    if(sn===null&&total+len>=start){sn=n;so=start-total;}"
                + "    if(en===null&&total+len>=end){en=n;eo=end-total;}"
                + "    total+=len;if(sn&&en){break;}"
                + "  }"
                + "  if(!sn||!en){return null;}"
                + "  var r=document.createRange();r.setStart(sn,so);r.setEnd(en,eo);return r;"
                + "}"
                + "window.__krFullText=fullText();"
                + "document.addEventListener('selectionchange',function(){"
                + "  clearTimeout(window.__krSelTimer);"
                + "  window.__krSelTimer=setTimeout(function(){"
                + "    var sel=window.getSelection();"
                + "    if(!sel||sel.isCollapsed||sel.rangeCount===0){return;}"
                + "    var range=sel.getRangeAt(0);"
                + "    var s=offsetOf(range.startContainer,range.startOffset);"
                + "    var e=offsetOf(range.endContainer,range.endOffset);"
                + "    if(s<0||e<0||e<=s){return;}"
                + "    var full=window.__krFullText;"
                + "    var quote=full.substring(s,e);"
                + "    var prefix=full.substring(Math.max(0,s-" + ANCHOR_CONTEXT_CHARS + "),s);"
                + "    var suffix=full.substring(e,e+" + ANCHOR_CONTEXT_CHARS + ");"
                + "    if(window.AndroidHighlight){window.AndroidHighlight.onTextSelected(quote,prefix,suffix);}"
                + "  },400);"
                + "});"
                + "window.__krWrapHighlight=function(id,prefix,quote,suffix,color){"
                + "  var full=window.__krFullText;"
                + "  var idx=full.indexOf(prefix+quote+suffix);"
                + "  var start,end;"
                + "  if(idx>=0){start=idx+prefix.length;end=start+quote.length;}"
                + "  else{idx=full.indexOf(quote);if(idx<0){console.log('KR wrap: quote not found');return false;}start=idx;end=idx+quote.length;}"
                + "  var range=rangeFromOffsets(start,end);"
                + "  if(!range){console.log('KR wrap: no range for '+start+'-'+end);return false;}"
                + "  try{"
                + "    var mark=document.createElement('mark');"
                + "    mark.setAttribute('data-kr-highlight-id',id);"
                + "    mark.style.backgroundColor=color;mark.style.color='inherit';"
                + "    range.surroundContents(mark);"
                + "    mark.onclick=function(){if(window.AndroidHighlight){window.AndroidHighlight.onHighlightTapped(id);}};"
                + "    console.log('KR wrap: success '+start+'-'+end);"
                + "    return true;"
                + "  }catch(e){console.log('KR wrap error: '+e);return false;}"
                + "};"
                + "window.__krClearSelection=function(){var s=window.getSelection();if(s){s.removeAllRanges();}};"
                + "})()";
    }

    /** Re-wraps every stored highlight for {@code chapter} after a fresh page load. */
    private String buildWrapCallsScript(int chapter) {
        List<Highlight> list = highlightsByChapter.get(chapter);
        if (list == null || list.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            Highlight h = list.get(i);
            if (!h.isTextAnchored()) {
                continue;
            }
            sb.append("window.__krWrapHighlight && window.__krWrapHighlight(")
                    .append(jsString(h.id)).append(',')
                    .append(jsString(h.anchorPrefix)).append(',')
                    .append(jsString(h.anchorQuote)).append(',')
                    .append(jsString(h.anchorSuffix)).append(',')
                    .append(jsString(h.color)).append(");");
        }
        return sb.toString();
    }

    /** Safely embeds a Java string as a single-quoted JS string literal. */
    private static String jsString(String s) {
        if (s == null) {
            return "''";
        }
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('\'');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '\'': sb.append("\\'"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                default: sb.append(c);
            }
        }
        sb.append('\'');
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Reading mode (light / dark / grayscale) -- shared with ReaderActivity
    // ------------------------------------------------------------------

    private void wireModeButtons() {
        btnModeLight.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked) selectMode(ColorModeHelper.MODE_LIGHT);
            }
        });
        btnModeDark.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked) selectMode(ColorModeHelper.MODE_DARK);
            }
        });
        btnModeGray.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked) selectMode(ColorModeHelper.MODE_GRAY);
            }
        });
    }

    private void selectMode(int mode) {
        prefs.setReaderMode(mode);
        ColorModeHelper.apply(webView, mode);
        syncModeButtons(mode);
    }

    private void syncModeButtons(int mode) {
        btnModeLight.setChecked(mode == ColorModeHelper.MODE_LIGHT);
        btnModeDark.setChecked(mode == ColorModeHelper.MODE_DARK);
        btnModeGray.setChecked(mode == ColorModeHelper.MODE_GRAY);
    }

    // ------------------------------------------------------------------
    // Fullscreen
    // ------------------------------------------------------------------

    private void toggleChrome() {
        setFullscreen(!fullscreenActive);
        btnFullscreen.setChecked(fullscreenActive);
    }

    private void setFullscreen(boolean enable) {
        fullscreenActive = enable;
        FullscreenHelper.apply(getWindow().getDecorView(), topBar, bottomToolbar, enable);
    }

    // ------------------------------------------------------------------
    // Progress persistence (chapter index stands in for "page")
    // ------------------------------------------------------------------

    private void persistProgress(int chapter) {
        if (document == null) {
            return;
        }
        db.setLocalProgress(document.id, chapter, IsoDate.nowIso(), true);
        db.updateLastReadPage(document.id, chapter);
    }

    // ------------------------------------------------------------------
    // Format switching (PDF <-> EPUB, when the server has both)
    // ------------------------------------------------------------------

    private void switchToPdf() {
        if (document == null) {
            return;
        }
        btnSwitchFormat.setEnabled(false);
        new AsyncTask<Void, Void, String>() {
            @Override
            protected String doInBackground(Void... params) {
                try {
                    syncManager.ensurePdfDownloaded(document);
                    return null;
                } catch (Exception e) {
                    return String.valueOf(e.getMessage());
                }
            }

            @Override
            protected void onPostExecute(String error) {
                btnSwitchFormat.setEnabled(true);
                if (error != null) {
                    Toast.makeText(EpubReaderActivity.this,
                            getString(R.string.error_switch_format, error), Toast.LENGTH_LONG).show();
                    return;
                }
                Intent intent = new Intent(EpubReaderActivity.this, ReaderActivity.class);
                intent.putExtra(ReaderActivity.EXTRA_DOCUMENT_ID, document.id);
                startActivity(intent);
                finish();
            }
        }.execute();
    }
}
