package com.kindlereader.app.util;

import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.view.View;

/**
 * Applies light / dark ("night") / grayscale reading modes to the rendered PDF
 * page without needing to hook into the internals of the PDF rendering
 * library.
 *
 * How it works: any View can be told to render itself into an offscreen
 * software layer via {@link View#setLayerType(int, Paint)}. When a Paint with
 * a {@link ColorMatrixColorFilter} is supplied, that filter is applied to the
 * *entire composited output* of the view (i.e. every pixel PDFView drew),
 * regardless of what the PDF rendering library does internally. This gives us
 * dark/grayscale modes without needing bitmap-level access inside the
 * third-party pdf-viewer library.
 *
 * We use LAYER_TYPE_SOFTWARE (not HARDWARE) deliberately, for two reasons:
 *  1) it works uniformly across the wide range of old/low-end GPUs found on
 *     these tablets, some of which have buggy hardware-layer + color-filter
 *     compositing paths, and
 *  2) it keeps Canvas transform queries (Canvas#getMatrix()) reliable for
 *     touch-to-page coordinate mapping in HighlightOverlayView --
 *     Canvas#getMatrix() is documented as unreliable on hardware-accelerated
 *     canvases but works correctly for software-rendered ones.
 */
public final class ColorModeHelper {

    public static final int MODE_LIGHT = 0;
    public static final int MODE_DARK = 1;
    public static final int MODE_GRAY = 2;

    private ColorModeHelper() {
    }

    public static void apply(View target, int mode) {
        Paint paint = new Paint();
        ColorMatrix matrix = matrixFor(mode);
        if (matrix != null) {
            paint.setColorFilter(new ColorMatrixColorFilter(matrix));
        }
        // Always use a software layer (even for MODE_LIGHT, where the filter
        // is null) so the touch-mapping code can rely on a consistent,
        // software-canvas rendering path. See class javadoc.
        target.setLayerType(View.LAYER_TYPE_SOFTWARE, paint);
        target.invalidate();
    }

    private static ColorMatrix matrixFor(int mode) {
        switch (mode) {
            case MODE_DARK: {
                // Inverts luminance while keeping hue roughly intact, which
                // reads better than a naive full RGB invert (which turns
                // colors into their photographic negative). This is the
                // classic "smart invert" matrix.
                ColorMatrix invert = new ColorMatrix(new float[]{
                        -1f, 0f, 0f, 0f, 255f,
                        0f, -1f, 0f, 0f, 255f,
                        0f, 0f, -1f, 0f, 255f,
                        0f, 0f, 0f, 1f, 0f
                });
                return invert;
            }
            case MODE_GRAY: {
                ColorMatrix gray = new ColorMatrix();
                gray.setSaturation(0f);
                return gray;
            }
            case MODE_LIGHT:
            default:
                return null;
        }
    }
}
