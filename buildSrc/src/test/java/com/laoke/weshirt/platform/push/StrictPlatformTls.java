package com.laoke.weshirt.platform.push;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSession;
import java.net.InetAddress;
import java.net.Socket;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

/** Test-only static sink: proves transformed upstream bytecode forwards the real arguments. */
public final class StrictPlatformTls {
    public static String lastMethod;
    public static Object[] lastArguments;
    public static Object result;
    public static CertificateException certificateFailure;

    private StrictPlatformTls() { }

    private static void record(String method, Object... arguments) {
        lastMethod = method;
        lastArguments = arguments;
    }

    private static void trusted(String method, Object... arguments) throws CertificateException {
        record(method, arguments);
        if (certificateFailure != null) throw certificateFailure;
    }

    public static void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        trusted("checkClientTrusted", chain, authType);
    }

    public static void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
        trusted("checkClientTrusted", chain, authType, socket);
    }

    public static void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
        trusted("checkClientTrusted", chain, authType, engine);
    }

    public static void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        trusted("checkServerTrusted", chain, authType);
    }

    public static void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
        trusted("checkServerTrusted", chain, authType, socket);
    }

    public static void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
        trusted("checkServerTrusted", chain, authType, engine);
    }

    public static X509Certificate[] getAcceptedIssuers() {
        record("getAcceptedIssuers");
        return (X509Certificate[]) result;
    }

    public static boolean verifyHostname(String hostname, SSLSession session) {
        record("verifyHostname", hostname, session);
        return (Boolean) result;
    }

    public static Socket createSocket() {
        record("createSocket");
        return (Socket) result;
    }

    public static Socket createSocket(String host, int port) {
        record("createSocket", host, port);
        return (Socket) result;
    }

    public static Socket createSocket(String host, int port, InetAddress localHost, int localPort) {
        record("createSocket", host, port, localHost, localPort);
        return (Socket) result;
    }

    public static Socket createSocket(InetAddress host, int port) {
        record("createSocket", host, port);
        return (Socket) result;
    }

    public static Socket createSocket(InetAddress host, int port, InetAddress localHost, int localPort) {
        record("createSocket", host, port, localHost, localPort);
        return (Socket) result;
    }

    public static Socket createSocket(Socket socket, String host, int port, boolean autoClose) {
        record("createSocket", socket, host, port, autoClose);
        return (Socket) result;
    }

    public static String[] getDefaultCipherSuites() {
        record("getDefaultCipherSuites");
        return (String[]) result;
    }

    public static String[] getSupportedCipherSuites() {
        record("getSupportedCipherSuites");
        return (String[]) result;
    }
}
