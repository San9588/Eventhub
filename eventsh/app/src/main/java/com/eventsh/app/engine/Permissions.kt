package com.eventsh.app.engine

import android.app.Activity
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.provider.Settings

/**
 * Maps rules/events to the Android permissions they need, Tasker-style.
 * A rule that needs an ungranted permission should prompt the user to set
 * it up (special-access settings screen or a runtime permission dialog).
 */
object Permissions {
    enum class Kind { RUNTIME, SPECIAL }

    data class Need(
        val id: String,
        val label: String,
        val detail: String,
        val kind: Kind,
        val permission: String? = null,
        val settingsAction: String? = null
    ) {
        fun granted(ctx: Context): Boolean = when (kind) {
            Kind.RUNTIME -> {
                val p = permission ?: return true
                Build.VERSION.SDK_INT < 23 || ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
            }
            Kind.SPECIAL -> specialGranted(ctx)
        }

        fun open(ctx: Context) {
            when (kind) {
                Kind.RUNTIME -> {
                    val act = ctx as? Activity
                    if (act != null && permission != null) {
                        act.requestPermissions(arrayOf(permission), 20)
                    }
                }
                Kind.SPECIAL -> settingsAction?.let { ctx.startActivity(Intent(it)) }
            }
        }

        private fun specialGranted(ctx: Context): Boolean = when (id) {
            "usage" -> {
                val am = ctx.getSystemService(AppOpsManager::class.java)
                val mode = if (Build.VERSION.SDK_INT >= 29) {
                    am.unsafeCheckOpNoThrow(
                        AppOpsManager.OPSTR_GET_USAGE_STATS,
                        Process.myUid(),
                        ctx.packageName
                    )
                } else {
                    am.checkOpNoThrow(
                        AppOpsManager.OPSTR_GET_USAGE_STATS,
                        Process.myUid(),
                        ctx.packageName
                    )
                }
                mode == AppOpsManager.MODE_ALLOWED
            }
            "notif_listener" -> {
                val cn = ComponentName(ctx, "com.eventsh.app.service.NotificationBridge")
                val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")
                flat != null && flat.split(':').any { it == cn.flattenToString() }
            }
            else -> true
        }
    }

    fun requiredFor(profile: Profile, tasks: List<Task>): List<Need> {
        val out = mutableListOf<Need>()
        val evs = profile.eventActions.toSet()
        if (evs.any { it in setOf("fg_app", "app_open", "app_close") } || profile.appCtx != null) {
            out += Need(
                "usage", "Usage access", "Detect the foreground app",
                Kind.SPECIAL, settingsAction = Settings.ACTION_USAGE_ACCESS_SETTINGS
            )
        }
        if ("notify_post" in evs) {
            out += Need(
                "notif_listener", "Notification access", "Read posted notifications",
                Kind.SPECIAL, settingsAction = Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
            )
        }
        if ("sms" in evs) {
            out += Need(
                "sms", "SMS", "Read incoming SMS",
                Kind.RUNTIME, permission = android.Manifest.permission.RECEIVE_SMS
            )
        }
        if (evs.any { it in setOf("call_in", "call_end") }) {
            out += Need(
                "phone", "Phone state", "Detect incoming calls",
                Kind.RUNTIME, permission = android.Manifest.permission.READ_PHONE_STATE
            )
        }
        if (evs.any { it in setOf("bt_conn", "bt_disconn") }) {
            out += Need(
                "bt", "Bluetooth", "Detect bluetooth connections",
                Kind.RUNTIME, permission = android.Manifest.permission.BLUETOOTH_CONNECT
            )
        }
        val linked = tasks.find { it.id == profile.taskId }
        if (linked?.actions?.any { it.type == Actions.NOTIFY } == true && Build.VERSION.SDK_INT >= 33) {
            out += Need(
                "notify", "Notifications", "Post task notifications",
                Kind.RUNTIME, permission = android.Manifest.permission.POST_NOTIFICATIONS
            )
        }
        return out
    }
}
