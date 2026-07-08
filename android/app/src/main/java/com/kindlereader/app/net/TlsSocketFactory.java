package com.kindlereader.app.net;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Wraps the platform SSLSocketFactory to force TLSv1.1/TLSv1.2 on.
 *
 * On Android API 16-19 the SSL provider supports TLSv1.1/1.2 but leaves them
 * disabled by default (only SSLv3/TLSv1.0 are enabled out of the box), so a
 * handshake against a server that requires TLS 1.2+ (the norm for anything
 * fronted by a modern reverse proxy) fails with "tlsv1 alert protocol
 * version" before certificates are even exchanged. Explicitly enabling the
 * protocols on every socket this factory creates fixes that.
 */
final class TlsSocketFactory extends SSLSocketFactory {

    private static final String[] PREFERRED_PROTOCOLS = {"TLSv1.2", "TLSv1.1", "TLSv1"};

    private final SSLSocketFactory delegate;

    TlsSocketFactory(SSLSocketFactory delegate) {
        this.delegate = delegate;
    }

    @Override
    public String[] getDefaultCipherSuites() {
        return delegate.getDefaultCipherSuites();
    }

    @Override
    public String[] getSupportedCipherSuites() {
        return delegate.getSupportedCipherSuites();
    }

    @Override
    public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
        return enableTls(delegate.createSocket(s, host, port, autoClose));
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        return enableTls(delegate.createSocket(host, port));
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
        return enableTls(delegate.createSocket(host, port, localHost, localPort));
    }

    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
        return enableTls(delegate.createSocket(host, port));
    }

    @Override
    public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
        return enableTls(delegate.createSocket(address, port, localAddress, localPort));
    }

    private static Socket enableTls(Socket socket) {
        if (socket instanceof SSLSocket) {
            SSLSocket sslSocket = (SSLSocket) socket;
            java.util.List<String> supported = java.util.Arrays.asList(sslSocket.getSupportedProtocols());
            java.util.List<String> enable = new java.util.ArrayList<>();
            for (String protocol : PREFERRED_PROTOCOLS) {
                if (supported.contains(protocol)) {
                    enable.add(protocol);
                }
            }
            if (!enable.isEmpty()) {
                sslSocket.setEnabledProtocols(enable.toArray(new String[0]));
            }
        }
        return socket;
    }
}
