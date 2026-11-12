package com.laoke.weshirt.platform.login

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.laoke.weshirt.AppViewModel
import com.laoke.weshirt.MainActivity
import com.laoke.weshirt.data.SecureSession
import com.laoke.weshirt.platform.push.PushRuntime
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Real Intent/SharedPreferences/lifecycle regression; no DingTalk or remote request is made. */
@RunWith(AndroidJUnit4::class)
class DingTalkCallbackTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
    private val pending get() = app.getSharedPreferences("native_login_pending", Context.MODE_PRIVATE)
    private val preferences get() = app.getSharedPreferences("preferences", Context.MODE_PRIVATE)
    private var previousEndpoint: String? = null

    @Before fun prepare() {
        previousEndpoint = preferences.getString("endpoint", null)
        preferences.edit().putString("endpoint", "").commit()
        DingTalkLoginBridge.clear(app)
        SecureSession(app).clear()
    }

    @After fun cleanup() {
        DingTalkLoginBridge.clear(app)
        SecureSession(app).clear()
        preferences.edit().apply { if (previousEndpoint == null) remove("endpoint") else putString("endpoint", previousEndpoint) }.commit()
    }

    @Test fun sdkExtrasSurviveTaskRecoveryWithoutWakeMarkerAndAreConsumedOnce() {
        val request = pendingRequest()
        // Literal SDK wire names guard the contract rather than reusing our own constants.
        assertTrue(DingTalkLoginBridge.acceptCallback(app, Intent().putExtra("authCode", "fixture-code").putExtra("state", request.state).putExtra("type", "app")))
        val restored = DingTalkLoginBridge.consumeReply(app, Intent(Intent.ACTION_MAIN))
        assertNotNull(restored)
        assertEquals("fixture-code", restored!!.authCode)
        assertEquals(request.state, restored.state)
        assertEquals(request.codeChallenge, DingTalkLoginBridge.challengeFor(restored.codeVerifier))
        assertEquals("fixture-challenge", restored.challengeId)
        assertTrue(restored.eventId.startsWith("client_login_"))
        assertNull(DingTalkLoginBridge.consumeReply(app, Intent().putExtra(DingTalkLoginBridge.REPLY_EXTRA, true)))
        assertFalse(DingTalkLoginBridge.acceptCallback(app, Intent().putExtra("authCode", "duplicate-code").putExtra("state", request.state)))
        assertTrue(pending.all.isEmpty())
    }

    @Test fun forgedStateDoesNotCancelTheRealPendingAuthorization() {
        val request = pendingRequest()
        assertFalse(DingTalkLoginBridge.acceptCallback(app, Intent().putExtra("state", "wrong-state").putExtra("error", "-2")))
        assertTrue(pending.getBoolean("launched", false))
        assertNull(DingTalkLoginBridge.consumeReply(app, Intent().putExtra(DingTalkLoginBridge.REPLY_EXTRA, true)))
        assertTrue(DingTalkLoginBridge.acceptCallback(app, Intent().putExtra("state", request.state).putExtra("authCode", "valid-code")))
        assertEquals("valid-code", DingTalkLoginBridge.consumeReply(app, null)?.authCode)
    }

    @Test fun expiredAndCancelledAuthorizationsRemainVisibleAndCannotExchangeACode() {
        var request = pendingRequest()
        pending.edit().putLong("expires", System.currentTimeMillis() - 1).commit()
        assertTrue(DingTalkLoginBridge.acceptCallback(app, Intent().putExtra("state", request.state).putExtra("authCode", "expired-code").putExtra("type", "app")))
        val expired = DingTalkLoginBridge.consumeReply(app, null)!!
        assertNull(expired.authCode)
        assertEquals("LOGIN_CHALLENGE_EXPIRED", expired.errorCode)
        assertTrue(DingTalkLoginBridge.failureMessage(expired.errorCode!!, expired.eventId).contains("状态码 410"))

        request = pendingRequest()
        assertTrue(DingTalkLoginBridge.acceptCallback(app, Intent().putExtra("state", request.state).putExtra("error", "-2")))
        val cancelled = DingTalkLoginBridge.consumeReply(app, null)!!
        assertNull(cancelled.authCode)
        assertEquals("LOGIN_CANCELLED", cancelled.errorCode)
        assertTrue(DingTalkLoginBridge.failureMessage(cancelled.errorCode!!, cancelled.eventId).contains(cancelled.eventId))
    }

    @Test fun absentSdkCallbackShowsFeedbackWithoutDestroyingLateValidReply() {
        val request = pendingRequest()
        DingTalkLoginBridge.noteBackground(app)
        assertTrue(DingTalkLoginBridge.resumeFeedback(app).orEmpty().contains("状态码 408"))
        assertNull(DingTalkLoginBridge.resumeFeedback(app))
        assertTrue(DingTalkLoginBridge.acceptCallback(app, Intent().putExtra("state", request.state).putExtra("authCode", "late-code")))
        assertEquals("late-code", DingTalkLoginBridge.consumeReply(app, null)?.authCode)
    }

    @Test fun anonymousPushHousekeepingCannotEraseAnInFlightNativeLogin() {
        val request = pendingRequest()
        PushRuntime.clearLocalSession(app)
        assertTrue("Clearing unsigned notification state must preserve authorization", pending.getBoolean("launched", false))
        assertTrue(DingTalkLoginBridge.acceptCallback(app, Intent().putExtra("state", request.state).putExtra("authCode", "still-valid-code")))
        assertEquals("still-valid-code", DingTalkLoginBridge.consumeReply(app, null)?.authCode)
    }

    @Test fun invalidWireCodeAndWebFallbackAreNeverExchanged() {
        for (value in listOf("has space", "x".repeat(2049), "line\nbreak")) {
            val request = pendingRequest()
            assertTrue(DingTalkLoginBridge.acceptCallback(app, Intent().putExtra("state", request.state).putExtra("authCode", value)))
            assertNull(DingTalkLoginBridge.consumeReply(app, null)!!.authCode)
        }
        val request = pendingRequest()
        assertTrue(DingTalkLoginBridge.acceptCallback(app, Intent().putExtra("state", request.state).putExtra("authCode", "web-code").putExtra("type", "web")))
        val rejected = DingTalkLoginBridge.consumeReply(app, null)!!
        assertNull(rejected.authCode)
        assertEquals("LOGIN_CALLBACK_TYPE_INVALID", rejected.errorCode)
    }

    @Test fun changedServiceOnColdStartRejectsVerifiedReplyAndDoesNotCreateSession() {
        val request = pendingRequest()
        app.getSharedPreferences("native_login_origin", Context.MODE_PRIVATE).edit().putString("origin", "https://old-service.invalid").commit()
        assertTrue(DingTalkLoginBridge.acceptCallback(app, Intent().putExtra("state", request.state).putExtra("authCode", "must-not-exchange")))
        // The app loads a different (blank) endpoint and no wake-up boolean.
        ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java)).use { scenario ->
            scenario.onActivity { activity ->
                val vm = ViewModelProvider(activity)[AppViewModel::class.java]
                assertTrue(vm.state.value.info.orEmpty().contains("服务地址已变更"))
                assertTrue(vm.state.value.info.orEmpty().contains("状态码 409"))
                assertTrue(vm.state.value.info.orEmpty().contains("事件 client_login_"))
                assertNull(vm.session.read())
            }
            scenario.recreate()
            scenario.onActivity { assertNull(ViewModelProvider(it)[AppViewModel::class.java].session.read()) }
        }
        assertTrue(pending.all.isEmpty())
    }

    @Test fun realSdkComponentReturnsToExistingMainActivityAndExchangesOnlyOnce() {
        val exchanged = CountDownLatch(1)
        val anonymousRefresh = CountDownLatch(1)
        val calls = AtomicInteger()
        val sent = AtomicReference<JSONObject>()
        val model = AtomicReference<AppViewModel>()
        val origin = "https://login-fixture.invalid"
        val request = pendingRequest()
        app.getSharedPreferences("native_login_origin", Context.MODE_PRIVATE).edit().putString("origin", origin).commit()
        ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java)).use { scenario ->
            scenario.onActivity { activity ->
                val vm = ViewModelProvider(activity)[AppViewModel::class.java]
                model.set(vm)
                vm.api.baseUrl = origin
                assertTrue("Signed-out ViewModel initialization must not clear the SDK request", pending.getBoolean("launched", false))
                val client = OkHttpClient.Builder().addInterceptor { chain ->
                    val httpRequest = chain.request()
                    val body = when (httpRequest.url.encodedPath) {
                        "/api/mobile/auth/exchange" -> {
                            val buffer = okio.Buffer(); httpRequest.body!!.writeTo(buffer)
                            sent.set(JSONObject(buffer.readUtf8()))
                            calls.incrementAndGet(); exchanged.countDown()
                            "{\"accessToken\":\"fixture-access\",\"refreshToken\":\"fixture-refresh\",\"expiresIn\":900}"
                        }
                        "/api/site" -> "{\"buildings\":[]}"
                        "/api/auth/me" -> {
                            assertEquals("Bearer fixture-access", httpRequest.header("Authorization"))
                            "{\"user\":{\"id\":\"login-fixture\",\"nick\":\"测试账号\",\"dingNick\":\"钉钉测试账号\",\"role\":\"admin\"}}"
                        }
                        "/api/user/permissions" -> {
                            assertEquals("Bearer fixture-access", httpRequest.header("Authorization"))
                            "{\"adminVisible\":true,\"permissions\":{\"tasks\":{\"view\":true,\"operate\":true}}}"
                        }
                        else -> "{}"
                    }
                    Response.Builder().request(httpRequest).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                        .body(body.toResponseBody("application/json".toMediaType())).build()
                }.build()
                vm.api.javaClass.getDeclaredField("http").apply { isAccessible = true }.set(vm.api, client)
                // Exercise the same signed-out synchronization that used to erase
                // pending state. Never recreate or reseed the pending request here.
                vm.refresh(forceMetadata = true).invokeOnCompletion { anonymousRefresh.countDown() }
            }
            assertTrue("The signed-out refresh must finish before the provider returns", anonymousRefresh.await(10, TimeUnit.SECONDS))
            scenario.onActivity { activity ->
                assertTrue("Anonymous board synchronization must preserve the pending SDK request", pending.getBoolean("launched", false))
                assertEquals(origin, app.getSharedPreferences("native_login_origin", Context.MODE_PRIVATE).getString("origin", null))
                activity.startActivity(callbackIntent(request.state))
            }
            assertTrue("The SDK Activity must trigger the real exchange path", exchanged.await(10, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (SecureSession(app).read() == null && System.nanoTime() < deadline) Thread.sleep(30)
            assertEquals("fixture-access", SecureSession(app).read()?.optString("accessToken"))
            assertEquals(request.state, sent.get().getString("state"))
            assertEquals("fixture-code", sent.get().getString("authCode"))
            assertEquals(request.codeChallenge, DingTalkLoginBridge.challengeFor(sent.get().getString("codeVerifier")))
            val profileDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while ((model.get().state.value.profile?.id != "login-fixture" || model.get().state.value.loading) && System.nanoTime() < profileDeadline) Thread.sleep(30)
            val loggedIn = model.get().state.value
            assertEquals("The successful exchange must load the account shown by the app", "login-fixture", loggedIn.profile?.id)
            assertEquals("测试账号", loggedIn.profile?.nick)
            assertEquals("钉钉测试账号", loggedIn.profile?.dingNick)
            assertTrue("The account's server permissions must be available after login", loggedIn.access.optBoolean("adminVisible"))
            assertNull("Successful login must not leave a refresh error behind", loggedIn.error)
            scenario.onActivity { it.startActivity(callbackIntent(request.state)) }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            assertEquals("Replayed SDK intents must not exchange a code twice", 1, calls.get())
        }
    }

    @Test fun exchangeFailureReturnsToTheAppWithStatusAndEventInsteadOfCreatingASession() {
        val exchanged = CountDownLatch(1)
        val model = AtomicReference<AppViewModel>()
        val origin = "https://login-fixture.invalid"
        val request = pendingRequest()
        app.getSharedPreferences("native_login_origin", Context.MODE_PRIVATE).edit().putString("origin", origin).commit()
        ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java)).use { scenario ->
            scenario.onActivity { activity ->
                val vm = ViewModelProvider(activity)[AppViewModel::class.java]
                model.set(vm)
                vm.api.baseUrl = origin
                val client = OkHttpClient.Builder().addInterceptor { chain ->
                    val httpRequest = chain.request()
                    val isExchange = httpRequest.url.encodedPath == "/api/mobile/auth/exchange"
                    if (isExchange) exchanged.countDown()
                    Response.Builder().request(httpRequest).protocol(Protocol.HTTP_1_1)
                        .code(if (isExchange) 502 else 200).message(if (isExchange) "Bad Gateway" else "OK")
                        .body((if (isExchange) "{\"error\":{\"code\":\"provider_unavailable\",\"message\":\"登录服务暂不可用，请重试\",\"eventId\":\"fixture_exchange_failure\"}}" else "{\"buildings\":[]}")
                            .toResponseBody("application/json".toMediaType())).build()
                }.build()
                vm.api.javaClass.getDeclaredField("http").apply { isAccessible = true }.set(vm.api, client)
                activity.startActivity(callbackIntent(request.state))
            }
            assertTrue("The SDK return must reach the real exchange path", exchanged.await(10, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (model.get().state.value.error == null && System.nanoTime() < deadline) Thread.sleep(30)
            val error = model.get().state.value.error.orEmpty()
            assertTrue("The app must expose the actionable provider failure", error.contains("登录服务暂不可用"))
            assertTrue(error.contains("状态码 502"))
            assertTrue(error.contains("事件 fixture_exchange_failure"))
            assertNull(model.get().session.read())
            assertNull(model.get().state.value.profile)
            assertTrue("A failed exchange must still consume the one-time callback locally", pending.all.isEmpty())
        }
    }

    @Test fun nativeSdkCallbackComponentIsExportedAndResolvesForCurrentApplicationId() {
        val component = ComponentName(app.packageName, app.packageName + ".ddauth.DDAuthActivity")
        @Suppress("DEPRECATION") val info = app.packageManager.getActivityInfo(component, 0)
        assertTrue(info.exported)
        assertEquals("com.laoke.weshirt.ddauth.DDAuthActivity", info.targetActivity ?: info.name)
    }

    private fun pendingRequest(): NativeLoginRequest {
        val request = DingTalkLoginBridge.createRequest(app, "instrumented-installation")
        // Backend challenge is fixed locally; the test never launches a real provider.
        pending.edit().putString("challenge", "fixture-challenge").putBoolean("launched", true)
            .putLong("expires", System.currentTimeMillis() + 180_000).commit()
        return request
    }

    private fun callbackIntent(state: String) = Intent().setComponent(ComponentName(app.packageName, app.packageName + ".ddauth.DDAuthActivity"))
        .putExtra("authCode", "fixture-code").putExtra("state", state).putExtra("type", "app")
}
