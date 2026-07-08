package com.kindlereader.app.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * Transparent view stacked on top of PDFView. Responsible for:
 *
 *  1. Capturing a live drag gesture (while "highlight mode" is armed via the
 *     toolbar) and drawing a preview rectangle for it.
 *  2. Translating that screen-space drag into a page-normalized rectangle
 *     (0..1 of page width/height, per the Highlight.rects shape in
 *     docs/API.md) once the gesture finishes.
 *
 * It does NOT render already-saved highlights -- those are drawn directly by
 * ReaderActivity via PDFView's OnDrawListener, which gives an exact
 * page-local coordinate system "for free" (see ReaderActivity for details).
 * This view only needs the inverse of that transform, to turn a touch point
 * into page-local coordinates; it obtains it from
 * {@link #updateFrame(Matrix, float, float, int)}, called by ReaderActivity
 * every time PDFView redraws the current page.
 *
 * NOTE ON COORDINATE MAPPING: This relies on Canvas#getMatrix(), which is
 * only reliable for a software-rendered canvas (it is documented as
 * unsupported on hardware-accelerated canvases). ReaderActivity forces
 * PDFView onto a software layer (View#setLayerType) for the reading-mode
 * color filters anyway (see ColorModeHelper), which conveniently makes this
 * mapping reliable too. This is the piece of the app most worth re-verifying
 * on a real device / real library build, since it could not be exercised
 * against the actual AndroidPdfViewer binary in the sandbox this was
 * developed in -- see android/README.md "Known limitations".
 */
public class HighlightOverlayView extends View {

    /** Minimum drag distance (in px) before we treat a gesture as a highlight rather than a tap. */
    private static final float MIN_DRAG_PX = 12f;

    public interface OnHighlightCreatedListener {
        void onHighlightCreated(int page, double nx, double ny, double nw, double nh);
    }

    private Matrix pageMatrix;
    private Matrix inverseMatrix = new Matrix();
    private boolean haveValidFrame;
    private float framePageWidth;
    private float framePageHeight;
    private int framePage = -1;

    private boolean highlightModeEnabled;
    private boolean dragging;
    private float downX, downY, curX, curY;

    private final Paint previewFill = new Paint();
    private final Paint previewStroke = new Paint();

    private OnHighlightCreatedListener listener;

    public HighlightOverlayView(Context context) {
        super(context);
        init();
    }

    public HighlightOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        previewFill.setColor(Color.argb(90, 255, 213, 79));
        previewFill.setStyle(Paint.Style.FILL);
        previewStroke.setColor(Color.argb(200, 255, 179, 0));
        previewStroke.setStyle(Paint.Style.STROKE);
        previewStroke.setStrokeWidth(2f);
        setWillNotDraw(false);
    }

    public void setOnHighlightCreatedListener(OnHighlightCreatedListener listener) {
        this.listener = listener;
    }

    public void setHighlightModeEnabled(boolean enabled) {
        this.highlightModeEnabled = enabled;
        if (!enabled) {
            dragging = false;
            invalidate();
        }
    }

    public boolean isHighlightModeEnabled() {
        return highlightModeEnabled;
    }

    /** Called by ReaderActivity from PDFView's draw listener for the currently displayed page. */
    public void updateFrame(Matrix matrixFromCanvas, float pageWidth, float pageHeight, int page) {
        if (pageMatrix == null) {
            pageMatrix = new Matrix();
        }
        pageMatrix.set(matrixFromCanvas);
        haveValidFrame = pageMatrix.invert(inverseMatrix);
        framePageWidth = pageWidth;
        framePageHeight = pageHeight;
        framePage = page;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!highlightModeEnabled || !haveValidFrame) {
            return false;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                curX = downX;
                curY = downY;
                dragging = true;
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (dragging) {
                    curX = event.getX();
                    curY = event.getY();
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (dragging) {
                    curX = event.getX();
                    curY = event.getY();
                    finishDrag();
                }
                dragging = false;
                invalidate();
                return true;
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                invalidate();
                return true;
            default:
                return true;
        }
    }

    private void finishDrag() {
        float left = Math.min(downX, curX);
        float top = Math.min(downY, curY);
        float right = Math.max(downX, curX);
        float bottom = Math.max(downY, curY);

        if (right - left < MIN_DRAG_PX || bottom - top < MIN_DRAG_PX) {
            // Too small; treat as an accidental tap, not a highlight.
            return;
        }
        if (framePageWidth <= 0 || framePageHeight <= 0) {
            return;
        }

        float[] topLeft = new float[]{left, top};
        float[] bottomRight = new float[]{right, bottom};
        inverseMatrix.mapPoints(topLeft);
        inverseMatrix.mapPoints(bottomRight);

        double nx1 = clamp01(topLeft[0] / framePageWidth);
        double ny1 = clamp01(topLeft[1] / framePageHeight);
        double nx2 = clamp01(bottomRight[0] / framePageWidth);
        double ny2 = clamp01(bottomRight[1] / framePageHeight);

        double nx = Math.min(nx1, nx2);
        double ny = Math.min(ny1, ny2);
        double nw = Math.abs(nx2 - nx1);
        double nh = Math.abs(ny2 - ny1);

        if (nw <= 0 || nh <= 0) {
            return;
        }

        if (listener != null) {
            listener.onHighlightCreated(framePage, nx, ny, nw, nh);
        }
    }

    /**
     * Maps a screen-space point (e.g. from PDFView's onTap callback) into
     * page-normalized coordinates for the page currently tracked by
     * {@link #updateFrame}, for hit-testing existing highlights (e.g.
     * tap-to-delete). Returns null if no frame has been captured yet.
     */
    public double[] screenToPageNormalized(float x, float y) {
        if (!haveValidFrame || framePageWidth <= 0 || framePageHeight <= 0) {
            return null;
        }
        float[] pt = new float[]{x, y};
        inverseMatrix.mapPoints(pt);
        return new double[]{framePage, pt[0] / framePageWidth, pt[1] / framePageHeight};
    }

    private static double clamp01(double v) {
        if (v < 0) return 0;
        if (v > 1) return 1;
        return v;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (dragging) {
            RectF r = new RectF(Math.min(downX, curX), Math.min(downY, curY),
                    Math.max(downX, curX), Math.max(downY, curY));
            canvas.drawRect(r, previewFill);
            canvas.drawRect(r, previewStroke);
        }
    }
}
