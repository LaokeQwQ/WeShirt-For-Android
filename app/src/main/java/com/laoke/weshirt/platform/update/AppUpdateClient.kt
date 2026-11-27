package com.laoke.weshirt.platform.update

import okhttp3.CacheControl
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.BufferedSource
import okio.buffer
import okio.source
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Cancels a blocked HTTP read as well as disk/hash work; it does not leave a partial APK. */
internal class UpdateDownloadCancellation {
    private val cancelled = AtomicBoolean(false)
    private val activeCall = AtomicReference<okhttp3.Call?>()
    fun cancel() { cancelled.set(true); activeCall.get()?.cancel() }
    fun check() { if (cancelled.get()) throw CancellationException("Update download cancelled") }
    fun attach(call: okhttp3.Call) { activeCall.set(call); if (cancelled.get()) call.cancel() }
    fun detach(call: okhttp3.Call) { activeCall.compareAndSet(call, null) }
}

internal data class AppRelease(
    val versionCode: Long,
    val versionName: String,
    val apk: HttpUrl,
    val size: Long,
    val sha256: String,
)

internal class AppUpdateException(val code: String, message: String) : IOException(message)

internal class AppUpdateClient(
    endpoint: String,
    transport: OkHttpClient? = null,
) {
    private val origin = endpoint.toHttpUrlOrNull()?.takeIf {
        it.isHttps && it.encodedPath == "/" && it.encodedQuery == null && it.encodedFragment == null &&
            it.encodedUsername.isEmpty() && it.encodedPassword.isEmpty()
    }
    val configured: Boolean get() = origin != null
    private val client by lazy {
        val builder = transport?.newBuilder() ?: OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(2, TimeUnit.MINUTES)
        builder.cache(null).retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()
    }

    fun check(installedVersion: Long): AppRelease? {
        val url = origin?.resolve("${ROOT}latest.json") ?: invalidManifest()
        client.newCall(Request.Builder().url(url).cacheControl(CacheControl.FORCE_NETWORK).build()).execute().use { reply ->
            if (!reply.isSuccessful) throw AppUpdateException("MANIFEST_HTTP_${reply.code}", "暂时无法获取版本清单")
            val source = reply.body?.source() ?: invalidManifest()
            if (source.request(MAX_MANIFEST_BYTES + 1)) invalidManifest()
            val data = try {
                JSONObject(source.readUtf8())
            } catch (_: org.json.JSONException) {
                invalidManifest()
            }
            val release = parseRelease(data)
            return release.takeIf { it.versionCode > installedVersion }
        }
    }

    fun download(release: AppRelease, directory: File, progress: (Float) -> Unit): File {
        var previous = 0
        return downloadCancelable(release, directory, UpdateDownloadCancellation()) { bytes ->
            val percent = (bytes * 100 / release.size).toInt()
            if (percent > previous && percent < 100) { progress(percent / 100f); previous = percent }
        }
    }

    fun downloadCancelable(release: AppRelease, directory: File, cancellation: UpdateDownloadCancellation,
                           transferred: (Long) -> Unit): File {
        cancellation.check()
        if (apkUrl(release.apk.toString()) != release.apk || !SHA256.matches(release.sha256) ||
            release.size !in 1..MAX_APK_BYTES || release.versionCode !in 1..MAX_VERSION_CODE) invalidManifest()
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw AppUpdateException("STORAGE_UNAVAILABLE", "更新目录暂不可用")
        }
        val target = File(directory, "${release.versionCode}-${release.sha256}.apk")
        if (target.isFile) {
            try {
                target.source().buffer().use { transfer(it, release, null, cancellation, transferred) }
                cancellation.check()
                return target
            } catch (error: CancellationException) { throw error }
            catch (_: IOException) { /* A corrupt cache is replaced using the same verified manifest. */ }
        }
        cancellation.check()
        target.delete()
        val temporary = File.createTempFile("download-", ".part", directory)
        try {
            val request = Request.Builder().url(release.apk).header("Accept-Encoding", "identity").build()
            val call = client.newCall(request)
            call.timeout().timeout(30, TimeUnit.MINUTES)
            cancellation.attach(call)
            try { call.execute().use { reply ->
                if (!reply.isSuccessful) throw AppUpdateException("APK_HTTP_${reply.code}", "暂时无法下载安装包")
                val body = reply.body ?: invalidSize()
                val length = body.contentLength()
                if (length >= 0L && length != release.size) invalidSize()
                body.source().use { source ->
                    temporary.outputStream().buffered().use { destination ->
                        transfer(source, release, destination, cancellation, transferred)
                    }
                }
            } } catch (error: IOException) { cancellation.check(); throw error }
            finally { cancellation.detach(call) }
            cancellation.check()
            if (!temporary.renameTo(target)) throw AppUpdateException("STORAGE_UNAVAILABLE", "无法保存已校验的安装包")
            cancellation.check()
            directory.listFiles()?.filter { it != target && it.name.endsWith(".apk") }?.forEach { it.delete() }
            return target
        } catch (error: CancellationException) {
            target.delete()
            throw error
        } finally {
            temporary.delete()
        }
    }

    private fun parseRelease(data: JSONObject): AppRelease {
        val version = integer(data.opt("versionCode"))?.takeIf { it in 1..MAX_VERSION_CODE } ?: invalidManifest()
        val name = (data.opt("versionName") as? String)?.takeIf(VERSION_NAME::matches) ?: invalidManifest()
        val apk = apkUrl(data.opt("apk") as? String ?: "") ?: invalidManifest()
        val size = integer(data.opt("size"))?.takeIf { it in 1..MAX_APK_BYTES } ?: invalidManifest()
        val hash = (data.opt("sha256") as? String)?.takeIf(SHA256::matches)?.lowercase() ?: invalidManifest()
        return AppRelease(version, name, apk, size, hash)
    }

    private fun apkUrl(value: String): HttpUrl? {
        val base = origin ?: return null
        // Check the raw name too, before URL normalization removes traversal segments.
        val rawPath = if (value.startsWith(ROOT)) value else {
            if (!value.startsWith("https://")) return null
            value.substringAfter("https://").substringAfter('/', "").let { "/$it" }
        }
        if (!rawPath.startsWith(ROOT) || !APK_NAME.matches(rawPath.removePrefix(ROOT))) return null
        return base.resolve(value)?.takeIf {
            it.isHttps && it.host == base.host && it.port == base.port &&
                it.encodedUsername.isEmpty() && it.encodedPassword.isEmpty() && it.encodedPath == rawPath &&
                it.encodedQuery == null && it.encodedFragment == null
        }
    }

    private fun transfer(
        source: BufferedSource,
        release: AppRelease,
        destination: java.io.OutputStream?,
        cancellation: UpdateDownloadCancellation,
        transferred: (Long) -> Unit,
    ) {
        val digest = MessageDigest.getInstance("SHA-256")
        val chunk = ByteArray(32 * 1024)
        var bytes = 0L
        while (true) {
            cancellation.check()
            val read = source.read(chunk, 0, chunk.size)
            if (read == -1) break
            bytes += read.toLong()
            if (bytes > release.size) invalidSize()
            digest.update(chunk, 0, read)
            destination?.write(chunk, 0, read)
            transferred(bytes)
        }
        cancellation.check()
        if (bytes != release.size) invalidSize()
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        if (hash != release.sha256) throw AppUpdateException("APK_SHA256_MISMATCH", "安装包校验失败，请重试")
    }

    private fun integer(value: Any?): Long? = when (value) {
        is Int -> value.toLong()
        is Long -> value
        else -> null
    }

    private companion object {
        const val ROOT = "/updates/android/"
        const val MAX_MANIFEST_BYTES = 16_384L
        const val MAX_APK_BYTES = 250L * 1024 * 1024
        const val MAX_VERSION_CODE = 2_100_000_000L
        val VERSION_NAME = Regex("[A-Za-z0-9][A-Za-z0-9.+_-]{0,63}")
        val APK_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}\\.apk")
        val SHA256 = Regex("[0-9a-fA-F]{64}")
        fun invalidManifest(): Nothing = throw AppUpdateException("MANIFEST_INVALID", "版本清单无效")
        fun invalidSize(): Nothing = throw AppUpdateException("APK_SIZE_MISMATCH", "安装包大小不符，请重试")
    }
}
