package com.beauty.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.beauty.app.ui.AppLinkInbox

/** Transient external entry point. The main task never receives a token-bearing Intent. */
class AppLinkActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val raw = intent.dataString.takeIf { intent.action == Intent.ACTION_VIEW }
        intent.data = null
        intent.replaceExtras(null as Bundle?)
        if (raw != null) AppLinkInbox.receive(raw)
        startActivity(Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        })
        finish()
    }
}
