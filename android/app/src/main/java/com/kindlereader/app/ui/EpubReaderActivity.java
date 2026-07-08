package com.kindlereader.app.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.support.v7.app.AppCompatActivity;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
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
import com.kindlereader.app.data.Progress;
import com.kindlereader.app.epub.EpubBook;
import com.kindlereader.app.epub.EpubParser;
import com.kindlereader.app.net.SyncManager;
import com.kindlereader.app.util.ColorModeHelper;
import com.kindlereader.app.util.FullscreenHelper;
import com.kindlereader.app.util.IsoDate;
import com.kindlereader.app.util.Prefs;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Reflowable reader for EPUB documents: unlike {@link ReaderActivity} (fixed
 * PDF pages), this walks the book's spine chapter by chapter in a WebView,
 * letting the platform text engine reflow each chapter to the screen width.
 * There's no cross-chapter concept of "page" the way there is for a PDF, so
 * reading progress is tracked at chapter granularity -- coarser than PDF
 * page-level progress, but reuses the same Progress sync machinery.
 *
 * No highlighting support here in v1: the highlight model (docs/API.md) is
 * normalized page-rects, which assumes a fixed rendered page image. Anchoring
 * highlights to reflowable text would need a different (text-range-based)
 * model entirely.
 */
public class EpubReaderActivity extends AppCompatActivity {

    public static final String EXTRA_DOCUMENT_ID = "document_id";

    private static final long PROGRESS_SAVE_DEBOUNCE_MS = 800L;

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
        // Needed for the font-size / reading-mode CSS injection below --
        // javascript: URLs are silently a no-op without this.
        webView.getSettings().setJavaScriptEnabled(true);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                injectReadableStyle(view);
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

            ColorModeHelper.apply(webView, prefs.getReaderMode());
            syncModeButtons(prefs.getReaderMode());

            int startChapter = result.progress != null ? result.progress.page : document.lastReadPage;
            loadChapter(startChapter);
        }
    }

    private static class LoadResult {
        Document document;
        Progress progress;
        EpubBook book;
        String error;
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
