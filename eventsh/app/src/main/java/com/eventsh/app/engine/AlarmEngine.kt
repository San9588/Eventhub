package com.eventsh.app.engine

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * System-alarm bridge for the Set Alarm / Cancel Alarm actions.
 *
 * No in-app alarm clock exists: an alarm is always handed to the system clock
 * app without root -
 *  - normal mode: [setAlarmSystem] fires the public
 *    [android.provider.AlarmClock.ACTION_SET_ALARM] intent with SKIP_UI, so the
 *    clock app sets the alarm silently (no UI) and rings it reliably, free of
 *    Doze or OEM restrictions.
 *  - su mode: [setAlarmSu] runs the equivalent `am start` command as root.
 *
 * On Android 12+ the clock app refuses SET_ALARM unless this app holds the
 * exact-alarm special permission ([android.Manifest.permission.SCHEDULE_EXACT_ALARM]).
 * It is denied by default on Android 13+ even when declared, so [setAlarmSystem]
 * checks it first and, when missing, posts a tap-through notification that
 * opens the "Alarms & reminders" settings screen instead of failing silently.
 *
 * Alarm settings (sound / snooze) live in the clock app, so they are not part
 * of this action. If no clock app resolves the intent, the failure is logged -
 * there is nothing left to fall back to.
 */
object AlarmEngine {
    const val TAG = "MANIFLOW"
    const val CHANNEL_ALARM_PERM = "alarm_perm"

    /**
     * Sets a real system alarm WITHOUT root:
     * fires the standard [android.provider.AlarmClock.ACTION_SET_ALARM] intent
     * (with SKIP_UI, the clock app sets it silently) and lets the system clock
     * ring it reliably - no Doze immunity or OEM restrictions needed.
     *
     * The only catch is the exact-alarm special permission: Android 12+ clock
     * apps refuse SET_ALARM (SecurityException "permission Denial") when the
     * caller cannot schedule exact alarms. It is denied by default on Android
     * 13+, so we check it up front and, when missing, post a notification that
     * jumps the user to the "Alarms & reminders" settings screen.
     */
    fun setAlarmSystem(
        ctx: Context, label: String, hour: Int, minute: Int, cfg: Actions.AlarmCfg
    ) {
        Thread {
            try {
                if (Build.VERSION.SDK_INT >= 31) {
                    val am = ctx.getSystemService(android.app.AlarmManager::class.java)
                    if (!am.canScheduleExactAlarms()) {
                        EventLog.push(
                            "[alarm] system clock blocked: exact-alarm permission not granted " +
                                "(enable it in Settings > Exact alarms, no root needed)"
                        )
                        notifyExactAlarmNeeded(ctx, label, hour, minute)
                        return@Thread
                    }
                }
                val i = Intent(android.provider.AlarmClock.ACTION_SET_ALARM).apply {
                    putExtra(android.provider.AlarmClock.EXTRA_HOUR, hour)
                    putExtra(android.provider.AlarmClock.EXTRA_MINUTES, minute)
                    putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)
                    putExtra(android.provider.AlarmClock.EXTRA_VIBRATE, cfg.vibrate)
                    if (label.isNotBlank()) {
                        putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, label)
                    }
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(i)
                EventLog.push("[alarm] system clock: '$label' set at ${String.format("%02d:%02d", hour, minute)} (no root)")
            } catch (e: Exception) {
                EventLog.push("[alarm] system clock unavailable (${e.message?.take(40) ?: "no clock app"})")
                if (Build.VERSION.SDK_INT >= 29 && !Settings.canDrawOverlays(ctx)) {
                    EventLog.push(
                        "[alarm] hint: grant 'Display over other apps' to set alarms from the background without root"
                    )
                }
                Log.w(TAG, "system clock alarm failed", e)
            }
        }.start()
    }

    private fun notifyExactAlarmNeeded(ctx: Context, label: String, hour: Int, minute: Int) {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ALARM_PERM, "Alarm setup", NotificationManager.IMPORTANCE_HIGH
                    )
                )
            }
            val time = String.format("%02d:%02d", hour, minute)
            val contentIntent = PendingIntent.getActivity(
                ctx, 0,
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:${ctx.packageName}")),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val n = android.app.Notification.Builder(ctx, CHANNEL_ALARM_PERM)
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentTitle("Alarm '$label' at $time - blocked")
                .setContentText("The clock app needs 'Alarms & reminders' permission to set it. Tap to grant (no root).")
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .build()
            ctx.getSystemService(NotificationManager::class.java)
                .notify(CHANNEL_ALARM_PERM.hashCode() and 0x7fffffff, n)
        } catch (e: Exception) {
            Log.w(TAG, "exact alarm guidance notification failed", e)
        }
    }

    /** Sets the alarm through the system clock app (Run with su mode). */
    fun setAlarmSu(ctx: Context, label: String, hour: Int, minute: Int, vibrate: Boolean) {
        val cmd = buildString {
            append("am start -a android.intent.action.SET_ALARM")
            append(" --ei android.intent.extra.alarm.HOUR $hour")
            append(" --ei android.intent.extra.alarm.MINUTES $minute")
            append(" --ez android.intent.extra.alarm.VIBRATE ${if (vibrate) "true" else "false"}")
            // SKIP_UI sets the alarm directly without opening the clock app UI
            append(" --ez android.intent.extra.alarm.SKIP_UI true")
            if (label.isNotBlank()) append(" --es android.intent.extra.alarm.MESSAGE \"${label.replace("\"", "")}\"")
        }
        Thread {
            val out = RootBridge.execute(cmd)
            EventLog.push("[alarm] su SET_ALARM -> ${out?.trim()?.take(60) ?: "ok"}")
        }.start()
    }

    /** Opens the system clock's alarm list (Run with su mode). */
    fun cancelAlarmSu(ctx: Context) {
        Thread {
            val out = RootBridge.execute("am start -a android.intent.action.SHOW_ALARMS")
            EventLog.push("[alarm] su SHOW_ALARMS -> ${out?.trim()?.take(60) ?: "ok"}")
        }.start()
    }
}
