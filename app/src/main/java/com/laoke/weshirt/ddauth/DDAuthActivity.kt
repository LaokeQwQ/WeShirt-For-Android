package com.laoke.weshirt.ddauth

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.laoke.weshirt.MainActivity
import com.laoke.weshirt.platform.login.DingTalkLoginBridge

/** The component name is part of DingTalk's native SDK callback contract. */
class DDAuthActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); handle(intent) }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handle(intent) }

    private fun handle(callback: Intent) {
        try {
            if (DingTalkLoginBridge.acceptCallback(this, callback)) {
                // An explicit in-app component also works when the launcher task
                // was recreated; authorization material never travels in this intent.
                startActivity(Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(DingTalkLoginBridge.REPLY_EXTRA, true))
            }
        } finally { finish() }
    }
}
