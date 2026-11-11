package com.laoke.weshirt.platform

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.laoke.weshirt.platform.push.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real preferences, component switches and registration queue; no provider network. */
@RunWith(AndroidJUnit4::class)
class PushFallbackInstrumentationTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
    private val prefs get() = app.getSharedPreferences("push_installation", Context.MODE_PRIVATE)
    private val configuration = PushConfiguration(consent = true,
        firebase = FirebasePushConfig("fixture-app", "fixture-api", "fixture-project", "fixture-sender"),
        umeng = UmengPushConfig("fixture-app", "fixture-message"))
    private lateinit var sdk: FakeSdk
    private lateinit var originalSdk: PushSdk

    @Before fun prepare() {
        runBlocking { PushRuntime.logout(app) }
        sdk = FakeSdk()
        originalSdk = PushRuntime.replaceSdkForTest(app, sdk)
        prefs.edit().remove("fcmFailureAt").commit()
    }

    @After fun cleanup() {
        runBlocking { PushRuntime.logout(app) }
        PushRuntime.replaceSdkForTest(app, originalSdk)
        prefs.edit().remove("fcmFailureAt").commit()
    }

    @Test fun successfulFcmIsPreferredAndDuplicateInitializationDoesNotDoubleRegister() {
        val sink = Sink()
        PushRuntime.initialize(app, configuration, sink)
        PushRuntime.onSessionAvailable(app)
        assertEquals(1, sdk.fcm.size)
        assertEquals(0, sdk.umeng.size)
        sdk.fcm.single().token("fcm-fixture-token")
        await { PushRuntime.registrationStatus == "REGISTERED" }
        repeat(4) { PushRuntime.initialize(app, configuration, sink); PushRuntime.onSessionAvailable(app) }
        assertEquals(1, sdk.fcm.size)
        assertEquals(0, sdk.umeng.size)
        assertEquals(listOf(PushProvider.FCM), sink.registrations.map { it.provider })
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, component("com.umeng.message.component.UmengMessageProvider"))
        // A late failure from the completed task cannot undo a usable token.
        sdk.fcm.single().failed()
        assertEquals(PushProvider.FCM, PushRuntime.route.provider)
    }

    @Test fun tokenFailureUsesOnlyUmengAndIgnoresLateFcmCallbacks() {
        val sink = Sink()
        PushRuntime.initialize(app, configuration, sink)
        PushRuntime.onSessionAvailable(app)
        val old = sdk.fcm.single()
        old.failed()
        assertEquals(PushProvider.UMENG, PushRuntime.route.provider)
        assertTrue(prefs.getLong("fcmFailureAt", 0L) > 0L)
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, component("com.google.firebase.iid.FirebaseInstanceIdReceiver"))
        sdk.umeng.single().token("umeng-fixture-token")
        await { PushRuntime.registrationStatus == "REGISTERED" }
        old.token("late-fcm-token")
        old.failed()
        PushRuntime.onFirebaseTokenChanged(app)
        assertEquals("umeng-fixture-token", prefs.getString("token", null))
        assertEquals(listOf(PushProvider.UMENG), sink.registrations.map { it.provider })
        assertEquals(PushProvider.UMENG, PushRuntime.route.provider)
        repeat(4) { PushRuntime.initialize(app, configuration, sink) }
        assertEquals(1, sdk.fcm.size)
        assertEquals(1, sdk.umeng.size)
    }

    @Test fun throwingFcmInitializationAndATimedOutRequestBothFallBack() {
        sdk.throwFcm = true
        assertEquals(PushProvider.UMENG, PushRuntime.initialize(app, configuration).provider)
        assertEquals(1, sdk.umeng.size)
        PushRuntime.clearLocalSession(app)
        prefs.edit().remove("fcmFailureAt").commit()
        sdk.throwFcm = false
        PushRuntime.replaceSdkForTest(app, sdk, timeoutMillis = 50L)
        assertEquals(PushProvider.FCM, PushRuntime.initialize(app, configuration).provider)
        await { PushRuntime.route.provider == PushProvider.UMENG && sdk.umeng.size == 2 }
        assertEquals(2, sdk.umeng.size)
        assertFalse(prefs.contains("token"))
    }

    @Test fun fallbackSurvivesRestartButConsentRevocationInvalidatesEveryCallback() {
        PushRuntime.initialize(app, configuration)
        PushRuntime.onSessionAvailable(app)
        sdk.fcm.single().failed()
        val oldUmeng = sdk.umeng.single()
        PushRuntime.clearLocalSession(app)
        val restartedSdk = FakeSdk()
        PushRuntime.replaceSdkForTest(app, restartedSdk)
        assertEquals(PushProvider.UMENG, PushRuntime.initialize(app, configuration).provider)
        assertEquals(0, restartedSdk.fcm.size)
        PushRuntime.onSessionAvailable(app)
        val currentUmeng = restartedSdk.umeng.single()
        PushRuntime.initialize(app, configuration.copy(consent = false))
        oldUmeng.token("old-umeng-token")
        currentUmeng.token("current-but-revoked-token")
        currentUmeng.failed()
        assertFalse(prefs.contains("token"))
        assertFalse(PushRuntime.notificationsAllowed(app))
        assertEquals(PushProvider.NONE, PushRuntime.route.provider)
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, component("com.umeng.message.component.UmengMessageProvider"))
    }

    @Test fun callbackFromOldAccountCannotOverwriteANewAttemptWithTheSameProvider() {
        val oldSink = Sink()
        PushRuntime.initialize(app, configuration, oldSink)
        PushRuntime.onSessionAvailable(app)
        val old = sdk.fcm.single()
        PushRuntime.clearLocalSession(app)
        val newSink = Sink()
        PushRuntime.initialize(app, configuration, newSink)
        PushRuntime.onSessionAvailable(app)
        val fresh = sdk.fcm.last()
        old.token("old-account-token")
        old.failed()
        assertFalse(prefs.contains("token"))
        assertEquals(PushProvider.FCM, PushRuntime.route.provider)
        fresh.token("current-account-token")
        await { PushRuntime.registrationStatus == "REGISTERED" }
        assertTrue(oldSink.registrations.isEmpty())
        assertEquals(listOf("current-account-token"), newSink.registrations.map { it.token })
    }

    @Test fun inFlightOldRegistrationDoesNotSetTheNewSessionsStatusOrDisableItsToken() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val oldSink = object : PushRegistrationSink {
            override suspend fun register(registration: PushRegistration) { entered.complete(Unit); release.await() }
            override suspend fun unregister(installationId: String) = Unit
        }
        try {
            PushRuntime.initialize(app, configuration, oldSink)
            PushRuntime.onSessionAvailable(app)
            val old = sdk.fcm.single()
            old.token("in-flight-old-token")
            await { entered.isCompleted }
            PushRuntime.clearLocalSession(app)
            val currentSink = Sink()
            PushRuntime.initialize(app, configuration, currentSink)
            PushRuntime.onSessionAvailable(app)
            sdk.fcm.last().token("new-token")
            old.failed()
            release.complete(Unit)
            await { PushRuntime.registrationStatus == "REGISTERED" }
            assertEquals("new-token", prefs.getString("token", null))
            assertEquals(listOf("new-token"), currentSink.registrations.map { it.token })
            assertEquals(PushProvider.FCM, PushRuntime.route.provider)
        } finally { release.complete(Unit) }
    }

    private fun component(name: String) = app.packageManager.getComponentEnabledSetting(ComponentName(app.packageName, name))
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue("The asynchronous push state must settle", condition())
    }

    private class Sink : PushRegistrationSink {
        val registrations: MutableList<PushRegistration> = Collections.synchronizedList(mutableListOf())
        override suspend fun register(registration: PushRegistration) { registrations.add(registration) }
        override suspend fun unregister(installationId: String) = Unit
    }
    private data class Call(val token: (String) -> Unit, val failed: () -> Unit)
    private class FakeSdk : PushSdk {
        val fcm: MutableList<Call> = Collections.synchronizedList(mutableListOf())
        val umeng: MutableList<Call> = Collections.synchronizedList(mutableListOf())
        val stoppedFcm = AtomicInteger()
        var throwFcm = false
        override fun googleServicesAvailable(context: Context) = true
        override fun startFirebase(context: Context, config: FirebasePushConfig, token: (String) -> Unit, failed: () -> Unit) {
            if (throwFcm) throw IllegalStateException("fixture initialization failure")
            fcm.add(Call(token, failed))
        }
        override fun stopFirebase() { stoppedFcm.incrementAndGet() }
        override fun startUmeng(context: Context, config: UmengPushConfig, token: (String) -> Unit, failed: () -> Unit, message: (Map<String, String>) -> Unit) { umeng.add(Call(token, failed)) }
        override fun stopUmeng(context: Context) = Unit
    }
}
