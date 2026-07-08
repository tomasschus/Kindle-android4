package com.kindlereader.app.net;

import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

import javax.net.ssl.X509TrustManager;

/**
 * Tries the system trust store first, then falls back to a second trust
 * manager (see {@link ApiClient}, which backs this with the bundled ISRG
 * Root X1 cert). Old Android trust stores predate Let's Encrypt's root and
 * the cross-signed intermediate that used to bridge the gap expired in 2021,
 * so devices below ~API 25 can't validate a Let's Encrypt chain without an
 * explicit assist.
 */
final class CompositeTrustManager implements X509TrustManager {

    private final X509TrustManager primary;
    private final X509TrustManager fallback;

    CompositeTrustManager(X509TrustManager primary, X509TrustManager fallback) {
        this.primary = primary;
        this.fallback = fallback;
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        primary.checkClientTrusted(chain, authType);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        try {
            primary.checkServerTrusted(chain, authType);
        } catch (CertificateException primaryFailure) {
            fallback.checkServerTrusted(chain, authType);
        }
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        X509Certificate[] a = primary.getAcceptedIssuers();
        X509Certificate[] b = fallback.getAcceptedIssuers();
        X509Certificate[] combined = new X509Certificate[a.length + b.length];
        System.arraycopy(a, 0, combined, 0, a.length);
        System.arraycopy(b, 0, combined, a.length, b.length);
        return combined;
    }
}
