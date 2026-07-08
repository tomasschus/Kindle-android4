package com.kindlereader.app.ui;

import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.TextView;
import android.widget.ToggleButton;
import android.widget.Toast;

import com.github.barteksc.pdfviewer.PDFView;
import com.github.barteksc.pdfviewer.listener.OnDrawListener;
import com.github.barteksc.pdfviewer.listener.OnErrorListener;
import com.github.barteksc.pdfviewer.listener.OnLoadCompleteListener;
import com.github.barteksc.pdfviewer.listener.OnPageChangeListener;
import com.github.barteksc.pdfviewer.listener.OnPageErrorListener;
import com.github.barteksc.pdfviewer.listener.OnTapListener;
import com.github.barteksc.pdfviewer.util.FitPolicy;
import com.kindlereader.app.R;
import com.kindlereader.app.data.DbHelper;
import com.kindlereader.app.data.Document;
import com.kindlereader.app.data.Highlight;
import com.kindlereader.app.data.HighlightRect;
import com.kindlereader.app.net.ApiClient;
import com.kindlereader.app.net.ApiException;
import com.kindlereader.app.net.SyncManager;
import com.kindlereader.app.util.ColorModeHelper;
import com.kindlereader.app.util.FullscreenHelper;
import com.kindlereader.app.util.IsoDate;
import com.kindlereader.app.util.Prefs;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * PDF reader: paging, reading-mode color filters, fullscreen, and rectangle
 * highlighting. See docs/API.md for the highlight/progress contract and
 * android/README.md for the design of the highlight coordinate mapping.
 */
public class ReaderActivity extends AppCompatActivity {

    public static final String EXTRA_DOCUMENT_ID = "document_id";

    private static final String[] HIGHLIGHT_COLOR_NAMES = {"Yellow", "Green", "Blue", "Pink"};
    private static final String[] HIGHLIGHT_COLOR_HEX = {"#FFEB3B", "#8BC34A", "#64B5F6", "#F48FB1"};
    private static final long PROGRESS_SAVE_DEBOUNCE_MS = 800L;

    private PDFView pdfView;
    private HighlightOverlayView overlay;
    private View topBar;
    private View bottomToolbar;
    private TextView pageIndicator;
    private TextView highlightHint;
    private Button btnSwitchFormat;
    private ToggleButton btnModeLight, btnModeDark, btnModeGray, btnHighlight, btnFullscreen;

    private DbHelper db;
    private SyncManager syncManager;
    private Prefs prefs;

    private Document document;
    private final Map<Integer, List<Highlight>> highlightsByPage = new HashMap<Integer, List<Highlight>>();

    private int currentPage;
    private int pageCount;
    private boolean fullscreenActive;

    private final Handler handler = new Handler();
    private final Runnable saveProgressRunnable = new Runnable() {
        @Override
        public void run() {
            persistProgress(currentPage);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Keep the screen on while reading -- this is a dedicated e-reader device.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_reader);

        String documentId = getIntent().getStringExtra(EXTRA_DOCUMENT_ID);
        if (documentId == null) {
            finish();
            return;
        }

        db = DbHelper.getInstance(this);
        syncManager = new SyncManager(this);
        prefs = new Prefs(this);

        pdfView = (PDFView) findViewById(R.id.pdf_view);
        overlay = (HighlightOverlayView) findViewById(R.id.highlight_overlay);
        topBar = findViewById(R.id.top_bar);
        bottomToolbar = findViewById(R.id.bottom_toolbar);
        pageIndicator = (TextView) findViewById(R.id.text_page_indicator);
        highlightHint = (TextView) findViewById(R.id.text_highlight_hint);
        btnModeLight = (ToggleButton) findViewById(R.id.btn_mode_light);
        btnModeDark = (ToggleButton) findViewById(R.id.btn_mode_dark);
        btnModeGray = (ToggleButton) findViewById(R.id.btn_mode_gray);
        btnHighlight = (ToggleButton) findViewById(R.id.btn_highlight);
        btnFullscreen = (ToggleButton) findViewById(R.id.btn_fullscreen);
        Button btnBack = (Button) findViewById(R.id.btn_back);
        btnSwitchFormat = (Button) findViewById(R.id.btn_switch_format);

        btnBack.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        btnSwitchFormat.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                switchToEpub();
            }
        });

        wireModeButtons();

        btnHighlight.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                overlay.setHighlightModeEnabled(isChecked);
                highlightHint.setVisibility(isChecked ? View.VISIBLE : View.GONE);
            }
        });

        btnFullscreen.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                setFullscreen(isChecked);
            }
        });

        overlay.setOnHighlightCreatedListener(new HighlightOverlayView.OnHighlightCreatedListener() {
            @Override
            public void onHighlightCreated(int page, double nx, double ny, double nw, double nh) {
                promptHighlightColor(page, nx, ny, nw, nh);
            }
        });

        new LoadTask(documentId).execute();
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Flush any pending debounced progress save so we don't lose the last
        // page read if the user backs out quickly.
        handler.removeCallbacks(saveProgressRunnable);
        persistProgress(currentPage);
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
            result.highlights = db.getHighlightsForDocument(documentId);
            result.progress = db.getProgress(documentId);
            return result;
        }

        @Override
        protected void onPostExecute(LoadResult result) {
            if (result.document == null || !result.document.isDownloaded()) {
                Toast.makeText(ReaderActivity.this,
                        getString(R.string.error_open_pdf, "not downloaded"), Toast.LENGTH_LONG).show();
                finish();
                return;
            }
            document = result.document;
            btnSwitchFormat.setVisibility(document.isEpub() ? View.VISIBLE : View.GONE);
            highlightsByPage.clear();
            for (int i = 0; i < result.highlights.size(); i++) {
                Highlight h = result.highlights.get(i);
                addHighlightToMap(h);
            }
            int startPage = result.progress != null ? result.progress.page : document.lastReadPage;
            openPdf(startPage);
            refreshProgressFromServer(documentId);
        }
    }

    private static class LoadResult {
        Document document;
        List<Highlight> highlights;
        com.kindlereader.app.data.Progress progress;
    }

    private void addHighlightToMap(Highlight h) {
        List<Highlight> list = highlightsByPage.get(h.page);
        if (list == null) {
            list = new ArrayList<Highlight>();
            highlightsByPage.put(h.page, list);
        }
        list.add(h);
    }

    /** GET /api/documents/:id/progress on open, per docs/API.md; reconciles with local if newer. */
    private void refreshProgressFromServer(final String documentId) {
        new AsyncTask<Void, Void, Integer>() {
            @Override
            protected Integer doInBackground(Void... params) {
                try {
                    ApiClient client = new ApiClient(ReaderActivity.this);
                    JSONObject response = client.getProgress(documentId);
                    if (response == null || response.length() == 0) {
                        return null;
                    }
                    com.kindlereader.app.data.Progress local = db.getProgress(documentId);
                    if (local != null && local.dirty) {
                        // We have an unpushed local change; don't clobber it.
                        return null;
                    }
                    int page = response.optInt("page", -1);
                    if (page < 0) {
                        return null;
                    }
                    db.setLocalProgress(documentId, page, response.optString("updatedAt", IsoDate.nowIso()), false);
                    return page;
                } catch (ApiException e) {
                    return null; // offline or server error: keep using local progress
                }
            }

            @Override
            protected void onPostExecute(Integer serverPage) {
                if (serverPage != null && pdfView != null && document != null) {
                    // Only jump if materially different, to avoid disrupting active reading.
                    if (Math.abs(serverPage - currentPage) > 0) {
                        pdfView.jumpTo(serverPage, false);
                    }
                }
            }
        }.execute();
    }

    private void openPdf(int startPage) {
        ColorModeHelper.apply(pdfView, prefs.getReaderMode());
        syncModeButtons(prefs.getReaderMode());

        File file = new File(document.localPath);
        pdfView.fromFile(file)
                .defaultPage(Math.max(0, startPage))
                .swipeHorizontal(true)
                .enableSwipe(true)
                .pageSnap(true)
                .autoSpacing(true)
                .pageFling(true)
                .pageFitPolicy(FitPolicy.BOTH)
                .enableAnnotationRendering(true)
                .enableAntialiasing(true)
                .spacing(0)
                .onPageChange(new OnPageChangeListener() {
                    @Override
                    public void onPageChanged(int page, int count) {
                        currentPage = page;
                        pageCount = count;
                        updatePageIndicator();
                        handler.removeCallbacks(saveProgressRunnable);
                        handler.postDelayed(saveProgressRunnable, PROGRESS_SAVE_DEBOUNCE_MS);
                    }
                })
                .onDrawAll(new OnDrawListener() {
                    @Override
                    public void onLayerDrawn(Canvas canvas, float pageWidth, float pageHeight, int displayedPage) {
                        drawHighlightsForPage(canvas, pageWidth, pageHeight, displayedPage);
                        if (displayedPage == pdfView.getCurrentPage()) {
                            overlay.updateFrame(canvas.getMatrix(), pageWidth, pageHeight, displayedPage);
                        }
                    }
                })
                .onLoad(new OnLoadCompleteListener() {
                    @Override
                    public void loadComplete(int nbPages) {
                        pageCount = nbPages;
                        currentPage = pdfView.getCurrentPage();
                        updatePageIndicator();
                    }
                })
                .onTap(new OnTapListener() {
                    @Override
                    public boolean onTap(MotionEvent e) {
                        return handlePageTap(e);
                    }
                })
                .onError(new OnErrorListener() {
                    @Override
                    public void onError(Throwable t) {
                        Toast.makeText(ReaderActivity.this,
                                getString(R.string.error_open_pdf, String.valueOf(t.getMessage())),
                                Toast.LENGTH_LONG).show();
                    }
                })
                .onPageError(new OnPageErrorListener() {
                    @Override
                    public void onPageError(int page, Throwable t) {
                        // Non-fatal: one page failed to render, keep going.
                    }
                })
                .load();
    }

    private void updatePageIndicator() {
        pageIndicator.setText(getString(R.string.page_indicator_format, currentPage + 1, Math.max(pageCount, 1)));
    }

    // ------------------------------------------------------------------
    // Highlight rendering (existing highlights) -- drawn directly on
    // PDFView's own canvas, in the page's local coordinate system, which the
    // library hands us "for free" via OnDrawListener. See HighlightOverlayView
    // for how new highlights are captured via touch.
    // ------------------------------------------------------------------

    private void drawHighlightsForPage(Canvas canvas, float pageWidth, float pageHeight, int page) {
        List<Highlight> list = highlightsByPage.get(page);
        if (list == null || list.isEmpty()) {
            return;
        }
        Paint fill = new Paint();
        Paint stroke = new Paint();
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(2f);
        for (int i = 0; i < list.size(); i++) {
            Highlight h = list.get(i);
            int color = safeParseColor(h.color);
            fill.setColor(color);
            fill.setAlpha(90);
            stroke.setColor(color);
            stroke.setAlpha(200);
            for (int j = 0; j < h.rects.size(); j++) {
                HighlightRect r = h.rects.get(j);
                RectF rectF = new RectF(
                        (float) (r.x * pageWidth),
                        (float) (r.y * pageHeight),
                        (float) ((r.x + r.w) * pageWidth),
                        (float) ((r.y + r.h) * pageHeight));
                canvas.drawRect(rectF, fill);
                canvas.drawRect(rectF, stroke);
            }
        }
    }

    private int safeParseColor(String hex) {
        try {
            return Color.parseColor(hex);
        } catch (Exception e) {
            return Color.YELLOW;
        }
    }

    // ------------------------------------------------------------------
    // Highlight creation / deletion
    // ------------------------------------------------------------------

    private void promptHighlightColor(final int page, final double nx, final double ny, final double nw, final double nh) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.action_pick_color)
                .setItems(HIGHLIGHT_COLOR_NAMES, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        createHighlight(page, nx, ny, nw, nh, HIGHLIGHT_COLOR_HEX[which]);
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void createHighlight(int page, double nx, double ny, double nw, double nh, String colorHex) {
        Highlight h = new Highlight();
        h.id = Highlight.LOCAL_ID_PREFIX + UUID.randomUUID().toString();
        h.documentId = document.id;
        h.page = page;
        h.rects = new ArrayList<HighlightRect>();
        h.rects.add(new HighlightRect(nx, ny, nw, nh));
        h.color = colorHex;
        h.note = null;
        h.createdAt = IsoDate.nowIso();
        h.updatedAt = h.createdAt;
        h.deleted = false;
        h.dirty = true;

        db.upsertHighlight(h);
        addHighlightToMap(h);
        pdfView.invalidate();
        syncManager.pushHighlightsAsync();
        Toast.makeText(this, R.string.highlight_saved, Toast.LENGTH_SHORT).show();
    }

    /** Returns true if a tap at (x,y) hit an existing highlight on the given page, and prompts deletion. */
    private boolean maybeDeleteHighlightAt(int page, double nx, double ny) {
        List<Highlight> list = highlightsByPage.get(page);
        if (list == null) {
            return false;
        }
        for (int i = 0; i < list.size(); i++) {
            final Highlight h = list.get(i);
            for (int j = 0; j < h.rects.size(); j++) {
                HighlightRect r = h.rects.get(j);
                if (nx >= r.x && nx <= r.x + r.w && ny >= r.y && ny <= r.y + r.h) {
                    confirmDeleteHighlight(h);
                    return true;
                }
            }
        }
        return false;
    }

    private void confirmDeleteHighlight(final Highlight h) {
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

    private void deleteHighlight(Highlight h) {
        List<Highlight> list = highlightsByPage.get(h.page);
        if (list != null) {
            list.remove(h);
        }
        if (h.isLocalOnly()) {
            // Never made it to the server; just drop it locally.
            db.hardDeleteHighlight(h.id);
        } else {
            h.deleted = true;
            h.dirty = true;
            h.updatedAt = IsoDate.nowIso();
            db.upsertHighlight(h);
            syncManager.pushHighlightsAsync();
        }
        pdfView.invalidate();
        Toast.makeText(this, R.string.highlight_deleted, Toast.LENGTH_SHORT).show();
    }

    private boolean handlePageTap(MotionEvent e) {
        if (!overlay.isHighlightModeEnabled()) {
            double[] hit = overlay.screenToPageNormalized(e.getX(), e.getY());
            if (hit != null) {
                int page = (int) hit[0];
                double nx = hit[1];
                double ny = hit[2];
                if (maybeDeleteHighlightAt(page, nx, ny)) {
                    return true;
                }
            }
        }
        toggleChrome();
        return true;
    }

    // ------------------------------------------------------------------
    // Reading mode (light / dark / grayscale)
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
        ColorModeHelper.apply(pdfView, mode);
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
    // Progress persistence
    // ------------------------------------------------------------------

    private void persistProgress(int page) {
        if (document == null) {
            return;
        }
        db.setLocalProgress(document.id, page, IsoDate.nowIso(), true);
        db.updateLastReadPage(document.id, page);
    }

    // ------------------------------------------------------------------
    // Format switching (PDF <-> EPUB, when the server has both)
    // ------------------------------------------------------------------

    private void switchToEpub() {
        if (document == null) {
            return;
        }
        btnSwitchFormat.setEnabled(false);
        new AsyncTask<Void, Void, String>() {
            @Override
            protected String doInBackground(Void... params) {
                try {
                    syncManager.ensureEpubDownloaded(document);
                    return null;
                } catch (Exception e) {
                    return String.valueOf(e.getMessage());
                }
            }

            @Override
            protected void onPostExecute(String error) {
                btnSwitchFormat.setEnabled(true);
                if (error != null) {
                    Toast.makeText(ReaderActivity.this,
                            getString(R.string.error_switch_format, error), Toast.LENGTH_LONG).show();
                    return;
                }
                Intent intent = new Intent(ReaderActivity.this, EpubReaderActivity.class);
                intent.putExtra(EpubReaderActivity.EXTRA_DOCUMENT_ID, document.id);
                startActivity(intent);
                finish();
            }
        }.execute();
    }
}
