package com.laoke.weshirt.ui.admin

import com.laoke.weshirt.data.ApiClient
import com.laoke.weshirt.data.SessionStore
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONObject
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class AdminControllerTest {
    private class MemorySession : SessionStore {
        override val installationId = "test-admin"
        private var value: String? = null
        override fun read() = value?.let(::JSONObject)
        override fun save(value: JSONObject) { this.value = value.toString() }
        override fun clear() { value = null }
    }
    private val temporary = Files.createTempDirectory("weshirt-admin-controller").toFile()
    private lateinit var server: MockWebServer
    private lateinit var http: OkHttpClient
    private lateinit var api: ApiClient
    private val release = CountDownLatch(1)
    private val entered = CountDownLatch(1)
    @Before fun prepare() {
        val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("127.0.0.1").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server = MockWebServer().apply { useHttps(serverTls.sslSocketFactory(), false); start(InetAddress.getByName("127.0.0.1"), 0) }
        http = OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
            .protocols(listOf(Protocol.HTTP_1_1)).retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
            .connectTimeout(3, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).build()
        api = ApiClient(MemorySession(), temporary, http).apply { baseUrl = server.url("/").newBuilder().host("127.0.0.1").build().toString().trimEnd('/') }
        api.saveSession(JSONObject().put("accessToken", "fixture-admin-access").put("refreshToken", "fixture-admin-refresh").put("expiresIn", 900))
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                entered.countDown()
                if (!release.await(4, TimeUnit.SECONDS)) return MockResponse().setResponseCode(504)
                return MockResponse().setHeader("Content-Type", "application/json").setBody("{\"sensitive\":\"old-admin-document\"}")
            }
        }
    }
    @After fun finish() {
        release.countDown(); server.shutdown()
        http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown()
        temporary.deleteRecursively()
    }
    private suspend fun waitFor(condition: () -> Boolean) = withTimeout(4_000) { while (!condition()) delay(10) }

    @Test fun aClosedPanelCannotPublishALateAdminRead() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val controller = AdminController(api, scope) { true }
        try {
            controller.load("/api/admin/site")
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            controller.close()
            release.countDown()
            delay(150)
            assertEquals(0, controller.document.length())
            assertNull(controller.error)
            assertEquals(1, server.requestCount)
        } finally { controller.close(); scope.cancel() }
    }

    @Test fun repeatedMutationWhilePendingIsSentOnce() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val controller = AdminController(api, scope) { true }
        try {
            controller.act("/api/admin/site", "PATCH", JSONObject().put("name", "first"), refresh = null)
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            controller.act("/api/admin/site", "PATCH", JSONObject().put("name", "second"), refresh = null)
            assertTrue(controller.saving)
            release.countDown(); waitFor { !controller.saving }
            assertEquals(1, server.requestCount)
            assertEquals("first", JSONObject(server.takeRequest(1, TimeUnit.SECONDS)!!.body.readUtf8()).getString("name"))
        } finally { controller.close(); scope.cancel() }
    }

    @Test fun closingAnInflightMutationPreventsItsFollowupRefreshAndSuccessCallback() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val controller = AdminController(api, scope) { true }
        val success = AtomicBoolean(false)
        val finished = AtomicBoolean(false)
        try {
            controller.act("/api/admin/site", "PATCH", JSONObject().put("name", "updated"), refresh = "/api/admin/site", finished = { finished.set(true) }, success = { success.set(true) })
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            controller.close(); release.countDown()
            waitFor { finished.get() }
            assertFalse(success.get())
            assertEquals(0, controller.document.length())
            assertEquals(1, server.requestCount)
        } finally { controller.close(); scope.cancel() }
    }

    @Test fun nativeAiConfigurationReadsWithTheExistingDingTalkSessionAndSendsAllThreeProtocols() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = if (request.getHeader("Authorization") == "Bearer fixture-admin-access")
                MockResponse().setHeader("Content-Type","application/json").setBody("{\"enabled\":false,\"keyConfigured\":true}")
            else MockResponse().setResponseCode(401).setBody("{\"error\":{\"code\":\"unauthorized\"}}")
        }
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val controller=AdminController(api,scope){true}
        try {
            controller.load("/api/admin/assistant");waitFor{!controller.loading}
            assertNull(controller.error);assertTrue(controller.document.optBoolean("keyConfigured"))
            assertEquals("/api/admin/assistant",server.takeRequest(1,TimeUnit.SECONDS)!!.path)
            for(protocol in listOf("responses","chat-completions","anthropic")) {
                val body=assistantConfigurationPayload(JSONObject().put("enabled",true).put("protocol",protocol).put("endpoint","https://provider.vendor.net/v1").put("model"," fixture-model ").put("dailyLimit",1000).put("minuteLimit",30).put("reasoningMode","on").put("reasoningEffort","high").put("thinkingMode","adaptive").put("promptProfiles",JSONArray().put(JSONObject().put("id","default").put("name","平台默认").put("text","请简洁回答"))).put("activePromptId","default"),replacementKey="fixture-ai-key")
                controller.act("/api/admin/assistant","PATCH",body,refresh=null);waitFor{!controller.saving}
                assertNull(controller.error)
                val request=server.takeRequest(1,TimeUnit.SECONDS)!!
                assertEquals("Bearer fixture-admin-access",request.getHeader("Authorization"));assertEquals("PATCH",request.method)
                val actual=JSONObject(request.body.readUtf8())
                assertEquals(protocol,actual.getString("protocol"));assertEquals("fixture-model",actual.getString("model"));assertEquals(30,actual.getInt("minuteLimit"));assertEquals("high",actual.getString("reasoningEffort"));assertEquals("fixture-ai-key",actual.getString("apiKey"));assertEquals("default",actual.getString("activePromptId"))
            }
            assertEquals(0,controller.document.optString("apiKey").length)
        } finally {controller.close();scope.cancel()}
    }

    @Test fun temporaryCodeRetryRetainsExplicitKeyAfter409AndNeverPublishesASecondFullCredential() = runBlocking {
        var attempts=0
        server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse {attempts++;return MockResponse().setResponseCode(if(attempts==1)503 else 409).setBody("{\"error\":{\"code\":\"already_created\",\"message\":\"请刷新确认\",\"eventId\":\"evt_fixture\"}}")}}
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val controller=AdminController(api,scope){true};val creation=AdminCodeCreation();val body=JSONObject().put("name","值班").put("ttlHours",12)
        var exposed=""
        try {
            repeat(3){controller.act("/api/admin/access-codes",body=body,key=creation.keyFor(body.toString()),refresh=null,success={exposed=it.optString("code")});waitFor{!controller.saving}}
            val requests=(0 until 3).map{server.takeRequest(1,TimeUnit.SECONDS)!!}
            assertEquals(1,requests.map{it.getHeader("Idempotency-Key")}.toSet().size)
            assertEquals("",exposed);assertTrue(controller.error!!.contains("evt_fixture"))
            val original=creation.keyFor(body.toString());assertNotEquals(original,creation.keyFor(JSONObject(body.toString()).put("name","另一个值班").toString()))
            creation.clear();assertNotEquals(original,creation.keyFor(body.toString()))
        }finally{controller.close();scope.cancel()}
    }

    @Test fun invalidNativeAssistantSettingsNeverReachTheNetwork() {
        val invalid=JSONObject().put("protocol","responses").put("endpoint","https://user:password@provider.vendor.net/v1").put("model","x").put("dailyLimit",1000).put("minuteLimit",31)
        assertThrows(IllegalArgumentException::class.java){assistantConfigurationPayload(invalid)}
        assertEquals(0,server.requestCount)
    }

    @Test fun personalAssistantPromptChangesUseAccountAuthorizationAndNeverSendAUserOrBuildingOverride() = runBlocking {
        server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest)=MockResponse().setHeader("Content-Type","application/json").setBody("{\"customized\":true,\"activePromptId\":\"personal\"}")}
        val body=JSONObject().put("activePromptId","personal").put("promptProfiles",JSONArray().put(JSONObject().put("id","personal").put("name","我的提示词").put("text","请简洁说明空闲机器")))
        val response=api.call("/api/assistant/preferences","PATCH",body)
        assertEquals("personal",response.optString("activePromptId"))
        val request=server.takeRequest(1,TimeUnit.SECONDS)!!
        assertEquals("Bearer fixture-admin-access",request.getHeader("Authorization"));assertNull(request.getHeader("X-Admin-Token"))
        assertEquals("/api/assistant/preferences",request.path);assertEquals("PATCH",request.method)
        val sent=JSONObject(request.body.readUtf8());assertEquals("personal",sent.optString("activePromptId"));assertFalse(sent.has("userId"));assertFalse(sent.has("dormBuilding"));assertFalse(sent.has("apiKey"))
    }
}
