package com.laoke.weshirt.platform.login

import com.laoke.weshirt.data.ApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/** Only pre-authorization challenge creation is repeatable. Never wrap exchange or token rotation. */
internal suspend fun <T> recoverNativeLoginChallenge(
    origin: String,
    currentOrigin: () -> String,
    pause: suspend () -> Unit = { delay(800) },
    createFreshChallenge: suspend () -> T,
): T {
    fun current() {
        if (currentOrigin() != origin) throw CancellationException("Login service changed during recovery")
    }
    currentCoroutineContext().ensureActive(); current()
    return try { createFreshChallenge() }
    catch (problem: ApiException) {
        // An explicit transient 503 occurs before DingTalk authorization. The
        // replacement generates a new state/verifier; the failed challenge is
        // abandoned. Configuration, permission and quota errors need user action.
        if (problem.status != 503 || problem.code !in setOf("mobile_unavailable", "mobile_busy", "mobile_store_unavailable")) throw problem
        pause()
        currentCoroutineContext().ensureActive(); current()
        createFreshChallenge()
    }
}

internal fun nativeLoginExchangeFailure(problem: ApiException): Exception =
    if (problem.status == 503) IllegalStateException(
        problem.message.orEmpty() + "\n请重新点击钉钉登录，完成一次新的授权；本次授权码不会重复提交。", problem,
    ) else problem
