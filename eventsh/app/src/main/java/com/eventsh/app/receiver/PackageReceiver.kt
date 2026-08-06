package com.eventsh.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.eventsh.app.engine.EventHub

class PackageReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pkg = intent.data?.schemeSpecificPart ?: return
        when (intent.action) {
            Intent.ACTION_PACKAGE_ADDED -> EventHub.dispatch("app_install", mapOf("summary" to pkg, "pkg" to pkg))
            Intent.ACTION_PACKAGE_REMOVED -> EventHub.dispatch("app_remove", mapOf("summary" to pkg, "pkg" to pkg))
            Intent.ACTION_PACKAGE_REPLACED -> EventHub.dispatch("app_update", mapOf("summary" to pkg, "pkg" to pkg))
        }
    }
}
