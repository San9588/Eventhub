package com.eventsh.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.eventsh.app.engine.EventLog
import com.eventsh.app.engine.Rule
import com.eventsh.app.engine.RuleStore
import com.eventsh.app.engine.Scheduler
import java.util.UUID

/**
 * Shell / root interface to create timer rules.
 *
 * One-shot:
 *   am broadcast -a com.eventsh.SET_TIMER --es at 1730000000 --es task foo --es label "MyTimer"
 * Daily (HH:mm):
 *   am broadcast -a com.eventsh.SET_TIMER --es daily 07:30 --es task morning --es label "Morning"
 *
 * Optional: --es root "cmd" --es notify true|false --es filter x --es retries N
 * If no `at` and no `daily` is given, a normal event rule is created with `--es event <name>`.
 */
class SetTimerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "com.eventsh.SET_TIMER") return
        val at = intent.getStringExtra("at")?.toLongOrNull() ?: 0L
        val daily = intent.getStringExtra("daily") ?: ""
        val event = intent.getStringExtra("event") ?: ""
        if (at <= 0 && daily.isBlank() && event.isBlank()) {
            EventLog.push("[timer] SET_TIMER needs at/daily/event")
            return
        }
        val label = intent.getStringExtra("label") ?: "TIMER"
        val task = intent.getStringExtra("task") ?: ""
        val root = intent.getStringExtra("root") ?: ""
        val notify = intent.getStringExtra("notify")?.toBooleanStrictOrNull() ?: false
        val retries = intent.getStringExtra("retries")?.toIntOrNull() ?: 0
        val rule = Rule(
            id = "t_" + UUID.randomUUID().toString().take(8),
            event = if (event.isNotBlank()) event else "timer.one",
            label = label,
            enabled = true,
            taskName = task,
            notify = notify,
            rootCmd = root,
            retries = retries,
            atEpoch = at,
            daily = daily
        )
        val rules = RuleStore.load(context).toMutableList().apply { add(rule) }
        RuleStore.save(context, rules)
        Scheduler.schedule(context, rule)
        EventLog.push("[timer] armed '${rule.label}' " + if (daily.isNotBlank()) daily else "at $at")
    }
}

/**
 * Cancel timer rules. `--es id <id>` cancels one; `--es task <task>` cancels all matching.
 */
class CancelTimerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "com.eventsh.CANCEL_TIMER") return
        val id = intent.getStringExtra("id")
        val task = intent.getStringExtra("task") ?: ""
        val rules = RuleStore.load(context)
        val toRemove = rules.filter { r ->
            (id != null && r.id == id) || (task.isNotBlank() && r.taskName == task) ||
                (task == "*" && (r.isOneShotTimer || r.isDailyTimer))
        }
        if (toRemove.isEmpty()) {
            EventLog.push("[timer] cancel: no match")
            return
        }
        toRemove.forEach { Scheduler.cancel(context, it) }
        RuleStore.save(context, rules.toMutableList().apply { removeAll(toRemove) })
        EventLog.push("[timer] cancelled ${toRemove.size} timer(s)")
    }
}
