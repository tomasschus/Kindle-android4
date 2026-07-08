package com.kindlereader.app.util;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * ISO-8601 helpers. Deliberately implemented with SimpleDateFormat rather than
 * java.time.* (only available on API 26+) since this app must run on API 15.
 */
public final class IsoDate {

    private IsoDate() {
    }

    public static String nowIso() {
        return format(new Date());
    }

    public static String format(Date date) {
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
        return fmt.format(date);
    }

    /**
     * Parses an ISO-8601 timestamp as returned by the server. Tries a couple of
     * common variants (with/without milliseconds, with/without timezone offset)
     * since different backends format slightly differently.
     */
    public static Date parse(String iso) {
        if (iso == null || iso.length() == 0) {
            return null;
        }
        String[] patterns = new String[]{
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                "yyyy-MM-dd'T'HH:mm:ss'Z'",
                "yyyy-MM-dd'T'HH:mm:ssZ",
                "yyyy-MM-dd'T'HH:mm:ss.SSSZ"
        };
        for (int i = 0; i < patterns.length; i++) {
            try {
                SimpleDateFormat fmt = new SimpleDateFormat(patterns[i], Locale.US);
                fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
                return fmt.parse(iso);
            } catch (ParseException e) {
                // try next pattern
            }
        }
        return null;
    }
}
