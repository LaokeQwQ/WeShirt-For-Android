package com.laoke.weshirt.data

import android.content.Context
import android.content.SharedPreferences
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
    companion object {
        private val processGuard = Any()
        private var cachedOwner: SharedPreferences? = null
        private var cachedSeal: String? = null
        private var cachedJson: String? = null
    }
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
        if (cachedOwner === prefs && raw == cachedSeal) return cachedJson?.let(::JSONObject)
        val decoded = unseal(raw)
        JSONObject(decoded).also { cacheLocked(raw, decoded) }
    }.getOrNull()
    override fun save(value: JSONObject) = synchronized(processGuard) { saveLocked(value) }
    private fun saveLocked(value: JSONObject) {
        val previous = readLocked()
        val decoded = value.toString()
        val sealed = seal(decoded)
        val edit = prefs.edit().putString("sealed", sealed)
        if (savedLaundrySessionId(previous) != savedLaundrySessionId(value) ||
            previous?.str("origin") != value.str("origin") ||
            cachedSessionProfile(previous, value.str("origin"))?.str("id") != cachedSessionProfile(value, value.str("origin"))?.str("id")) {
            edit.remove(SAVED_LAUNDRY_KEY)
        }
        check(edit.commit())
        cacheLocked(sealed, decoded)
    }
    private fun seal(decoded: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(cipher.doFinal(decoded.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }
    private fun unseal(raw: String): String {
        val pieces = raw.split(":", limit = 2)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(pieces[0], Base64.NO_WRAP)))
        return String(cipher.doFinal(Base64.decode(pieces[1], Base64.NO_WRAP)), Charsets.UTF_8)
    }

    internal fun readLaundry(origin: String, now: Long = System.currentTimeMillis()): SavedLaundry? = synchronized(processGuard) {
        val encrypted = prefs.getString(SAVED_LAUNDRY_KEY, null) ?: return@synchronized null
        runCatching { decodeSavedLaundry(JSONObject(unseal(encrypted)), readLocked(), origin, now) }.getOrNull()
    }

    /** A late result cannot store or erase the snapshot of another login. */
    internal fun saveLaundry(origin: String, owner: String, nativeSessionId: String, value: SavedLaundry?) = synchronized(processGuard) {
        val current = readLocked()
        if (savedLaundrySessionId(current) != nativeSessionId || cachedSessionProfile(current, origin)?.str("id") != owner) return@synchronized
        if (value == null) {
            prefs.edit().remove(SAVED_LAUNDRY_KEY).apply()
        } else {
            val data = encodeSavedLaundry(origin, owner, nativeSessionId, value)
            val previous = prefs.getString(SAVED_LAUNDRY_KEY, null)?.let { runCatching { JSONObject(unseal(it)) }.getOrNull() }
            if (previous != null && previous.optLong("syncedAt") > value.syncedAt) return@synchronized
            if (previous != null && value.syncedAt - previous.optLong("syncedAt") < 300_000L &&
                previous.optJSONArray("tasks")?.toString() == data.getJSONArray("tasks").toString()) return@synchronized
            prefs.edit().putString(SAVED_LAUNDRY_KEY, seal(data.toString())).apply()
        }
    }
    private fun cacheLocked(sealed: String?, decoded: String?) {
        cachedOwner = prefs
        cachedSeal = sealed
        cachedJson = decoded
    }
    private fun clearLocked() {
        cacheLocked(null, null)
        if (prefs.contains("sealed") || prefs.contains(SAVED_LAUNDRY_KEY)) {
            check(prefs.edit().remove("sealed").remove(SAVED_LAUNDRY_KEY).commit())
        }
    }
    override fun clear() = synchronized(processGuard) { clearLocked() }

    /** Protect background clients against another instance logging out, signing in,
     * rotating credentials or caching a newer profile during an outstanding call. */
    override fun compareAndSet(expected: JSONObject?, next: JSONObject?): Boolean = synchronized(processGuard) {
        if (readLocked()?.toString() != expected?.toString()) return@synchronized false
        if (next == null) clearLocked() else saveLocked(next)
        true
    }

    override fun compareAndSetCredentials(expected: JSONObject?, next: JSONObject?): Boolean = synchronized(processGuard) {
        val current = readLocked()
        if (!sameSessionCredentials(expected, current)) return@synchronized false
        if (next == null) clearLocked() else saveLocked(withLatestSessionProfile(next, current))
        true
    }
}
