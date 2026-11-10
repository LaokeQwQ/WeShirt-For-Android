package com.laoke.weshirt.platform

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.Proxy
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.cert.CertificateException
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager

/** Exercises vendor classes loaded from the instrumented APK, rather than testing only our helper. */
@RunWith(AndroidJUnit4::class)
class VendorTlsInstrumentationTest {
    private fun vendor(name: String, host: String? = null): Any {
        val type = Class.forName("com.umeng.ut.a.b.$name")
        val constructor = if (host == null) type.getDeclaredConstructor() else type.getDeclaredConstructor(String::class.java)
        constructor.isAccessible = true
        return if (host == null) constructor.newInstance() else constructor.newInstance(host)
    }

    private fun expiredCertificate(): X509Certificate = InstrumentationRegistry.getInstrumentation().context.assets
        .open("expired-localhost.der").use { CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate }

    @Test fun transformedVendorTrustManagersRejectExpiredChainsInAllEightOverloads() {
        val chain = arrayOf(expiredCertificate())
        val extended = vendor("c") as X509ExtendedTrustManager
        val basic = vendor("f") as X509TrustManager
        val engine = SSLContext.getDefault().createSSLEngine("localhost", 443)
        SSLContext.getDefault().socketFactory.createSocket().use { socket ->
            val checks = listOf<() -> Unit>(
                { extended.checkClientTrusted(chain, "RSA") },
                { extended.checkServerTrusted(chain, "RSA") },
                { extended.checkClientTrusted(chain, "RSA", socket) },
                { extended.checkServerTrusted(chain, "RSA", socket) },
                { extended.checkClientTrusted(chain, "RSA", engine) },
                { extended.checkServerTrusted(chain, "RSA", engine) },
                { basic.checkClientTrusted(chain, "RSA") },
                { basic.checkServerTrusted(chain, "RSA") },
            )
            checks.forEachIndexed { index, check ->
                try { check(); fail("Vendor trust overload $index accepted an expired certificate") }
                catch (error: CertificateException) { assertTrue("The explicit validity check must execute", error is CertificateExpiredException) }
            }
        }
        assertTrue(extended.acceptedIssuers.isNotEmpty())
        assertTrue(basic.acceptedIssuers.isNotEmpty())
    }

    @Test fun transformedHostnameVerifierUsesEachActualHostname() {
        val certificate = expiredCertificate()
        val session = Proxy.newProxyInstance(SSLSession::class.java.classLoader, arrayOf(SSLSession::class.java)) { _, method, _ ->
            when (method.name) {
                "getPeerCertificates" -> arrayOf(certificate)
                else -> throw UnsupportedOperationException(method.name)
            }
        } as SSLSession
        val verifier = vendor("d", "cached.invalid") as HostnameVerifier
        // Date validity belongs to the trust manager; this test isolates actual SAN/host selection.
        assertTrue(verifier.verify("localhost", session))
        assertFalse(verifier.verify("cached.invalid", session))
        assertFalse(verifier.verify("127.0.0.1", session))
    }

    @Test fun transformedSocketFactoryDelegatesAllSixOverloadsAndBothCipherLists() {
        val factory = vendor("e", "cached.invalid") as SSLSocketFactory
        assertTrue(factory.defaultCipherSuites.isNotEmpty())
        assertTrue(factory.supportedCipherSuites.isNotEmpty())
        assertHttps(factory.createSocket())
        val loopback = InetAddress.getByName("127.0.0.1")
        ServerSocket(0, 16, loopback).use { server ->
            assertHttps(factory.createSocket("127.0.0.1", server.localPort))
            assertHttps(factory.createSocket("127.0.0.1", server.localPort, loopback, 0))
            assertHttps(factory.createSocket(loopback, server.localPort))
            assertHttps(factory.createSocket(loopback, server.localPort, loopback, 0))
            Socket(loopback, server.localPort).use { plain ->
                plain.soTimeout = 2_000
                assertHttps(factory.createSocket(plain, "127.0.0.1", server.localPort, true))
            }
        }
    }

    private fun assertHttps(socket: Socket?) {
        assertTrue("The patched vendor factory must return a real TLS socket", socket is SSLSocket)
        (socket as SSLSocket).use { assertEquals("HTTPS", it.sslParameters.endpointIdentificationAlgorithm) }
    }
}
