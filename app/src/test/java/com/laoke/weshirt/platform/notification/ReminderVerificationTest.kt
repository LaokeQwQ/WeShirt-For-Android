package com.laoke.weshirt.platform.notification

import com.laoke.weshirt.data.ApiClient
import com.laoke.weshirt.data.SessionStore
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.time.Instant

class ReminderVerificationTest {
    private val now = 1_800_000_000_000L
    private lateinit var server:MockWebServer
    private lateinit var api:ApiClient
    private lateinit var cache:java.io.File
    private var task = JSONObject()
    private var allow = true
    private var notify = true
    private var queue = false
    private var machineState = "occupied"
    private var failTasks = false
    private val paths = mutableListOf<String>()

    @Before fun prepare() {
        val certificate=HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").addSubjectAlternativeName("127.0.0.1").build()
        val tls=HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val trust=HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server=MockWebServer().apply { useHttps(tls.sslSocketFactory(),false) }
        server.dispatcher=object:Dispatcher(){
            override fun dispatch(request:RecordedRequest):MockResponse {
                paths += request.path.orEmpty()
                assertEquals("no-cache, no-store",request.getHeader("Cache-Control"))
                if(request.path?.startsWith("/api/machines")!=true) assertEquals("Bearer fixture-access",request.getHeader("Authorization"))
                val body=when(request.path?.substringBefore('?')) {
                    "/api/auth/me" -> JSONObject().put("user",JSONObject().put("id","owner").put("notifyOn",notify).put("dormBuilding","guiyuan"))
                    "/api/user/permissions" -> JSONObject().put("permissions",JSONObject().put(if(queue)"queue" else "tasks",JSONObject().put("view",allow)))
                    "/api/tasks" -> {
                        if(failTasks)return MockResponse().setResponseCode(503).setBody("{\"error\":{\"code\":\"unavailable\"}}")
                        JSONObject().put("serverNow",iso(now+120_000)).put("tasks",org.json.JSONArray().put(task))
                    }
                    "/api/queue" -> JSONObject().put("queue",org.json.JSONArray().put(JSONObject().put("id","q").put("mine",true).put("position",1).put("machineId","m")))
                    "/api/machines" -> {
                        val machine=JSONObject().put("id","m").put("state",machineState).put("finishAt",iso(now+360_000))
                        val floor=JSONObject().put("machines",org.json.JSONArray().put(machine))
                        JSONObject().put("freshness",JSONObject().put("serverNow",iso(now)).put("stale",false))
                            .put("board",JSONObject().put("floors",org.json.JSONArray().put(floor)))
                    }
                    else -> return MockResponse().setResponseCode(404)
                }
                return MockResponse().setHeader("Content-Type","application/json").setBody(body.toString())
            }
        }
        server.start()
        val origin=server.url("/").toString().trimEnd('/')
        val session=object:SessionStore {
            override val installationId="fixture_installation"
            private var saved=JSONObject().put("origin",origin).put("accessToken","fixture-access").put("expiresAt",System.currentTimeMillis()+900_000)
            override fun read()=JSONObject(saved.toString())
            override fun save(value:JSONObject){saved=value}
            override fun clear(){saved=JSONObject()}
        }
        cache=Files.createTempDirectory("reminder-verification").toFile()
        api=ApiClient(session,cache,OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(),trust.trustManager).followRedirects(false).build()).apply {baseUrl=origin}
        task=JSONObject().put("id","t").put("machineName","测试机器").put("endAt",iso(now+8*60_000)).put("timingSource","upstream").put("completionConfirmed",false)
    }
    @After fun cleanup(){server.shutdown();cache.deleteRecursively()}

    @Test fun platformDelayReschedulesOldLocalTimerAndUsesServerClock()=runBlocking {
        val result=fetchVerifiedReminder(api,"t"){now}
        assertEquals(now+6*60_000,result.finish)
        assertEquals(ReminderAction.RESCHEDULE,reminderAction(result,now+3*60_000,"soon",3,now))
        assertEquals(listOf("/api/auth/me","/api/user/permissions","/api/tasks"),paths)
    }

    @Test fun elapsedTimerWhileStillRunningWaitsAndFreshCompletionCanFinishBeforeRegistrationTimer()=runBlocking {
        task.put("endAt",iso(now+120_000)) // Server clock is two minutes ahead: local deadline is now.
        val running=fetchVerifiedReminder(api,"t"){now}
        assertEquals(ReminderAction.WAIT,reminderAction(running,now,"estimated",3,now))
        task.put("completionConfirmed",true)
        val ended=fetchVerifiedReminder(api,"t"){now}
        assertEquals(ReminderAction.FINISHED,reminderAction(ended,now+30*60_000,"estimated",3,now))
    }

    @Test fun notificationOptOutPermissionRevocationTakenTaskAndRemovedTaskFailClosed()=runBlocking {
        notify=false;assertFalse(fetchVerifiedReminder(api,"t"){now}.active);assertEquals(1,paths.size)
        notify=true;allow=false;paths.clear();assertFalse(fetchVerifiedReminder(api,"t"){now}.active);assertFalse(paths.contains("/api/tasks"))
        allow=true;task.put("takenAt",iso(now));assertFalse(fetchVerifiedReminder(api,"t"){now}.active)
        task.remove("takenAt");assertFalse(fetchVerifiedReminder(api,"missing"){now}.active)
    }

    @Test fun staleSuccessfulResponseNeverClaimsCompletionAndNetworkFallbackIsExplicit()=runBlocking {
        task.put("timeStale",true).put("completionConfirmed",true)
        assertEquals(ReminderAction.WAIT,reminderAction(fetchVerifiedReminder(api,"t"){now},now,"estimated",0,now))
        val failed=VerifiedReminder(now,"测试机器",fallback=true)
        assertEquals(ReminderAction.ESTIMATE,reminderAction(failed,now,"estimated",0,now))
        failTasks=true
        val result=runCatching {fetchVerifiedReminder(api,"t"){now}}
        assertTrue(result.exceptionOrNull() is com.laoke.weshirt.data.ApiException)
    }

    @Test fun queueUsesLatestMachineTimeAndNeverTreatsItsZeroCountdownAsIdle()=runBlocking {
        queue=true
        val running=fetchVerifiedReminder(api,"queue:q"){now}
        assertEquals(now+6*60_000,running.finish)
        assertEquals(ReminderAction.RESCHEDULE,reminderAction(running,now+3*60_000,"soon",3,now))
        assertFalse(running.confirmed)
        machineState="idle"
        assertEquals(ReminderAction.FINISHED,reminderAction(fetchVerifiedReminder(api,"queue:q"){now},now,"estimated",3,now))
    }

    private fun iso(value:Long)=Instant.ofEpochMilli(value).toString()
}
