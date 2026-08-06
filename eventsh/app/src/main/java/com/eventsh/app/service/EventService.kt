package com.eventsh.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.eventsh.app.engine.Dispatcher
import com.eventsh.app.engine.EventHub
import com.eventsh.app.engine.EventLog
import com.eventsh.app.engine.RootBridge
import com.eventsh.app.engine.Scheduler
import com.eventsh.app.engine.Watchers

class EventService : Service() {

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(
                    "fgs", "EVENTSH service",
                    NotificationManager.IMPORTANCE_LOW
                ).apply { description = "keeps event listener alive" }
            )
        }
        val n = Notification.Builder(this, "fgs")
            .setSmallIcon(android.R.drawable.ic_menu_more)
            .setContentTitle("EVENTSH")
            .setContentText("listening for events")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
        EventHub.register(this)
        EventLog.push("[svc] started (fg)")
        RootBridge.checkAsync()
        Watchers.start(this)
        Scheduler.rescheduleAll(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        EventHub.unregister(this)
        Watchers.stop()
        EventLog.push("[svc] stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
