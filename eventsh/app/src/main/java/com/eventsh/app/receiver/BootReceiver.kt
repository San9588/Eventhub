package com.eventsh.app.receiver

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.eventsh.app.engine.EventHub
import com.eventsh.app.engine.Scheduler
import com.eventsh.app.service.EventService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val a = intent.action
        if (a == Intent.ACTION_BOOT_COMPLETED || a == Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            // re-arm persisted timers (alarms are cleared on reboot)
            Scheduler.rescheduleAll(context)
            EventHub.fireDirect(context, "boot", mapOf("summary" to "boot complete"))
            // Android 15 blocks direct FGS from BOOT_COMPLETED; schedule shortly after.
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getForegroundService(
                context, 1,
                Intent(context, EventService::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            am.setAndAllowWhileIdle(AlarmManager.RTC, System.currentTimeMillis() + 20000L, pi)
        }
    }
}
