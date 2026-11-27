package com.laoke.weshirt.platform

import com.laoke.weshirt.platform.update.AppUpdateClient
import com.laoke.weshirt.platform.update.AppUpdateException
import com.laoke.weshirt.platform.update.UpdateDownloadCancellation
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ExecutionException
import java.util.concurrent.CancellationException

class AppUpdateClientTest {
    private lateinit var pages: MockWebServer
    private lateinit var transport: OkHttpClient
    private lateinit var client: AppUpdateClient
    private val updates = Files.createTempDirectory("weshirt-update-test").toFile()
    private val apk = ByteArray(65_536) { (it % 23).toByte() }
    private val path = "/updates/android/WeShirt-v0.1.1-build16.apk"
    private val digest = MessageDigest.getInstance("SHA-256").digest(apk).joinToString("") { "%02x".format(it) }
    private val origin get() = pages.url("/").newBuilder().host("127.0.0.1").build()

    @Before fun prepare() {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("127.0.0.1").build()
        val server = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val trusted = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        pages = MockWebServer().apply {
            useHttps(server.sslSocketFactory(), false)
            start(InetAddress.getByName("127.0.0.1"), 0)
        }
        transport = OkHttpClient.Builder().sslSocketFactory(trusted.sslSocketFactory(), trusted.trustManager)
            .connectTimeout(3, TimeUnit.SECONDS).readTimeout(3, TimeUnit.SECONDS).build()
        client = AppUpdateClient(origin.toString(), transport)
    }

    @After fun cleanup() {
        transport.connectionPool.evictAll()
        transport.dispatcher.executorService.shutdown()
        pages.shutdown()
        updates.deleteRecursively()
    }

    @Test fun onlyHigherVersionCodesAreOfferedAndRequestIsAnonymous() {
        enqueue(manifest())
        enqueue(manifest())
        enqueue(manifest().put("apk", origin.resolve(path).toString()))
        assertNull(client.check(16))
        assertNull(client.check(17))
        val release = client.check(15)!!
        assertEquals(16L, release.versionCode)
        assertEquals("0.1.1", release.versionName)
        assertEquals(origin.resolve(path), release.apk)
        val request = pages.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("/updates/android/latest.json", request.path)
        assertEquals("no-cache", request.getHeader("Cache-Control"))
        assertNull(request.getHeader("Authorization"))
        assertNull(request.getHeader("Cookie"))
    }

    @Test fun rejectsCrossOriginCleartextAndPathsOutsideTheApkDirectory() {
        val unsafe = listOf(
            "https://other.invalid$path",
            origin.newBuilder().port(9999).build().resolve(path).toString(),
            origin.resolve(path).toString().replace("https://", "http://"),
            "//127.0.0.1:${origin.port}$path",
            origin.resolve(path).toString().replace("https://", "https://user@"),
            "/updates/android/test/../update.apk",
            "/updates/android/%2e%2e/update.apk",
            "/other/update.apk",
            "$path?external=1",
            "$path#extra",
        )
        unsafe.forEach {
            enqueue(manifest().put("apk", it))
            assertEquals("MANIFEST_INVALID", assertThrows(AppUpdateException::class.java) { client.check(15) }.code)
        }
        assertEquals(unsafe.size, pages.requestCount)
        assertFalse(AppUpdateClient("http://127.0.0.1", transport).configured)
    }

    @Test fun rejectsMissingMalformedAndUnboundedMetadata() {
        val malformed = listOf(
            manifest().put("versionCode", "16"),
            manifest().put("versionCode", 16.5),
            manifest().put("size", 0),
            manifest().put("size", 250L * 1024 * 1024 + 1),
            manifest().put("sha256", "a".repeat(63)),
            manifest().put("versionName", "版本\n控制字符"),
            manifest().apply { remove("apk") },
        )
        malformed.forEach { enqueue(it) }
        pages.enqueue(MockResponse().setBody("<html>Pages route fallback</html>"))
        pages.enqueue(MockResponse().setBody(" ".repeat(16_385) + manifest()))
        repeat(malformed.size + 2) {
            assertEquals("MANIFEST_INVALID", assertThrows(AppUpdateException::class.java) { client.check(15) }.code)
        }
    }

    @Test fun refusesRedirectsForBothManifestAndApk() {
        pages.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/untrusted-manifest"))
        assertEquals("MANIFEST_HTTP_302", assertThrows(AppUpdateException::class.java) { client.check(15) }.code)
        enqueue(manifest())
        val release = client.check(15)!!
        pages.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/untrusted-apk"))
        assertEquals("APK_HTTP_302", assertThrows(AppUpdateException::class.java) {
            client.download(release, updates) {}
        }.code)
        assertEquals(3, pages.requestCount)
        assertTrue(updates.listFiles()!!.isEmpty())
    }

    @Test fun onlyCompleteVerifiedBytesReachTheFinalFileAndCorruptCacheIsReplaced() {
        enqueue(manifest())
        val release = client.check(15)!!
        pages.enqueue(MockResponse().setBody(Buffer().write(apk.copyOf(apk.size - 1))))
        assertEquals("APK_SIZE_MISMATCH", assertThrows(AppUpdateException::class.java) {
            client.download(release, updates) {}
        }.code)
        pages.enqueue(MockResponse().setBody(Buffer().write(ByteArray(apk.size))))
        assertEquals("APK_SHA256_MISMATCH", assertThrows(AppUpdateException::class.java) {
            client.download(release, updates) {}
        }.code)
        assertTrue(updates.listFiles()!!.isEmpty())
        pages.enqueue(MockResponse().setBody(Buffer().write(apk)))
        val progress = mutableListOf<Float>()
        val installed = client.download(release, updates, progress::add)
        assertTrue(progress.isNotEmpty())
        assertTrue(progress.zipWithNext().all { (previous, next) -> previous < next })
        assertArrayEquals(apk, installed.readBytes())
        assertEquals(installed, client.download(release, updates) {})
        assertEquals(4, pages.requestCount)

        installed.writeBytes(ByteArray(apk.size))
        pages.enqueue(MockResponse().setBody(Buffer().write(apk)))
        assertArrayEquals(apk, client.download(release, updates) {}.readBytes())
        assertEquals(listOf(installed), updates.listFiles()!!.toList())
        assertEquals(5, pages.requestCount)
    }

    @Test fun cancellationInterruptsBlockedHttpReadAndRemovesThePartialPackage() {
        enqueue(manifest())
        val release = client.check(15)!!
        pages.enqueue(MockResponse().setBody(Buffer().write(apk)).throttleBody(1024, 2, TimeUnit.SECONDS))
        val control = UpdateDownloadCancellation()
        val received = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit<java.io.File> {
                client.downloadCancelable(release, updates, control) { if (it > 0) received.countDown() }
            }
            assertTrue("The transfer must receive actual HTTPS bytes before cancellation", received.await(3, TimeUnit.SECONDS))
            control.cancel()
            val failure = assertThrows(ExecutionException::class.java) { result.get(2, TimeUnit.SECONDS) }
            assertTrue(failure.cause is CancellationException)
            assertTrue("Cancellation must not retain a final or partially downloaded package", updates.listFiles()!!.isEmpty())
        } finally { control.cancel(); executor.shutdownNow() }
    }

    private fun manifest() = JSONObject().put("versionCode", 16).put("versionName", "0.1.1")
        .put("apk", path).put("size", apk.size).put("sha256", digest)

    private fun enqueue(value: JSONObject) {
        pages.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(value.toString()))
    }
}
