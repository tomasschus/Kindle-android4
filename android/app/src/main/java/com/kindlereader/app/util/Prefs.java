package com.kindlereader.app.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

/**
 * Thin wrapper around SharedPreferences holding auth + server configuration
 * state. Kept as a plain class (no DI framework) to minimize moving parts on
 * an old, low-resource device.
 */
public final class Prefs {

    private static final String FILE = "kindle_reader_prefs";

    private static final String KEY_SERVER_URL = "server_url";
    private static final String KEY_TOKEN = "auth_token";
    private static final String KEY_USER_ID = "user_id";
    private static final String KEY_USERNAME = "username";
    private static final String KEY_LAST_SYNC_AT = "last_sync_at";
    private static final String KEY_READER_MODE = "reader_mode";

    private final SharedPreferences prefs;

    public Prefs(Context context) {
        this.prefs = context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public String getServerUrl() {
        return prefs.getString(KEY_SERVER_URL, "");
    }

    public void setServerUrl(String url) {
        if (url != null) {
            // Normalize: strip trailing slash so we can safely concatenate paths.
            while (url.endsWith("/")) {
                url = url.substring(0, url.length() - 1);
            }
        }
        prefs.edit().putString(KEY_SERVER_URL, url).apply();
    }

    public String getToken() {
        return prefs.getString(KEY_TOKEN, null);
    }

    public void setToken(String token) {
        prefs.edit().putString(KEY_TOKEN, token).apply();
    }

    public String getUserId() {
        return prefs.getString(KEY_USER_ID, null);
    }

    public String getUsername() {
        return prefs.getString(KEY_USERNAME, null);
    }

    public void setUser(String userId, String username) {
        prefs.edit().putString(KEY_USER_ID, userId).putString(KEY_USERNAME, username).apply();
    }

    public boolean isLoggedIn() {
        return !TextUtils.isEmpty(getToken()) && !TextUtils.isEmpty(getServerUrl());
    }

    public void clearSession() {
        prefs.edit().remove(KEY_TOKEN).remove(KEY_USER_ID).remove(KEY_USERNAME).apply();
    }

    public String getLastSyncAt() {
        return prefs.getString(KEY_LAST_SYNC_AT, null);
    }

    public void setLastSyncAt(String iso) {
        prefs.edit().putString(KEY_LAST_SYNC_AT, iso).apply();
    }

    public int getReaderMode() {
        return prefs.getInt(KEY_READER_MODE, 0);
    }

    public void setReaderMode(int mode) {
        prefs.edit().putInt(KEY_READER_MODE, mode).apply();
    }
}
