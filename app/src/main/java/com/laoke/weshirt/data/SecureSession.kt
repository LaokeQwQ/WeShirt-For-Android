package com.laoke.weshirt.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Tokens are encrypted using a non-exportable Android Keystore key. Backups are disabled. */
interface SessionStore {
    val installationId: String
    fun read(): JSONObject?
    fun save(value: JSONObject)
    fun clear()
    /** Implementations backed by shared storage must override with their shared lock. */
    fun compareAndSet(expected: JSONObject?, next: JSONObject?): Boolean = synchronized(this) {
        if (read()?.toString() != expected?.toString()) return@synchronized false
        if (next == null) clear() else save(next)
        true
    }
    /** A profile-only cache write must not discard the unique result of token rotation. */
    fun compareAndSetCredentials(expected: JSONObject?, next: JSONObject?): Boolean = synchronized(this) {
        val current = read()
        if (!sameSessionCredentials(expected, current)) return@synchronized false
        if (next == null) clear() else save(withLatestSessionProfile(next, current))
        true
    }
}

internal fun sameSessionCredentials(expected: JSONObject?, current: JSONObject?): Boolean {
    if (expected == null || current == null) return expected == null && current == null
    return listOf("origin", "accessToken", "refreshToken", "expiresAt").all { expected.str(it) == current.str(it) } &&
        expected.optJSONObject("user")?.str("id").orEmpty() == current.optJSONObject("user")?.str("id").orEmpty()
}

internal fun withLatestSessionProfile(next: JSONObject, current: JSONObject?): JSONObject {
    val merged = JSONObject(next.toString())
    val latest = cachedSessionProfile(current, next.str("origin"))
    if (latest != null && latest.str("id") == next.optJSONObject("user")?.str("id")) merged.put("profile", latest)
    return merged
}
class SecureSession(context: Context) : SessionStore {
    companion object { private val processGuard = Any() }
    private val prefs = context.getSharedPreferences("native_session", Context.MODE_PRIVATE)
    private val alias = "weshirt.session.v1"
    override val installationId: String = synchronized(processGuard) {
        prefs.getString("installation", null) ?: UUID.randomUUID().toString().also { check(prefs.edit().putString("installation", it).commit()) }
    }
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    override fun read(): JSONObject? = synchronized(processGuard) { readLocked() }
    private fun readLocked(): JSONObject? = runCatching {
        val raw = prefs.getString("sealed", null) ?: return null
        val pieces = raw.split(":")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(pieces[0], Base64.NO_WRAP)))
        JSONObject(String(cipher.doFinal(Base64.decode(pieces[1], Base64.NO_WRAP)), Charsets.UTF_8))
    }.getOrNull()
    override fun save(value: JSONObject) = synchronized(processGuard) { saveLocked(value) }
    private fun saveLocked(value: JSONObject) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(cipher.doFinal(value.toString().toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        check(prefs.edit().putString("sealed", sealed).commit())
    }
    override fun clear() = synchronized(processGuard) { check(prefs.edit().remove("sealed").commit()) }

    /** Protect background clients against another instance logging out, signing in,
     * rotating credentials or caching a newer profile during an outstanding call. */
    override fun compareAndSet(expected: JSONObject?, next: JSONObject?): Boolean = synchronized(processGuard) {
        if (readLocked()?.toString() != expected?.toString()) return@synchronized false
        if (next == null) check(prefs.edit().remove("sealed").commit()) else saveLocked(next)
        true
    }

    override fun compareAndSetCredentials(expected: JSONObject?, next: JSONObject?): Boolean = synchronized(processGuard) {
        val current = readLocked()
        if (!sameSessionCredentials(expected, current)) return@synchronized false
        if (next == null) check(prefs.edit().remove("sealed").commit()) else saveLocked(withLatestSessionProfile(next, current))
        true
    }
}
