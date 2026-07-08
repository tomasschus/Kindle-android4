package com.kindlereader.app.net;

import android.content.Context;
import android.text.TextUtils;

import com.kindlereader.app.R;
import com.kindlereader.app.util.Prefs;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import okhttp3.ConnectionSpec;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.TlsVersion;

/**
 * Thin synchronous REST client for the API described in docs/API.md.
 * All methods here are blocking and MUST be called off the main thread.
 *
 * Uses plain OkHttp + org.json (built into Android) rather than
 * Retrofit+Gson/Moshi, to avoid pulling in Java-8-only transitive
 * dependencies that get fragile targeting API 15 (see android/README.md).
 */
public class ApiClient {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final Prefs prefs;
    private final OkHttpClient http;

    public ApiClient(Context context) {
        this.prefs = new Prefs(context);
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS);
        configureTls(context, builder);
        this.http = builder.build();
    }

    /**
     * Two fixes needed for TLS to work against a modern server on Android
     * API 16-19:
     *
     * 1. TLSv1.1/1.2 are supported by the provider but disabled by default,
     *    so a plain OkHttpClient fails to negotiate with servers that
     *    require TLS 1.2+ (see TlsSocketFactory).
     * 2. These devices' trust stores predate Let's Encrypt's ISRG Root X1,
     *    and the cross-signed intermediate that used to bridge the gap
     *    expired in 2021, so the system trust manager alone can't validate
     *    a Let's Encrypt chain. Fall back to a bundled copy of that root
     *    (res/raw/isrgrootx1.pem) via CompositeTrustManager.
     */
    private static void configureTls(Context context, OkHttpClient.Builder builder) {
        try {
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init((KeyStore) null);
            X509TrustManager systemTrustManager = firstX509(tmf.getTrustManagers());
            if (systemTrustManager == null) {
                return;
            }

            X509TrustManager trustManager = systemTrustManager;
            X509TrustManager pinnedRootTrustManager = loadPinnedRootTrustManager(context);
            if (pinnedRootTrustManager != null) {
                trustManager = new CompositeTrustManager(systemTrustManager, pinnedRootTrustManager);
            }

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[]{trustManager}, null);

            builder.sslSocketFactory(new TlsSocketFactory(sslContext.getSocketFactory()), trustManager);
            builder.connectionSpecs(Arrays.asList(
                    new ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                            .tlsVersions(TlsVersion.TLS_1_2, TlsVersion.TLS_1_1, TlsVersion.TLS_1_0)
                            .build(),
                    ConnectionSpec.CLEARTEXT));
        } catch (Exception e) {
            // Fall back to OkHttp's default TLS handling if anything above
            // is unavailable on this device/provider.
        }
    }

    private static X509TrustManager loadPinnedRootTrustManager(Context context) {
        InputStream in = null;
        try {
            in = context.getResources().openRawResource(R.raw.isrgrootx1);
            Certificate cert = CertificateFactory.getInstance("X.509").generateCertificate(in);

            KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
            keyStore.load(null, null);
            keyStore.setCertificateEntry("isrgrootx1", cert);

            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(keyStore);
            return firstX509(tmf.getTrustManagers());
        } catch (Exception e) {
            return null;
        } finally {
            closeQuietly(in);
        }
    }

    private static X509TrustManager firstX509(TrustManager[] trustManagers) {
        for (TrustManager tm : trustManagers) {
            if (tm instanceof X509TrustManager) {
                return (X509TrustManager) tm;
            }
        }
        return null;
    }

    private String baseUrl() {
        String url = prefs.getServerUrl();
        if (url == null) {
            url = "";
        }
        return url;
    }

    private Request.Builder authedRequest(String path) {
        Request.Builder b = new Request.Builder().url(baseUrl() + path);
        String token = prefs.getToken();
        if (!TextUtils.isEmpty(token)) {
            b.header("Authorization", "Bearer " + token);
        }
        return b;
    }

    // ------------------------------------------------------------------
    // Auth
    // ------------------------------------------------------------------

    /** Returns the raw `{ token, user }` response. */
    public JSONObject login(String username, String password) throws ApiException {
        try {
            JSONObject body = new JSONObject();
            body.put("username", username);
            body.put("password", password);
            Request req = new Request.Builder()
                    .url(baseUrl() + "/api/auth/login")
                    .post(RequestBody.create(JSON, body.toString()))
                    .build();
            return executeJson(req);
        } catch (JSONException e) {
            throw new ApiException("Failed to build login request: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Sync
    // ------------------------------------------------------------------

    public JSONObject sync(String sinceIso) throws ApiException {
        String path = "/api/sync";
        if (!TextUtils.isEmpty(sinceIso)) {
            path += "?since=" + urlEncode(sinceIso);
        }
        Request req = authedRequest(path).get().build();
        return executeJson(req);
    }

    // ------------------------------------------------------------------
    // Documents
    // ------------------------------------------------------------------

    public JSONObject listDocuments() throws ApiException {
        Request req = authedRequest("/api/documents").get().build();
        return executeJson(req);
    }

    /**
     * Downloads a document's original PDF to {@code dest}, supporting resume
     * via HTTP Range when {@code resumeFromBytes > 0}. Reports progress via
     * {@code listener}.
     */
    public void downloadDocument(String documentId, File dest, long resumeFromBytes, DownloadProgressListener listener)
            throws ApiException {
        downloadToFile("/api/documents/" + documentId + "/download", dest, resumeFromBytes, listener);
    }

    /**
     * Downloads the server-side PDF->EPUB conversion of a document (see
     * Document.epubStatus). The endpoint doesn't support Range requests, but
     * this still works correctly when resuming: the shared download logic
     * detects the non-206 response and restarts from scratch.
     */
    public void downloadEpub(String documentId, File dest, long resumeFromBytes, DownloadProgressListener listener)
            throws ApiException {
        downloadToFile("/api/documents/" + documentId + "/epub", dest, resumeFromBytes, listener);
    }

    private void downloadToFile(String path, File dest, long resumeFromBytes, DownloadProgressListener listener)
            throws ApiException {
        Request.Builder rb = authedRequest(path).get();
        boolean resuming = resumeFromBytes > 0 && dest.exists();
        if (resuming) {
            rb.header("Range", "bytes=" + resumeFromBytes + "-");
        }
        Response response = null;
        OutputStream out = null;
        InputStream in = null;
        try {
            response = http.newCall(rb.build()).execute();
            if (!response.isSuccessful()) {
                throw errorFromResponse(response);
            }
            boolean serverHonoredRange = response.code() == 206;
            long already = serverHonoredRange ? resumeFromBytes : 0;
            if (!serverHonoredRange && dest.exists()) {
                // Server sent the whole file back; start fresh.
                dest.delete();
            }
            File parent = dest.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            out = new FileOutputStream(dest, serverHonoredRange);
            ResponseBody body = response.body();
            if (body == null) {
                throw new ApiException("Empty response body");
            }
            long total = body.contentLength();
            if (total > 0 && already > 0) {
                total += already;
            }
            in = body.byteStream();
            byte[] buffer = new byte[8192];
            long readTotal = already;
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                readTotal += read;
                if (listener != null && total > 0) {
                    listener.onProgress(readTotal, total);
                }
            }
        } catch (IOException e) {
            throw new ApiException("Download failed: " + e.getMessage());
        } finally {
            closeQuietly(in);
            closeQuietly(out);
            if (response != null) {
                response.close();
            }
        }
    }

    public interface DownloadProgressListener {
        void onProgress(long bytesRead, long totalBytes);
    }

    // ------------------------------------------------------------------
    // Highlights
    // ------------------------------------------------------------------

    public JSONObject createHighlight(String documentId, int page, String rectsJsonArray, String color, String note)
            throws ApiException {
        try {
            JSONObject body = new JSONObject();
            body.put("page", page);
            body.put("rects", new org.json.JSONArray(rectsJsonArray));
            body.put("color", color);
            if (note != null) {
                body.put("note", note);
            }
            Request req = authedRequest("/api/documents/" + documentId + "/highlights")
                    .post(RequestBody.create(JSON, body.toString()))
                    .build();
            return executeJson(req);
        } catch (JSONException e) {
            throw new ApiException("Failed to build highlight request: " + e.getMessage());
        }
    }

    public JSONObject updateHighlight(String highlightId, String rectsJsonArray, String color, String note)
            throws ApiException {
        try {
            JSONObject body = new JSONObject();
            if (rectsJsonArray != null) {
                body.put("rects", new org.json.JSONArray(rectsJsonArray));
            }
            if (color != null) {
                body.put("color", color);
            }
            if (note != null) {
                body.put("note", note);
            }
            Request req = authedRequest("/api/highlights/" + highlightId)
                    .put(RequestBody.create(JSON, body.toString()))
                    .build();
            return executeJson(req);
        } catch (JSONException e) {
            throw new ApiException("Failed to build highlight update: " + e.getMessage());
        }
    }

    public void deleteHighlight(String highlightId) throws ApiException {
        Request req = authedRequest("/api/highlights/" + highlightId).delete().build();
        executeNoContent(req);
    }

    // ------------------------------------------------------------------
    // Progress
    // ------------------------------------------------------------------

    public JSONObject getProgress(String documentId) throws ApiException {
        Request req = authedRequest("/api/documents/" + documentId + "/progress").get().build();
        return executeJson(req);
    }

    public JSONObject putProgress(String documentId, int page) throws ApiException {
        try {
            JSONObject body = new JSONObject();
            body.put("page", page);
            Request req = authedRequest("/api/documents/" + documentId + "/progress")
                    .put(RequestBody.create(JSON, body.toString()))
                    .build();
            return executeJson(req);
        } catch (JSONException e) {
            throw new ApiException("Failed to build progress update: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private JSONObject executeJson(Request req) throws ApiException {
        Response response = null;
        try {
            response = http.newCall(req).execute();
            ResponseBody body = response.body();
            String text = body == null ? "" : body.string();
            if (!response.isSuccessful()) {
                throw errorFromBody(response.code(), text);
            }
            if (TextUtils.isEmpty(text)) {
                return new JSONObject();
            }
            return new JSONObject(text);
        } catch (IOException e) {
            throw new ApiException("Network error: " + e.getMessage());
        } catch (JSONException e) {
            throw new ApiException("Malformed response: " + e.getMessage());
        } finally {
            if (response != null) {
                response.close();
            }
        }
    }

    private void executeNoContent(Request req) throws ApiException {
        Response response = null;
        try {
            response = http.newCall(req).execute();
            if (!response.isSuccessful()) {
                ResponseBody body = response.body();
                String text = body == null ? "" : body.string();
                throw errorFromBody(response.code(), text);
            }
        } catch (IOException e) {
            throw new ApiException("Network error: " + e.getMessage());
        } finally {
            if (response != null) {
                response.close();
            }
        }
    }

    private ApiException errorFromResponse(Response response) {
        try {
            ResponseBody body = response.body();
            String text = body == null ? "" : body.string();
            return errorFromBody(response.code(), text);
        } catch (IOException e) {
            return new ApiException(response.code(), null, "HTTP " + response.code());
        }
    }

    private ApiException errorFromBody(int status, String text) {
        String errorCode = null;
        String message = "HTTP " + status;
        if (!TextUtils.isEmpty(text)) {
            try {
                JSONObject o = new JSONObject(text);
                errorCode = o.optString("error", null);
                String msg = o.optString("message", null);
                message = msg != null ? msg : (errorCode != null ? errorCode : message);
            } catch (JSONException ignored) {
                // Non-JSON error body; fall back to generic message.
            }
        }
        return new ApiException(status, errorCode, message);
    }

    private static String urlEncode(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return s;
        }
    }

    private static void closeQuietly(InputStream in) {
        if (in != null) {
            try {
                in.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static void closeQuietly(OutputStream out) {
        if (out != null) {
            try {
                out.close();
            } catch (IOException ignored) {
            }
        }
    }
}
