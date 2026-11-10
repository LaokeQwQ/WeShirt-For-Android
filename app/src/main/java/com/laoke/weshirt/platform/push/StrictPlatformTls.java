package com.laoke.weshirt.platform.push;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;
import okhttp3.OkHttpClient;

/** System TLS used by the narrowly verified UMDID build transform. No vendor trust bypasses. */
public final class StrictPlatformTls {
    private StrictPlatformTls() {}

    private static final class SystemTls {
        static final X509ExtendedTrustManager TRUST = trustManager();
        static final SSLSocketFactory SOCKETS = socketFactory();
        static final HostnameVerifier HOSTS = new OkHttpClient().hostnameVerifier();

        private static X509ExtendedTrustManager trustManager() {
            try {
                TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                factory.init((KeyStore) null);
                for (TrustManager manager : factory.getTrustManagers()) {
                    if (manager instanceof X509ExtendedTrustManager) return (X509ExtendedTrustManager) manager;
                }
                throw new IllegalStateException("System extended trust manager unavailable");
            } catch (GeneralSecurityException error) {
                throw new IllegalStateException("System trust configuration unavailable", error);
            }
        }

        private static SSLSocketFactory socketFactory() {
            try {
                SSLContext context = SSLContext.getInstance("TLS");
                context.init(null, new TrustManager[]{TRUST}, null);
                return context.getSocketFactory();
            } catch (GeneralSecurityException error) {
                throw new IllegalStateException("System TLS unavailable", error);
            }
        }
    }

    private static void validate(X509Certificate[] chain, String authType) throws CertificateException {
        if (chain == null || chain.length == 0 || authType == null || authType.isEmpty())
            throw new CertificateException("A certificate chain and authentication type are required");
        for (X509Certificate certificate : chain) {
            if (certificate == null) throw new CertificateException("Invalid certificate chain");
            certificate.checkValidity();
        }
    }

    public static void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        validate(chain, authType);
        SystemTls.TRUST.checkServerTrusted(chain, authType);
    }
    public static void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        validate(chain, authType);
        SystemTls.TRUST.checkClientTrusted(chain, authType);
    }
    public static void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
        validate(chain, authType);
        if (!(socket instanceof SSLSocket)) throw new CertificateException("A TLS peer is required");
        configure((SSLSocket) socket);
        SystemTls.TRUST.checkServerTrusted(chain, authType, socket);
    }
    public static void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
        validate(chain, authType);
        SystemTls.TRUST.checkClientTrusted(chain, authType, socket);
    }
    public static void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
        validate(chain, authType);
        if (engine == null) throw new CertificateException("A TLS peer is required");
        SSLParameters parameters = engine.getSSLParameters();
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        engine.setSSLParameters(parameters);
        SystemTls.TRUST.checkServerTrusted(chain, authType, engine);
    }
    public static void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
        validate(chain, authType);
        SystemTls.TRUST.checkClientTrusted(chain, authType, engine);
    }
    public static X509Certificate[] getAcceptedIssuers() { return SystemTls.TRUST.getAcceptedIssuers().clone(); }

    public static boolean verifyHostname(String hostname, SSLSession session) {
        return hostname != null && !hostname.isEmpty() && session != null && SystemTls.HOSTS.verify(hostname, session);
    }
    private static SSLSocket configure(SSLSocket socket) {
        SSLParameters parameters = socket.getSSLParameters();
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        socket.setSSLParameters(parameters);
        return socket;
    }
    public static Socket createSocket() throws IOException { return configure((SSLSocket) SystemTls.SOCKETS.createSocket()); }
    public static Socket createSocket(String host, int port) throws IOException { return configure((SSLSocket) SystemTls.SOCKETS.createSocket(host, port)); }
    public static Socket createSocket(String host, int port, InetAddress local, int localPort) throws IOException { return configure((SSLSocket) SystemTls.SOCKETS.createSocket(host, port, local, localPort)); }
    public static Socket createSocket(InetAddress address, int port) throws IOException { return configure((SSLSocket) SystemTls.SOCKETS.createSocket(address, port)); }
    public static Socket createSocket(InetAddress address, int port, InetAddress local, int localPort) throws IOException { return configure((SSLSocket) SystemTls.SOCKETS.createSocket(address, port, local, localPort)); }
    public static Socket createSocket(Socket socket, String host, int port, boolean autoClose) throws IOException { return configure((SSLSocket) SystemTls.SOCKETS.createSocket(socket, host, port, autoClose)); }
    public static String[] getDefaultCipherSuites() { return SystemTls.SOCKETS.getDefaultCipherSuites().clone(); }
    public static String[] getSupportedCipherSuites() { return SystemTls.SOCKETS.getSupportedCipherSuites().clone(); }
}
