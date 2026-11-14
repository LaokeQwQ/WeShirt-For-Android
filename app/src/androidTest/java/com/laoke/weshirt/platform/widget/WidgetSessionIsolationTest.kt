package com.laoke.weshirt.platform.widget

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.laoke.weshirt.data.ApiClient
import com.laoke.weshirt.data.SecureSession
import com.laoke.weshirt.data.str
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real encrypted storage shared by distinct foreground/background instances. */
@RunWith(AndroidJUnit4::class)
class WidgetSessionIsolationTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
    private lateinit var foreground: SecureSession
    private lateinit var background: SecureSession
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var origin: String

    @Before fun prepare() {
        foreground = SecureSession(app); foreground.clear(); background = SecureSession(app)
        val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("127.0.0.1").build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server = MockWebServer().apply { useHttps(serverCertificates.sslSocketFactory(), false); start(InetAddress.getByName("127.0.0.1"), 0) }
        origin = server.url("/").toString().trimEnd('/')
        client = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager)
            .protocols(listOf(Protocol.HTTP_1_1)).retryOnConnectionFailure(false)
            .followRedirects(false).followSslRedirects(false).callTimeout(6, TimeUnit.SECONDS).build()
    }
    @After fun cleanup() {
        foreground.clear(); server.shutdown(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
    }
    private fun session(id: String, expires: Long = 0) = JSONObject().put("origin", origin)
        .put("accessToken", "access-$id").put("refreshToken", "refresh-$id").put("expiresAt", expires)
        .put("user", JSONObject().put("id", id)).put("profile", JSONObject().put("id", id).put("nick", "测试用户"))
    private fun json(body: JSONObject, status: Int = 200) = MockResponse().setResponseCode(status)
        .setHeader("Content-Type", "application/json").setBody(body.toString())

    @Test fun compareAndSetAcrossInstancesCannotReplaceANewLoginOrAProfileCacheUpdate() {
        val a = session("one"); foreground.save(a)
        assertEquals(foreground.installationId, background.installationId)
        val captured = background.read()!!
        foreground.save(session("two"))
        assertFalse(background.compareAndSet(captured, session("obsolete")))
        assertFalse(background.compareAndSet(captured, null))
        assertEquals("two", foreground.read()!!.getJSONObject("user").str("id"))
        val b = background.read()!!
        foreground.save(JSONObject(b.toString()).put("profile", JSONObject().put("id", "two").put("nick", "更新资料")))
        assertFalse(background.compareAndSet(b, null))
        assertEquals("更新资料", foreground.read()!!.getJSONObject("profile").str("nick"))
    }

    @Test fun lateSuccessfulRefreshCannotOverwriteAnotherInstancesNewAccount() = runBlocking {
        foreground.save(session("one"))
        val bound = WidgetSessionStore(background, background.read()!!) { true }
        val api = ApiClient(bound, app.cacheDir, client).also { it.baseUrl = origin }
        server.enqueue(json(JSONObject().put("accessToken", "rotated-one").put("refreshToken", "rotated-refresh-one").put("expiresIn", 900)
            .put("user", JSONObject().put("id", "one"))).setBodyDelay(400, TimeUnit.MILLISECONDS))
        val request = async(Dispatchers.IO) { api.call("/api/auth/me") }
        assertEquals("/api/mobile/auth/refresh", server.takeRequest(4, TimeUnit.SECONDS)?.path)
        foreground.save(session("two", System.currentTimeMillis() + 600_000))
        try { request.await(); fail("Late refresh must cancel") } catch (_: CancellationException) { }
        assertEquals("access-two", foreground.read()!!.str("accessToken"))
        assertEquals(1, server.requestCount)
    }

    @Test fun lateUnauthorizedResponseCannotClearAnotherInstancesNewAccount() = runBlocking {
        foreground.save(session("one", System.currentTimeMillis() + 600_000))
        val bound = WidgetSessionStore(background, background.read()!!) { true }
        val api = ApiClient(bound, app.cacheDir, client).also { it.baseUrl = origin }
        server.enqueue(json(JSONObject().put("error", JSONObject().put("message", "会话失效").put("code", "unauthorized")), 401)
            .setBodyDelay(400, TimeUnit.MILLISECONDS))
        val request = async(Dispatchers.IO) { api.call("/api/auth/me") }
        assertEquals("/api/auth/me", server.takeRequest(4, TimeUnit.SECONDS)?.path)
        foreground.save(session("two", System.currentTimeMillis() + 600_000))
        try { request.await(); fail("Obsolete unauthorized response must cancel") } catch (_: CancellationException) { }
        assertEquals("access-two", foreground.read()!!.str("accessToken"))
    }

    @Test fun validBackgroundRefreshUsesCasAndRetainsTheMatchingCachedProfile() = runBlocking {
        foreground.save(session("one"))
        val bound = WidgetSessionStore(background, background.read()!!) { true }
        val api = ApiClient(bound, app.cacheDir, client).also { it.baseUrl = origin }
        server.enqueue(json(JSONObject().put("accessToken", "new-access").put("refreshToken", "new-refresh").put("expiresIn", 900)
            .put("user", JSONObject().put("id", "one"))))
        server.enqueue(json(JSONObject().put("user", JSONObject().put("id", "one"))))
        assertEquals("one", api.call("/api/auth/me").getJSONObject("user").str("id"))
        assertEquals("new-access", foreground.read()!!.str("accessToken"))
        assertEquals("测试用户", foreground.read()!!.getJSONObject("profile").str("nick"))
        assertEquals("/api/mobile/auth/refresh", server.takeRequest(3, TimeUnit.SECONDS)?.path)
        assertEquals("Bearer new-access", server.takeRequest(3, TimeUnit.SECONDS)?.getHeader("Authorization"))
    }

    @Test fun widgetAndForegroundSerializeRefreshBeforeALosing401CanEraseRealEncryptedSession() = runBlocking {
        foreground.save(session("one"))
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val refreshes = AtomicInteger(); val invalidations = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/api/mobile/auth/refresh" -> if (refreshes.incrementAndGet() == 1) {
                    entered.countDown(); check(release.await(4, TimeUnit.SECONDS))
                    json(JSONObject().put("accessToken", "widget-winner").put("refreshToken", "widget-next-refresh").put("expiresIn", 900)
                        .put("user", JSONObject().put("id", "one")))
                } else json(JSONObject().put("error", JSONObject().put("code", "invalid_refresh_token")), 401)
                "/api/auth/me" -> {
                    assertEquals("Bearer widget-winner", request.getHeader("Authorization"))
                    json(JSONObject().put("user", JSONObject().put("id", "one")))
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
        val api = ApiClient(foreground, app.cacheDir, client).also { it.baseUrl = origin; it.onSessionInvalidated = { invalidations.incrementAndGet() } }
        val widget = ApiClient(WidgetSessionStore(background, background.read()!!) { true }, app.cacheDir, client).also { it.baseUrl = origin }
        val refreshingWidget = async(Dispatchers.IO) { widget.call("/api/auth/me") }
        try {
            assertTrue(entered.await(4, TimeUnit.SECONDS))
            val started = CountDownLatch(1)
            val waitingForeground = async(Dispatchers.IO) { started.countDown(); api.call("/api/auth/me") }
            assertTrue(started.await(4, TimeUnit.SECONDS)); delay(100)
            assertEquals(1, refreshes.get()); assertFalse(waitingForeground.isCompleted)
            release.countDown()
            assertEquals("one", refreshingWidget.await().getJSONObject("user").str("id"))
            assertEquals("one", waitingForeground.await().getJSONObject("user").str("id"))
            assertEquals("widget-winner", foreground.read()!!.str("accessToken"))
            assertEquals("测试用户", foreground.read()!!.getJSONObject("profile").str("nick"))
            assertEquals(0, invalidations.get())
            assertEquals("one", api.call("/api/auth/me").getJSONObject("user").str("id"))
            assertEquals(1, refreshes.get()); assertEquals(4, server.requestCount)
        } finally { release.countDown() }
    }

    @Test fun foreground401WaitsUntilWidgetsPendingWinningRotationIsSavedInEncryptedStorage() = runBlocking {
        val expires = System.currentTimeMillis() + 32_000
        foreground.save(session("one", expires))
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val rotatedRemotely = CountDownLatch(1); val releaseWinner = CountDownLatch(1)
        val received401 = CountDownLatch(1); val invalidated = CountDownLatch(1)
        val observed = client.newBuilder().eventListener(object : okhttp3.EventListener() {
            override fun responseBodyEnd(call: okhttp3.Call, byteCount: Long) {
                if (call.request().url.query == "old") received401.countDown()
            }
        }).build()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/api/auth/me?old" -> {
                    assertEquals("Bearer access-one", request.getHeader("Authorization"))
                    entered.countDown(); check(release.await(4, TimeUnit.SECONDS))
                    json(JSONObject().put("error", JSONObject().put("code", "unauthorized")), 401)
                }
                "/api/mobile/auth/refresh" -> {
                    rotatedRemotely.countDown(); check(releaseWinner.await(4, TimeUnit.SECONDS))
                    json(JSONObject().put("accessToken", "widget-new-access").put("refreshToken", "widget-new-refresh").put("expiresIn", 900)
                        .put("user", JSONObject().put("id", "one")))
                }
                "/api/auth/me" -> {
                    assertEquals("Bearer widget-new-access", request.getHeader("Authorization"))
                    json(JSONObject().put("user", JSONObject().put("id", "one")))
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
        val api = ApiClient(foreground, app.cacheDir, observed).also { it.baseUrl = origin; it.onSessionInvalidated = { invalidated.countDown() } }
        val obsolete = async(Dispatchers.IO) { api.call("/api/auth/me?old") }
        try {
            assertTrue(entered.await(4, TimeUnit.SECONDS))
            // Keep exactly the captured encrypted record: altering its expiresAt
            // would hide the pending-winner bug by causing an unrelated CAS miss.
            delay((expires - System.currentTimeMillis() - 30_000 + 25).coerceAtLeast(0))
            val widget = ApiClient(WidgetSessionStore(background, background.read()!!) { true }, app.cacheDir, client).also { it.baseUrl = origin }
            val winner = async(Dispatchers.IO) { widget.call("/api/auth/me") }
            assertTrue(rotatedRemotely.await(4, TimeUnit.SECONDS))
            release.countDown(); assertTrue(received401.await(4, TimeUnit.SECONDS))
            assertFalse("An old 401 cannot clear storage while the widget winner is in transit", invalidated.await(150, TimeUnit.MILLISECONDS))
            assertFalse(obsolete.isCompleted); assertEquals("access-one", foreground.read()!!.str("accessToken"))
            releaseWinner.countDown(); winner.await()
            try { obsolete.await(); fail("A response bound to the old token cannot invalidate the winner") } catch (_: CancellationException) { }
            assertEquals("widget-new-access", foreground.read()!!.str("accessToken"))
            assertEquals("widget-new-refresh", foreground.read()!!.str("refreshToken")); assertEquals(1L, invalidated.count)
            assertEquals(3, server.requestCount)
        } finally { release.countDown(); releaseWinner.countDown() }
    }

    @Test fun profileCachingWhileWidgetRefreshIsPendingPreservesNewCredentialsAndLatestEncryptedProfile() = runBlocking {
        foreground.save(session("one"))
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/api/mobile/auth/refresh" -> {
                    entered.countDown(); check(release.await(4, TimeUnit.SECONDS))
                    json(JSONObject().put("accessToken", "profile-race-winner").put("refreshToken", "profile-race-next").put("expiresIn", 900)
                        .put("user", JSONObject().put("id", "one")))
                }
                "/api/auth/me" -> {
                    assertEquals("Bearer profile-race-winner", request.getHeader("Authorization"))
                    json(JSONObject().put("user", JSONObject().put("id", "one")))
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
        val widget = ApiClient(WidgetSessionStore(background, background.read()!!) { true }, app.cacheDir, client).also { it.baseUrl = origin }
        val refreshing = async(Dispatchers.IO) { widget.call("/api/auth/me") }
        try {
            assertTrue(entered.await(4, TimeUnit.SECONDS))
            ApiClient(foreground, app.cacheDir, client).also { it.baseUrl = origin }
                .cacheProfile(JSONObject().put("id", "one").put("nick", "最新缓存资料").put("dingNick", "最新关联资料"))
            release.countDown(); assertEquals("one", refreshing.await().getJSONObject("user").str("id"))
            val saved = foreground.read()!!
            assertEquals("profile-race-winner", saved.str("accessToken")); assertEquals("profile-race-next", saved.str("refreshToken"))
            assertEquals("最新缓存资料", saved.getJSONObject("profile").str("nick")); assertEquals("最新关联资料", saved.getJSONObject("profile").str("dingNick"))
            assertEquals(2, server.requestCount)
        } finally { release.countDown() }
    }

    @Test fun cancellationInvalidatesAnInFlightWidgetBeforeEncryptedLogoutFinishes() {
        foreground.save(session("one"))
        val version = LaundryWidgetRefresh.generation(app)
        val bound = WidgetSessionStore(background, background.read()!!) { LaundryWidgetRefresh.generation(app) == version }
        LaundryWidgetRefresh.cancel(app)
        assertNotEquals(version, LaundryWidgetRefresh.generation(app))
        try { bound.read(); fail("Old widget generation must stop before the session is cleared") } catch (_: CancellationException) { }
        assertEquals("access-one", foreground.read()!!.str("accessToken"))
    }

    @Test fun refreshClickCooldownPersistsForTheScopeAndDoesNotBlockANewAccount() {
        val scope = LaundryWidgetScope("1".repeat(64), origin, "one")
        val other = LaundryWidgetScope("2".repeat(64), origin, "two")
        LaundryWidgetRefresh.cancel(app)
        val now = System.currentTimeMillis()
        assertTrue(LaundryWidgetRefresh.acquire(app, scope, now))
        assertFalse(LaundryWidgetRefresh.acquire(app.createConfigurationContext(app.resources.configuration), scope, now + 29_999))
        assertTrue(LaundryWidgetRefresh.acquire(app, scope, now + 30_000))
        assertTrue(LaundryWidgetRefresh.acquire(app, other, now + 30_001))
        LaundryWidgetRefresh.cancel(app)
    }
}
