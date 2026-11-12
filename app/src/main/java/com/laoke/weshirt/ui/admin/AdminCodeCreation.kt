package com.laoke.weshirt.ui.admin

import java.util.UUID

/** Retry state only; never retains the one-time credential returned by the server. */
internal class AdminCodeCreation {
    private var payload = ""
    private var key = ""
    fun keyFor(serializedBody: String): String {
        if (payload != serializedBody || key.isBlank()) {
            payload = serializedBody
            key = UUID.randomUUID().toString()
        }
        return key
    }
    fun clear() { payload = ""; key = "" }
}
