package com.laoke.weshirt.platform

import com.laoke.weshirt.platform.push.StrictPlatformTls
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import java.net.Socket
import java.security.cert.CertificateException
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateNotYetValidException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket

class StrictPlatformTlsTest {
    private fun certificate(before: Long, after: Long) = HeldCertificate.Builder().commonName("localhost")
        .addSubjectAlternativeName("localhost").validityInterval(before, after).build()
    private fun validators(chain: Array<X509Certificate>) = listOf<() -> Unit>(
        { StrictPlatformTls.checkServerTrusted(chain, "RSA") },
        { StrictPlatformTls.checkClientTrusted(chain, "RSA") },
        { StrictPlatformTls.checkServerTrusted(chain, "RSA", null as Socket?) },
        { StrictPlatformTls.checkClientTrusted(chain, "RSA", null as Socket?) },
        { StrictPlatformTls.checkServerTrusted(chain, "RSA", SSLContext.getDefault().createSSLEngine("localhost", 443)) },
        { StrictPlatformTls.checkClientTrusted(chain, "RSA", SSLContext.getDefault().createSSLEngine("localhost", 443)) }
    )

    @Test fun everyTrustOverloadRejectsExpiredAndFutureCertificates() {
        val now = System.currentTimeMillis()
        listOf(certificate(now - 120_000, now - 60_000) to CertificateExpiredException::class.java,
            certificate(now + 60_000, now + 120_000) to CertificateNotYetValidException::class.java).forEach { (held, expected) ->
            validators(arrayOf(held.certificate)).forEach { validate ->
                try { validate(); fail("Invalid certificate must not be accepted") }
                catch (error: CertificateException) { assertTrue(expected.isInstance(error)) }
            }
        }
    }

    @Test fun systemTrustRejectsAnUntrustedSelfSignedPeer() {
        val now = System.currentTimeMillis()
        val held = certificate(now - 60_000, now + 60_000)
        try { StrictPlatformTls.checkServerTrusted(arrayOf(held.certificate), "RSA"); fail("Untrusted root accepted") }
        catch (_: CertificateException) { }
        val serverTls = HandshakeCertificates.Builder().heldCertificate(held).build()
        MockWebServer().use { server ->
            server.useHttps(serverTls.sslSocketFactory(), false); server.start()
            (StrictPlatformTls.createSocket("localhost", server.port) as SSLSocket).use { socket ->
                assertEquals("HTTPS", socket.sslParameters.endpointIdentificationAlgorithm)
                try { socket.startHandshake(); fail("An untrusted TLS connection succeeded") }
                catch (_: SSLException) { }
            }
        }
    }

    @Test fun hostnameVerificationUsesTheCurrentPeerRatherThanACachedHost() {
        val now = System.currentTimeMillis()
        val held = certificate(now - 60_000, now + 60_000)
        val serverTls = HandshakeCertificates.Builder().heldCertificate(held).build()
        val testTrust = HandshakeCertificates.Builder().addTrustedCertificate(held.certificate).build()
        MockWebServer().use { server ->
            server.useHttps(serverTls.sslSocketFactory(), false); server.start()
            (testTrust.sslSocketFactory().createSocket("localhost", server.port) as SSLSocket).use { socket ->
                socket.startHandshake()
                assertTrue(StrictPlatformTls.verifyHostname("localhost", socket.session))
                assertFalse(StrictPlatformTls.verifyHostname("wrong.example", socket.session))
                assertFalse(StrictPlatformTls.verifyHostname("127.0.0.1", socket.session))
            }
        }
        assertFalse(StrictPlatformTls.verifyHostname(null, null))
        assertTrue(StrictPlatformTls.getAcceptedIssuers().isNotEmpty())
        assertTrue(StrictPlatformTls.getDefaultCipherSuites().isNotEmpty())
        (StrictPlatformTls.createSocket() as SSLSocket).use { socket -> assertEquals("HTTPS", socket.sslParameters.endpointIdentificationAlgorithm) }
    }
}
