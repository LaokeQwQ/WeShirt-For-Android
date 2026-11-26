package com.laoke.weshirt.platform.login

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive

internal data class PreparedNativeLogin(val origin: String, val challenge: NativeLoginChallenge)

/** Coalesces a short-lived, unused challenge only. Authorization codes are never cached. */
internal class NativeLoginPreparation(
    private val scope: CoroutineScope,
    private val load: suspend (String) -> NativeLoginChallenge,
    private val valid: suspend (PreparedNativeLogin) -> Boolean,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private data class Entry(val origin: String, val result: Deferred<Result<PreparedNativeLogin>>)
    private var entry: Entry? = null
    private var revision = 0L
    private var retryAfter = 0L
    private var retryOrigin: String? = null

    /** Invoked by a foreground login screen; repeated state updates do not issue new requests. */
    suspend fun warm(origin: String) {
        if (origin.isBlank() || synchronized(this) { retryOrigin == origin && now() < retryAfter }) return
        try { obtain(origin) }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { synchronized(this) { retryOrigin = origin; retryAfter = now() + 30_000 } }
    }

    suspend fun obtain(origin: String): PreparedNativeLogin {
        require(origin.isNotBlank())
        repeat(2) {
            val selected = synchronized(this) {
                entry?.takeIf { it.origin == origin } ?: run {
                    entry?.result?.cancel()
                    val captured = ++revision
                    Entry(origin, scope.async(start = CoroutineStart.LAZY) {
                        try {
                            val prepared = PreparedNativeLogin(origin, load(origin))
                            ensureActive()
                            if (synchronized(this@NativeLoginPreparation) { captured != revision })
                                throw CancellationException("Login preparation changed")
                            Result.success(prepared)
                        } catch (cancel: CancellationException) { throw cancel }
                        // A failed background preparation must not fail its owner
                        // scope; the awaiting warm/tap caller owns error handling.
                        catch (problem: Exception) { Result.failure(problem) }
                    }).also { entry = it }
                }
            }
            val prepared = try { selected.result.await().getOrThrow() }
            catch (problem: Exception) {
                // Closing a screen cancels its waiter, not the shared request
                // that an immediate login tap or recreated Activity can reuse.
                if (problem !is CancellationException || selected.result.isCancelled)
                    synchronized(this) { if (entry === selected) entry = null }
                throw problem
            }
            if (prepared.challenge.expiresAtMillis - now() > 15_000 && valid(prepared)) return prepared
            synchronized(this) { if (entry === selected) { entry = null; revision++ } }
            // A used/replaced state needs a fresh challenge; an endpoint/account
            // change is cancelled by the caller's validity check before loading.
        }
        throw IllegalStateException(DingTalkLoginBridge.failureMessage("LOGIN_CHALLENGE_EXPIRED"))
    }

    @Synchronized fun discard() {
        revision++; entry?.result?.cancel(); entry = null; retryOrigin = null; retryAfter = 0L
    }
}
