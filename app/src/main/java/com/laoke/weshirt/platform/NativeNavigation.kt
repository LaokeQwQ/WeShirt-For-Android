package com.laoke.weshirt.platform

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.laoke.weshirt.MainActivity

/** Only fixed, native destinations are accepted; push payloads cannot provide URLs or channel IDs. */
object NativeNavigation {
    const val DESTINATION_EXTRA = "weshirt.destination"
    private const val TASK_EXTRA = "weshirt.taskId"

    fun consumeTab(intent: Intent?): Int? {
        val destination = intent?.getStringExtra(DESTINATION_EXTRA)
        intent?.removeExtra(DESTINATION_EXTRA)
        intent?.removeExtra(TASK_EXTRA)
        return when (destination) {
            "MY_LAUNDRY", "QUEUE_THREE_MINUTES", "ESTIMATED_END", "TASK_FINISHED", "PICKUP_REQUEST" -> 1
            "CHAT_MENTION" -> 2
            "ANNOUNCEMENT" -> 0
            else -> null
        }
    }

    fun pendingIntent(context: Context, destination: String, identity: String): PendingIntent? {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        intent.action = "weshirt.open.${identity.take(180)}"
        intent.putExtra(DESTINATION_EXTRA, destination)
        return PendingIntent.getActivity(context, identity.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}
