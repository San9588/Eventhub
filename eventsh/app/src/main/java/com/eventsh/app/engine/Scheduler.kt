package com.eventsh.app.engine

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.eventsh.app.receiver.TimerReceiver
import java.util.Calendar

/**
 * Schedules timer profiles via AlarmManager.
 *
 * A timer profile is a normal [Profile] with either `atEpoch > 0` (one-shot)
 * or a non-blank `daily` field "HH:mm" (repeating). A profile with a TimeCtx
 * (and no broadcast event) is armed to fire at its next matching occurrence.
 * When it fires, its own linked Task runs and the corresponding event
 * (`timer.one` / `timer.daily`) is dispatched so other profiles can listen.
 */
object Scheduler {
    const val ACTION_FIRE = "com.eventsh.TIMER_FIRE"
    const val EXTRA_ID = "id"
    const val EVENT_TIME = "timer.time"

    fun schedule(ctx: Context, profile: Profile) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = fireIntent(ctx, profile)
        if (profile.isOneShotTimer) {
            setAlarm(am, profile.atEpoch, pi)
        } else if (profile.isDailyTimer) {
            setAlarm(am, nextDaily(profile.daily), pi)
        }
    }

    /** Schedules a profile whose only trigger is a TimeCtx (no broadcast event). */
    fun scheduleCtx(ctx: Context, profile: Profile) {
        val tc = profile.timeCtx ?: return
        val next = nextCtxTrigger(tc, profile.dayCtx, System.currentTimeMillis())
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        setAlarm(am, next, fireIntent(ctx, profile))
    }

    /**
     * Requests an exact alarm but degrades to an inexact (but idle-tolerant)
     * alarm when SCHEDULE_EXACT_ALARM is missing or revoked. Scheduling must
     * never crash the caller - Android 12+ throws SecurityException otherwise.
     */
    private fun setAlarm(am: AlarmManager, triggerAt: Long, pi: PendingIntent) {
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
    }

    fun cancel(ctx: Context, profile: Profile) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(fireIntent(ctx, profile))
    }

    /** Re-arms every persisted timer / time-context profile (call on boot / service start). */
    fun rescheduleAll(ctx: Context) {
        Store.profiles(ctx).filter { it.enabled }.forEach {
            when {
                it.isOneShotTimer || it.isDailyTimer -> schedule(ctx, it)
                it.timeCtx != null -> scheduleCtx(ctx, it)
            }
        }
    }

    /** Called from TimerReceiver when an alarm fires. */
    fun onFire(ctx: Context, profileId: String) {
        val profiles = Store.profiles(ctx)
        val p = profiles.find { it.id == profileId } ?: return
        if (!p.enabled) return
        val data = mapOf("summary" to p.name, "timer" to p.id)
        when {
            p.isOneShotTimer -> {
                Store.saveProfiles(ctx, profiles.toMutableList().apply { remove(p) })
                Dispatcher.fire(ctx, p, "timer.one", data)
                EventHub.dispatch("timer.one", data)
            }
            p.isDailyTimer -> {
                schedule(ctx, p)
                Dispatcher.fire(ctx, p, "timer.daily", data)
                EventHub.dispatch("timer.daily", data)
            }
            p.timeCtx != null -> {
                scheduleCtx(ctx, p)
                EventHub.fireRule(ctx, p, EVENT_TIME, data)
            }
        }
    }

    private fun fireIntent(ctx: Context, profile: Profile): PendingIntent {
        val i = Intent(ctx, TimerReceiver::class.java).apply {
            action = ACTION_FIRE
            putExtra(EXTRA_ID, profile.id)
        }
        return PendingIntent.getBroadcast(
            ctx, profile.id.hashCode(), i,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /** Next occurrence of a TimeCtx (+ optional DayCtx), strictly after [fromMs]. */
    private fun nextCtxTrigger(tc: TimeCtx, dc: DayCtx?, fromMs: Long): Long {
        val now = Calendar.getInstance().apply { timeInMillis = fromMs }
        for (i in 0 until 366) {
            if (dc == null || ContextGate.dayMatch(dc, now)) {
                for (occ in occurrencesForDay(tc)) {
                    val cand = (now.clone() as Calendar).apply {
                        set(Calendar.HOUR_OF_DAY, occ / 60)
                        set(Calendar.MINUTE, occ % 60)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                    if (cand.timeInMillis > fromMs) return cand.timeInMillis
                }
            }
            now.add(Calendar.DAY_OF_YEAR, 1)
        }
        return fromMs + 24L * 3600 * 1000
    }

    /** Minutes-of-day occurrences for a TimeCtx on one day. */
    private fun occurrencesForDay(tc: TimeCtx): List<Int> {
        val from = tc.fromMin
        val to = tc.toMin
        val r = tc.repeatMin
        val out = mutableListOf<Int>()
        if (r > 0) {
            var occ = from
            while (if (to >= from) occ <= to else occ <= 1439) {
                out.add(occ)
                occ += r
            }
            if (to < from) {
                var occ2 = 0
                while (occ2 <= to) { out.add(occ2); occ2 += r }
            }
        } else {
            out.add(from)
        }
        return out.distinct().sorted()
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
