package com.eventsh.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.eventsh.app.engine.Dispatcher
import com.eventsh.app.engine.EventHub
import com.eventsh.app.engine.EventLog
import com.eventsh.app.engine.Privilege
import com.eventsh.app.engine.RootBridge
import com.eventsh.app.engine.Scheduler
import com.eventsh.app.engine.ShizukuClient
import com.eventsh.app.engine.Tts
import com.eventsh.app.engine.Watchers

class EventService : Service() {

    /** Whether this service instance holds the location FGS type. */
    companion object {
        @Volatile var locationTypeActive = false
    }

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
        // Only claim the location FGS type when the runtime permission exists -
        // Android 14+ throws SecurityException otherwise. The manifest lists
        // specialUse|location, so this is always a valid subset.
        locationTypeActive = hasLocationPermission()
        if (Build.VERSION.SDK_INT >= 34) {
            val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                if (locationTypeActive) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
            startForeground(1, n, type)
        } else {
            startForeground(1, n)
        }
        Privilege.ensureChannel(this)
        ShizukuClient.init(this)
        EventHub.register(this)
        EventLog.push("[svc] started (fg)")
        RootBridge.checkAsync()
        Watchers.start(this)
        Scheduler.rescheduleAll(this)
    }

    private fun hasLocationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < 23) return true
        return checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        EventHub.unregister(this)
        Watchers.stop()
        Tts.shutdown()
        EventLog.push("[svc] stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
