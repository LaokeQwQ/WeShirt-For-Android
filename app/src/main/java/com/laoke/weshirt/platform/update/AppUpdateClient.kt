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
        if (apkUrl(release.apk.toString()) != release.apk || !SHA256.matches(release.sha256) ||
            release.size !in 1..MAX_APK_BYTES || release.versionCode !in 1..MAX_VERSION_CODE) invalidManifest()
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw AppUpdateException("STORAGE_UNAVAILABLE", "更新目录暂不可用")
        }
        val target = File(directory, "${release.versionCode}-${release.sha256}.apk")
        if (target.isFile && runCatching { target.source().buffer().use { transfer(it, release, null, progress) } }.isSuccess) {
            return target
        }
        target.delete()
        val temporary = File.createTempFile("download-", ".part", directory)
        try {
            val request = Request.Builder().url(release.apk).header("Accept-Encoding", "identity").build()
            client.newCall(request).execute().use { reply ->
                if (!reply.isSuccessful) throw AppUpdateException("APK_HTTP_${reply.code}", "暂时无法下载安装包")
                val body = reply.body ?: invalidSize()
                val length = body.contentLength()
                if (length >= 0L && length != release.size) invalidSize()
                body.source().use { source ->
                    temporary.outputStream().buffered().use { destination ->
                        transfer(source, release, destination, progress)
                    }
                }
            }
            if (!temporary.renameTo(target)) throw AppUpdateException("STORAGE_UNAVAILABLE", "无法保存已校验的安装包")
            directory.listFiles()?.filter { it != target && it.name.endsWith(".apk") }?.forEach { it.delete() }
            return target
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
        progress: (Float) -> Unit,
    ) {
        val digest = MessageDigest.getInstance("SHA-256")
        val chunk = ByteArray(32 * 1024)
        var bytes = 0L
        var lastPercent = 0
        while (true) {
            val read = source.read(chunk, 0, chunk.size)
            if (read == -1) break
            bytes += read.toLong()
            if (bytes > release.size) invalidSize()
            digest.update(chunk, 0, read)
            destination?.write(chunk, 0, read)
            val percent = (bytes * 100 / release.size).toInt()
            if (percent > lastPercent && percent < 100) {
                progress(percent / 100f)
                lastPercent = percent
            }
        }
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
