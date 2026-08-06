package com.eventsh.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.eventsh.app.engine.EventHub

class TimeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_TIME_CHANGED -> EventHub.dispatch("time_set", mapOf("summary" to "time set"))
            Intent.ACTION_TIMEZONE_CHANGED -> EventHub.dispatch("tz_change", mapOf("summary" to "tz changed"))
        }
    }
}
