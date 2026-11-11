package com.laoke.weshirt.platform.push

enum class PushProvider { NONE, FCM, UMENG }
enum class PushRegion { AUTO, DOMESTIC, GOOGLE }

data class PushAvailability(
    val consent: Boolean,
    val googleServicesAvailable: Boolean,
    val firebaseConfigured: Boolean,
    val umengConfigured: Boolean,
)

data class PushRoute(val provider: PushProvider, val reason: String)

/** One installation uses either Firebase FCM or the base U-Push channel. */
object PushRouter {
    const val FCM_RETRY_DELAY_MILLIS = 6 * 60 * 60_000L

    /** A clock rollback or corrupt future timestamp must not suppress FCM forever. */
    fun deferFcm(lastFailureAtMillis: Long, nowMillis: Long): Boolean =
        lastFailureAtMillis > 0 && nowMillis >= lastFailureAtMillis &&
            nowMillis - lastFailureAtMillis < FCM_RETRY_DELAY_MILLIS

    fun choose(region: PushRegion, available: PushAvailability, fcmDeferred: Boolean = false): PushRoute {
        if (!available.consent) return PushRoute(PushProvider.NONE, "CONSENT_REQUIRED")
        return when (region) {
            PushRegion.DOMESTIC -> if (available.umengConfigured) PushRoute(PushProvider.UMENG, "UMENG_CHANNEL")
                else PushRoute(PushProvider.NONE, "UMENG_NOT_CONFIGURED")
            PushRegion.GOOGLE -> if (available.firebaseConfigured && available.googleServicesAvailable)
                PushRoute(PushProvider.FCM, "GOOGLE_SERVICES") else PushRoute(PushProvider.NONE, "FCM_UNAVAILABLE")
            PushRegion.AUTO -> when {
                available.googleServicesAvailable && available.firebaseConfigured && !fcmDeferred -> PushRoute(PushProvider.FCM, "GOOGLE_SERVICES")
                available.umengConfigured -> PushRoute(PushProvider.UMENG, if (fcmDeferred) "FCM_UNAVAILABLE_USING_UMENG" else "UMENG_CHANNEL")
                fcmDeferred && available.firebaseConfigured -> PushRoute(PushProvider.NONE, "FCM_RETRY_DEFERRED")
                else -> PushRoute(PushProvider.NONE, "NO_CONFIGURED_PROVIDER")
            }
        }
    }

    fun afterFcmFailure(region: PushRegion, available: PushAvailability): PushRoute =
        if (region == PushRegion.AUTO) choose(region, available, fcmDeferred = true)
        else PushRoute(PushProvider.NONE, if (available.consent) "FCM_UNAVAILABLE" else "CONSENT_REQUIRED")
}

data class PushRegistration(val installationId: String, val provider: PushProvider, val token: String)

interface PushRegistrationSink {
    /** Idempotent upsert, keyed by installationId. The server replaces any previous provider/token. */
    suspend fun register(registration: PushRegistration)
    suspend fun unregister(installationId: String)
}

enum class PushEventKind { QUEUE_THREE_MINUTES, ESTIMATED_END, TASK_FINISHED, CHAT_MENTION, ANNOUNCEMENT, PICKUP_REQUEST }

data class NativePushEvent(
    val eventId: String,
    val revision: Long,
    val kind: PushEventKind,
    val title: String,
    val body: String,
    val taskId: String? = null,
    val serverConfirmed: Boolean = false,
    val finishAtMillis: Long? = null,
) {
    fun valid(): Boolean = eventId.isNotBlank() && eventId.length <= 160 && revision >= 0 &&
        title.isNotBlank() && title.length <= 160 && body.length <= 1500 &&
        (taskId == null || taskId.length <= 160) && (finishAtMillis == null || finishAtMillis > 0) &&
        (kind != PushEventKind.TASK_FINISHED || serverConfirmed)
}

data class TaskNoticeReceipt(val eventId: String, val revision: Long, val finishAtMillis: Long?, val confirmed: Boolean)

/** Local timers and vendor pushes have different event IDs for the same task phase. */
object TaskNoticeCoalescing {
    fun key(event: NativePushEvent): String? {
        val task = event.taskId?.takeIf { it.isNotBlank() } ?: return null
        val phase = when (event.kind) {
            PushEventKind.QUEUE_THREE_MINUTES -> "lead"
            PushEventKind.ESTIMATED_END, PushEventKind.TASK_FINISHED -> "end"
            else -> return null
        }
        return "$task:$phase"
    }

    fun sameFinish(first: Long?, second: Long?): Boolean = first == null || second == null ||
        kotlin.math.abs(first - second) <= 120_000L

    fun shouldDisplay(event: NativePushEvent, previous: TaskNoticeReceipt?): Boolean {
        if (previous == null) return true
        if (previous.confirmed && !event.serverConfirmed) return false
        if (!sameFinish(previous.finishAtMillis, event.finishAtMillis)) return true
        if (event.serverConfirmed && !previous.confirmed) return true
        return event.eventId == previous.eventId && event.revision > previous.revision
    }
}

/** A bounded monotonic ledger: replayed events and older revisions never re-alert the user. */
class EventRevisionLedger(private val maximumEntries: Int = 256) {
    private val revisions = LinkedHashMap<String, Long>()
    @Synchronized fun shouldAccept(eventId: String, revision: Long): Boolean =
        revision >= 0 && (revisions[eventId]?.let { revision > it } ?: true)
    @Synchronized fun record(eventId: String, revision: Long) {
        if (!shouldAccept(eventId, revision)) return
        revisions.remove(eventId)
        revisions[eventId] = revision
        while (revisions.size > maximumEntries) revisions.remove(revisions.keys.first())
    }
    @Synchronized fun entries(): Map<String, Long> = revisions.toMap()
}
