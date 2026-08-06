package com.eventsh.app.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.eventsh.app.engine.EventHub

class NotificationBridge : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        EventHub.dispatch("notify_post", mapOf("summary" to "bridge connected"))
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        sbn ?: return
        val pkg = sbn.packageName
        val title = sbn.notification?.extras?.getCharSequence("android.title")?.toString() ?: ""
        EventHub.dispatch("notify_post", mapOf("summary" to "$pkg:$title", "pkg" to pkg, "title" to title))
    }
}
