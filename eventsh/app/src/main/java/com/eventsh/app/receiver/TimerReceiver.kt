package com.eventsh.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.eventsh.app.engine.Scheduler

class TimerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Scheduler.ACTION_FIRE) return
        val id = intent.getStringExtra(Scheduler.EXTRA_ID) ?: return
        Scheduler.onFire(context, id)
    }
}
