package com.eventsh.app.engine

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

// ============================================================================
//  EVENTSH - Dispatcher FILE MAP
// ----------------------------------------------------------------------------
//  This file keeps ONLY the dispatcher core: entry points (fire / runTask /
//  runTaskNow / stopTask), the per-task abort flags and the task runner loop
//  with If/Else/For/Goto control flow. Each other concern lives in its own
//  file (same package) as internal top-level functions, so behaviour is
//  unchanged and each file stays small enough to grep quickly.
//
//  FILE MAP
//    Dispatcher.kt            <- this file (facade + task runner loop)
//    DispatcherActions.kt     execute(): the when-switch mapping every action
//                               type to its implementation, plus task/profile
//                               lookup used by Task Run / Profile actions
//    DispatcherSystem.kt      shell commands, Termux dispatch, wifi/bt/data/
//                               display/rotate toggles, retry-with-backoff,
//                               typed intent extras
//    DispatcherValues.kt      pure value helpers: If-condition evaluation,
//                               For-loop value lists, 1-based arrays
//
//  Where to add new code:
//    - a new action type .......... DispatcherActions.kt -> execute()
//    - a new action catalog entry . engine/Action.kt -> Actions object
//    - a new system toggle ........ DispatcherSystem.kt -> systemToggle()/directToggle()
//    - a new value helper .......... DispatcherValues.kt
//    - change task flow / stop ..... this file -> runActions()
//    - change entry points ......... this file (fire / runTask / runTaskNow)
// ============================================================================

/** Per-task abort flags: set by Stop / Task Stop, cleared when a task starts. */
internal val dispatcherStopFlags = ConcurrentHashMap<String, AtomicBoolean>()

/**
 * Runs a profile's linked Task (its list of actions) when a profile fires.
 * All channels may retry with sleep backoff, so execution runs on a worker
 * thread. A shell action runs inline so %STDOUT/%STDERR/%EXIT become
 * available to the actions that follow it.
 */
object Dispatcher {
    const val TAG = "EVENTSH"
    const val ACTION_OWN = "com.eventsh.TRIGGER"
    const val CHANNEL_EVENT = "events"

    /** Tasks currently running via the Tasks-tab Play button. */
    private val manualRuns = ConcurrentHashMap.newKeySet<String>()

    /**
     * Runs a saved task by id (Tasks-tab Play button).
     * Returns false when the task is missing or already running.
     */
    fun runTask(ctx: Context, taskId: String): Boolean {
        val task = Store.tasks(ctx).find { it.id == taskId } ?: return false
        return runTaskNow(ctx, task)
    }

    /**
     * Runs a task's actions standalone (Task editor Run button), outside any
     * profile trigger. Unlike [runTask] it takes the Task object directly so
     * unsaved edits can be tested. Returns false when already running.
     * Execution happens on a worker thread so the UI never blocks.
     *
     * [onResult] fires on that worker thread for every executed action
     * (index, ok, short message); the trailing (-1, true, "finished") signals
     * the end of the run. Only the top-level task reports - nested tasks
     * launched by Task Run keep their own indexes and do not collide.
     */
    fun runTaskNow(
        ctx: Context,
        task: Task,
        onResult: ((index: Int, ok: Boolean, msg: String) -> Unit)? = null
    ): Boolean {
        if (!manualRuns.add(task.id)) return false
        Thread {
            try {
                val vars = Vars.all(ctx, "manual", mapOf("summary" to task.name))
                val pseudo = Profile(id = task.id, name = task.name, taskId = task.id)
                EventLog.push("[${task.name}] manual run started")
                runActions(ctx, pseudo, task, vars, 1, "manual", task.name, 0, onResult)
            } catch (e: Exception) {
                Log.w(TAG, "manual task run failed", e)
            } finally {
                manualRuns.remove(task.id)
                EventLog.push("[${task.name}] manual run finished")
                onResult?.invoke(-1, true, "finished")
            }
        }.apply { name = "eventsh-run"; start() }
        return true
    }

    /** Stops a task started via the Play button (and its Task Stop target). */
    fun stopTask(taskId: String) {
        dispatcherStopFlags[taskId]?.set(true)
        manualRuns.remove(taskId)
        EventLog.push("[task] stop requested")
    }

    /** True while a task is running via the Play button. */
    fun isTaskRunning(taskId: String): Boolean = taskId in manualRuns

    fun ensureChannel(ctx: Context) {
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(
                CHANNEL_EVENT, "Event alerts", NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "Profile triggered events" }
            ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    fun fire(ctx: Context, profile: Profile, event: String, data: Map<String, String>) {
        // actions may retry with sleep backoff -> keep off the main thread
        Thread {
            fireInner(ctx, profile, event, data)
        }.start()
    }

    private fun fireInner(ctx: Context, profile: Profile, event: String, data: Map<String, String>) {
        val vars = Vars.all(ctx, event, data)
        val summary = vars["SUMMARY"] ?: ""
        EventLog.push("[${profile.name}] $summary")

        // own generic broadcast so root scripts / custom receivers still fire
        try {
            val i = Intent(ACTION_OWN).apply {
                putExtra("event", event)
                putExtra("profile", profile.id)
                vars.forEach { (k, v) -> putExtra(k, v) }
            }
            ctx.sendBroadcast(i)
        } catch (e: Exception) {
            Log.w(TAG, "own broadcast failed", e)
        }

        val task = Store.tasks(ctx).find { it.id == profile.taskId } ?: return
        if (!task.enabled) {
            EventLog.push("[${profile.name}] task '${task.name}' is disabled - skipped")
            return
        }
        val attempts = (task.retries + 1).coerceAtLeast(1)
        runActions(ctx, profile, task, vars, attempts, event, summary)
    }

    /**
     * Executes a task's actions in order with If/Else/End If and For/End For
     * control flow. Block commands (IF/ELSE/END_IF/FOR/END_FOR) just move the
     * instruction pointer; every other action is handed to [execute].
     * Each action also carries its own If guard ([Action.cond]) - when it does
     * not match the action is skipped and execution continues with the next one.
     */
    internal fun runActions(
        ctx: Context,
        profile: Profile,
        task: Task,
        vars: MutableMap<String, String>,
        attempts: Int,
        event: String,
        summary: String,
        depth: Int = 0,
        onResult: ((index: Int, ok: Boolean, msg: String) -> Unit)? = null
    ) {
        val actions = task.actions
        var pc = 0
        val forStack = ArrayDeque<ForFrame>()
        var steps = 0
        val maxSteps = actions.size * 1000 + 1000
        dispatcherStopFlags[task.id] = AtomicBoolean(false)
        val report = if (depth == 0) onResult else null
        while (pc < actions.size) {
            if (dispatcherStopFlags[task.id]?.get() == true) break
            val a = actions[pc]
            when (a.type) {
                Actions.IF -> {
                    if (evalCondition(a.value, vars)) {
                        pc++
                    } else {
                        pc = findBlockEnd(actions, pc, Actions.IF, Actions.ELSE, Actions.END_IF, false)
                    }
                }
                Actions.ELSE -> pc = findBlockEnd(actions, pc, Actions.IF, null, Actions.END_IF, true)
                Actions.END_IF -> pc++
                Actions.FOR -> {
                    val values = parseValueList(a.value, vars)
                    if (values.isEmpty()) {
                        pc = findBlockEnd(actions, pc, Actions.FOR, null, Actions.END_FOR, false)
                    } else {
                        val it = values.iterator()
                        val lv = a.extra.trim().removePrefix("%").ifBlank { "loop" }
                        vars[lv] = it.next()
                        forStack.addLast(ForFrame(pc, it, lv))
                        pc++
                    }
                }
                Actions.END_FOR -> {
                    val f = forStack.removeLastOrNull()
                    if (f != null && f.iter.hasNext()) {
                        vars[f.varName] = f.iter.next()
                        pc = f.start + 1
                    } else {
                        pc++
                    }
                }
                Actions.GOTO -> {
                    val raw = Vars.resolve(a.value, vars).trim()
                    val num = raw.toIntOrNull()
                    val target = if (num != null && num in 1..actions.size) num - 1
                    else actions.indexOfFirst { it.label.equals(raw, true) && it.label.isNotBlank() }
                    if (target >= 0) {
                        pc = target
                        forStack.clear()
                        EventLog.push("[${profile.name}] goto $raw")
                    } else {
                        pc++
                    }
                    steps++
                    if (steps > maxSteps) {
                        EventLog.push("[${profile.name}] goto loop detected - stopping task")
                        break
                    }
                }
                else -> {
                    if (guardPasses(ctx, a, vars)) {
                        val ok = execute(ctx, profile, task, a, vars, attempts, event, summary, depth)
                        report?.invoke(pc, ok, if (ok) "ok" else "failed")
                    } else {
                        report?.invoke(pc, false, "IF guard not matched")
                    }
                    pc++
                }
            }
        }
    }

    /** True unless the action defines an If guard that does not match. */
    private fun guardPasses(ctx: Context, a: Action, vars: MutableMap<String, String>): Boolean {
        val spec = a.condTerms() ?: return true
        return CondSpec.matches(ctx, spec.first, spec.second, vars)
    }

    /**
     * Scans forward from [start] for the block terminator. [startOpen] is true
     * when the caller IS the block opener already accounted for (Else) - it
     * prevents the opener from being counted twice.
     */
    private fun findBlockEnd(
        actions: List<Action>,
        start: Int,
        openType: String,
        elseType: String?,
        endType: String,
        startOpen: Boolean
    ): Int {
        var depth = if (startOpen) 1 else 0
        var i = start
        while (i < actions.size) {
            val t = actions[i].type
            if (elseType != null && depth == 1 && t == elseType) return i + 1
            if (t == openType) {
                depth++
            } else if (t == endType) {
                depth--
                if (depth == 0) return i + 1
            }
            i++
        }
        return actions.size
    }
}
