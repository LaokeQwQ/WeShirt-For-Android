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

/** One installation has one primary delivery provider; OEM channels belong to UMeng. */
object PushRouter {
    fun choose(region: PushRegion, available: PushAvailability): PushRoute {
        if (!available.consent) return PushRoute(PushProvider.NONE, "CONSENT_REQUIRED")
        return when (region) {
            PushRegion.DOMESTIC -> if (available.umengConfigured) PushRoute(PushProvider.UMENG, "DOMESTIC_AGGREGATE")
                else PushRoute(PushProvider.NONE, "UMENG_NOT_CONFIGURED")
            PushRegion.GOOGLE -> if (available.firebaseConfigured && available.googleServicesAvailable)
                PushRoute(PushProvider.FCM, "GOOGLE_SERVICES") else PushRoute(PushProvider.NONE, "FCM_UNAVAILABLE")
            PushRegion.AUTO -> when {
                available.googleServicesAvailable && available.firebaseConfigured -> PushRoute(PushProvider.FCM, "GOOGLE_SERVICES")
                available.umengConfigured -> PushRoute(PushProvider.UMENG, "DOMESTIC_AGGREGATE")
                else -> PushRoute(PushProvider.NONE, "NO_CONFIGURED_PROVIDER")
            }
        }
    }
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
) {
    fun valid(): Boolean = eventId.isNotBlank() && eventId.length <= 160 && revision >= 0 &&
        title.isNotBlank() && title.length <= 160 && body.length <= 1500 &&
        (kind != PushEventKind.TASK_FINISHED || serverConfirmed)
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
