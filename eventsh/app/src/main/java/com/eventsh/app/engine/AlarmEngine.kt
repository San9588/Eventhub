package com.eventsh.app.engine

import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * System-alarm bridge for the Set Alarm / Cancel Alarm actions.
 *
 * No in-app alarm clock exists: an alarm is always handed to the system clock
 * app, exactly like Tasker does without root -
 *  - normal mode: [setAlarmSystem] fires the public
 *    [android.provider.AlarmClock.ACTION_SET_ALARM] intent with SKIP_UI, so the
 *    clock app sets the alarm silently (no UI) and rings it reliably, free of
 *    exact-alarm permissions, Doze or OEM restrictions.
 *  - su mode: [setAlarmSu] runs the equivalent `am start` command as root.
 *
 * Alarm settings (sound / snooze) live in the clock app, so they are not part
 * of this action. If no clock app resolves the intent, the failure is logged -
 * there is nothing left to fall back to.
 */
object AlarmEngine {
    const val TAG = "EVENTSH"

    /**
     * Sets a real system alarm WITHOUT root, exactly how Tasker does it:
     * fires the standard [android.provider.AlarmClock.ACTION_SET_ALARM] intent
     * (with SKIP_UI, the clock app sets it silently) and lets the system clock
     * ring it reliably - no exact-alarm permission, Doze immunity or OEM
     * restrictions needed.
     */
    fun setAlarmSystem(
        ctx: Context, label: String, hour: Int, minute: Int, cfg: Actions.AlarmCfg
    ) {
        Thread {
            try {
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
                Log.w(TAG, "system clock alarm failed", e)
            }
        }.start()
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
