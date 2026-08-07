package com.eventsh.app.engine

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.eventsh.app.receiver.TimerReceiver
import java.util.Calendar

/**
 * Schedules one-shot and daily timer rules via AlarmManager.
 *
 * A timer rule is a normal [Rule] with either `atEpoch > 0` (one-shot)
 * or a non-blank `daily` field "HH:mm" (repeating). When it fires, its
 * own task / notify / root actions run, and the corresponding event
 * (`timer.one` / `timer.daily`) is dispatched so other rules can listen.
 */
object Scheduler {
    const val ACTION_FIRE = "com.eventsh.TIMER_FIRE"
    const val EXTRA_ID = "id"

    fun schedule(ctx: Context, rule: Rule) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = fireIntent(ctx, rule)
        if (rule.isOneShotTimer) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, rule.atEpoch, pi)
        } else if (rule.isDailyTimer) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextDaily(rule.daily), pi)
        }
    }

    fun cancel(ctx: Context, rule: Rule) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(fireIntent(ctx, rule))
    }

    /** Re-arms every persisted timer rule (call on boot / service start). */
    fun rescheduleAll(ctx: Context) {
        RuleStore.load(ctx)
            .filter { it.enabled && (it.isOneShotTimer || it.isDailyTimer) }
            .forEach { schedule(ctx, it) }
    }

    /** Called from TimerReceiver when an alarm fires. */
    fun onFire(ctx: Context, ruleId: String) {
        val rules = RuleStore.load(ctx)
        val rule = rules.find { it.id == ruleId } ?: return
        if (!rule.enabled) return
        val event = if (rule.isDailyTimer) "timer.daily" else "timer.one"
        val data = mapOf("summary" to rule.label, "timer" to rule.id)
        if (rule.isOneShotTimer) {
            RuleStore.save(ctx, rules.toMutableList().apply { remove(rule) })
        } else if (rule.isDailyTimer) {
            schedule(ctx, rule)
        }
        Dispatcher.fire(ctx, rule, event, data)
        EventHub.dispatch(event, data)
    }

    private fun fireIntent(ctx: Context, rule: Rule): PendingIntent {
        val i = Intent(ctx, TimerReceiver::class.java).apply {
            action = ACTION_FIRE
            putExtra(EXTRA_ID, rule.id)
        }
        return PendingIntent.getBroadcast(
            ctx, rule.id.hashCode(), i,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun nextDaily(hhmm: String): Long {
        val parts = hhmm.split(":")
        val h = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val m = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, h)
            set(Calendar.MINUTE, m)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis
    }
}
