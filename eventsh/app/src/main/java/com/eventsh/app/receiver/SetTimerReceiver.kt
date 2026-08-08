package com.eventsh.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.eventsh.app.engine.Action
import com.eventsh.app.engine.Actions
import com.eventsh.app.engine.EventCtx
import com.eventsh.app.engine.EventLog
import com.eventsh.app.engine.Profile
import com.eventsh.app.engine.Scheduler
import com.eventsh.app.engine.Store
import com.eventsh.app.engine.Task
import java.util.UUID

/**
 * Shell / root interface to create timer profiles.
 *
 * One-shot:
 *   am broadcast -a com.eventsh.SET_TIMER --es at 1730000000 --es task foo --es label "MyTimer"
 * Daily (HH:mm):
 *   am broadcast -a com.eventsh.SET_TIMER --es daily 07:30 --es task morning --es label "Morning"
 *
 * Optional: --es root "cmd" --es notify true|false --es filter x --es retries N
 * If no `at` and no `daily` is given, a normal event profile is created with `--es event <name>`.
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

        val acts = ArrayList<Action>()
        if (task.isNotBlank()) acts.add(Action(Actions.SCRIPT, task))
        if (root.isNotBlank()) acts.add(Action(Actions.ROOT, root))
        if (notify) acts.add(Action(Actions.NOTIFY, ""))

        val taskId = if (acts.isEmpty()) "" else {
            val t = Task("tk_" + UUID.randomUUID().toString().take(8), label, acts, retries)
            Store.saveTasks(context, Store.tasks(context).toMutableList().apply { add(t) })
            t.id
        }
        val contexts = if (event.isNotBlank()) listOf(EventCtx(event)) else emptyList()
        val profile = Profile(
            id = "p_" + UUID.randomUUID().toString().take(8),
            name = label,
            enabled = true,
            contexts = contexts,
            taskId = taskId,
            atEpoch = at,
            daily = daily
        )
        Store.saveProfiles(context, Store.profiles(context).toMutableList().apply { add(profile) })
        if (profile.isOneShotTimer || profile.isDailyTimer || profile.timeCtx != null) {
            Scheduler.schedule(context, profile)
        }
        EventLog.push("[timer] armed '${profile.name}' " + if (daily.isNotBlank()) daily else "at $at")
    }
}

/**
 * Cancel timer profiles. `--es id <id>` cancels one; `--es task <task>` cancels all matching.
 */
class CancelTimerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "com.eventsh.CANCEL_TIMER") return
        val id = intent.getStringExtra("id")
        val task = intent.getStringExtra("task") ?: ""
        val profiles = Store.profiles(context)
        val toRemove = profiles.filter { p ->
            (id != null && p.id == id) ||
                (task.isNotBlank() && p.name == task) ||
                (task == "*" && (p.isOneShotTimer || p.isDailyTimer))
        }
        if (toRemove.isEmpty()) {
            EventLog.push("[timer] cancel: no match")
            return
        }
        toRemove.forEach { Scheduler.cancel(context, it) }
        Store.saveProfiles(context, profiles.toMutableList().apply { removeAll(toRemove) })
        EventLog.push("[timer] cancelled ${toRemove.size} timer(s)")
    }
}
