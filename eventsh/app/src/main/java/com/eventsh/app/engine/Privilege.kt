package com.eventsh.app.engine

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Log

/**
 * Privileged execution for system actions.
 *
 * Android 13+ (and, for some toggles, Android 10+) no longer lets normal apps
 * do things like toggle wifi / bluetooth / mobile data via the public API.
 * For those actions we expose a "Run with su" option:
 *  - [RootBridge] (su) when the user ticks the box,
 *  - [ShizukuClient] when the user granted Shizuku access,
 *  - otherwise a clear notification is posted telling the user what to enable,
 *    and the action is skipped instead of silently failing.
 *
 * On Android 12 and below actions with a working public API go straight to
 * [PrivResult.DIRECT] so the caller can use the standard API.
 */
object Privilege {
    const val TAG = "EVENTSH"
    const val CHANNEL_PRIV = "privilege"
    const val NOTIF_ID = 0x5052

    enum class PrivResult { DONE, DIRECT, FAILED }

    fun ensureChannel(ctx: Context) {
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(
                CHANNEL_PRIV, "Privileged access",
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "Actions that need Shizuku or root" }
            ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    /**
     * Runs [cmd] (a privileged shell command) for action [type].
     * Returns:
     *  - [PrivResult.DONE]   command ran (su or Shizuku),
     *  - [PrivResult.DIRECT] the public API still works, caller should use it,
     *  - [PrivResult.FAILED] no privilege available - a notification was posted.
     */
    fun runPrivileged(ctx: Context, type: String, label: String, cmd: String, useSu: Boolean): PrivResult {
        return if (useSu) {
            runSu(ctx, label, cmd)
        } else {
            val limit = Actions.sdkLimit(type)
            if (limit == null || android.os.Build.VERSION.SDK_INT >= limit) {
                if (ShizukuClient.available) {
                    runShizuku(ctx, label, cmd)
                } else {
                    notifyNeeds(ctx, label)
                    EventLog.push("[$label] skipped - needs Shizuku (or 'Run with su') on Android 13+")
                    PrivResult.FAILED
                }
            } else {
                PrivResult.DIRECT
            }
        }
    }

    private fun runSu(ctx: Context, label: String, cmd: String): PrivResult {
        val out = RootBridge.execute(cmd)
        EventLog.push("[$label] su -> ${out?.trim()?.take(80) ?: "ok"}")
        return if (out == null || !out.startsWith("exit=")) PrivResult.DONE else PrivResult.FAILED
    }

    private fun runShizuku(ctx: Context, label: String, cmd: String): PrivResult {
        val out = ShizukuClient.execute(cmd)
        EventLog.push("[$label] shizuku -> ${out?.trim()?.take(80) ?: "ok"}")
        return if (out != null) PrivResult.DONE else PrivResult.FAILED
    }

    /**
     * Posts the "this action needs Shizuku on Android 13+" notification.
     */
    fun notifyNeeds(ctx: Context, label: String) {
        try {
            ensureChannel(ctx)
            val n = android.app.Notification.Builder(ctx, CHANNEL_PRIV)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("Action needs Shizuku")
                .setContentText(
                    "'$label' can't run with the standard API on Android 13+. " +
                        "Enable 'Run with su' in the action, or grant Shizuku access."
                )
                .setStyle(android.app.Notification.BigTextStyle().bigText(
                    "'$label' can't run with the standard API on Android 13+. " +
                        "Edit the action and enable 'Run with su', or grant this app Shizuku access " +
                        "(settings > Shizuku) so the command can run through the Shizuku server."
                ))
                .setAutoCancel(true)
                .setWhen(System.currentTimeMillis())
                .build()
            ctx.getSystemService(NotificationManager::class.java)
                .notify(NOTIF_ID, n)
            EventLog.push("[priv] posted 'needs Shizuku' for $label")
        } catch (e: Exception) {
            Log.w(TAG, "privilege notify failed", e)
        }
    }
}
