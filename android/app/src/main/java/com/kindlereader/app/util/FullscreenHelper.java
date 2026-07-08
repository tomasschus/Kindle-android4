package com.kindlereader.app.util;

import android.os.Build;
import android.view.View;

/** Shared immersive-mode flag logic, used by both readers (PDF and EPUB). */
public final class FullscreenHelper {

    private FullscreenHelper() {
    }

    public static void apply(View decorView, View topBar, View bottomToolbar, boolean enable) {
        topBar.setVisibility(enable ? View.GONE : View.VISIBLE);
        bottomToolbar.setVisibility(enable ? View.GONE : View.VISIBLE);

        if (enable) {
            int flags;
            if (Build.VERSION.SDK_INT >= 19) {
                flags = View.SYSTEM_UI_FLAG_LOW_PROFILE
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
            } else if (Build.VERSION.SDK_INT >= 16) {
                // JELLY_BEAN: SYSTEM_UI_FLAG_FULLSCREEN exists but sticky/immersive does not.
                flags = View.SYSTEM_UI_FLAG_LOW_PROFILE
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION;
            } else {
                // API 15 (ICE_CREAM_SANDWICH_MR1): no SYSTEM_UI_FLAG_FULLSCREEN yet,
                // fall back to hiding the nav bar + low profile status bar only.
                flags = View.SYSTEM_UI_FLAG_LOW_PROFILE | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION;
            }
            decorView.setSystemUiVisibility(flags);
        } else {
            decorView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        }
    }
}
