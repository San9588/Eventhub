package com.eventsh.app.engine

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs a profile's linked Task (its list of actions) when a profile fires.
 * All channels may retry with sleep backoff, so execution runs on a worker
 * thread. A shell action runs inline so %STDOUT/%STDERR/%EXIT become
 * available to the actions that follow it.
 */
object Dispatcher {
    const val TAG = "EVENTSH"
    const val ACTION_TASKER_REQ = "net.dinglisch.android.tasker.REQBROADCAST"
    const val EXTRA_TASKER_INTENT = "net.dinglisch.android.tasker.extras.INTENT"
    const val EXTRA_TASKER_MSG = "net.dinglisch.android.tasker.extras.MSG"
    const val EXTRA_TASKER_BUNDLE = "net.dinglisch.android.tasker.extras.BUNDLE"

    const val ACTION_OWN = "com.eventsh.TRIGGER"
    const val CHANNEL_EVENT = "events"

    /** Per-task abort flags: set by Stop / Task Stop, cleared when a task starts. */
    private val stopFlags = ConcurrentHashMap<String, AtomicBoolean>()

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
    private fun runActions(
        ctx: Context,
        profile: Profile,
        task: Task,
        vars: MutableMap<String, String>,
        attempts: Int,
        event: String,
        summary: String,
        depth: Int = 0
    ) {
        val actions = task.actions
        var pc = 0
        val forStack = ArrayDeque<ForFrame>()
        var steps = 0
        val maxSteps = actions.size * 1000 + 1000
        stopFlags[task.id] = AtomicBoolean(false)
        while (pc < actions.size) {
            if (stopFlags[task.id]?.get() == true) break
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
                    val values = loopValues(a, vars)
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
                        execute(ctx, profile, task, a, vars, attempts, event, summary, depth)
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

    private fun findTask(ctx: Context, nameOrId: String): Task? {
        val s = nameOrId.trim()
        if (s.isBlank()) return null
        return Store.cachedTasks(ctx).find { it.id == s || it.name == s }
    }

    private fun findProfile(ctx: Context, nameOrId: String): Profile? {
        val s = nameOrId.trim()
        if (s.isBlank()) return null
        return Store.cachedProfiles(ctx).find { it.id == s || it.name == s }
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

    /**
     * Evaluates a Tasker-style If condition:
     *  `%var = val`, `!=`, `>`, `>=`, `<`, `<=` (numeric compare),
     *  `%var ~ pattern`, `%var !~ pattern` (wildcard, * + / ! supported),
     *  bare expression = true when the resolved value is non-blank.
     * The operator used is the one appearing earliest in the expression, so
     * `%var = a~b` compares with `=` and `%var ~ *a*` matches with `~`.
     */
    private fun evalCondition(expr: String, vars: Map<String, String>): Boolean {
        val s = expr.trim()
        if (s.isEmpty()) return true
        var bestOp: String? = null
        var bestIdx = -1
        for (op in arrayOf("!~", "~", ">=", "<=", "!=", "==", "=", ">", "<")) {
            val idx = s.indexOf(op)
            if (idx > 0 && (bestIdx < 0 || idx < bestIdx)) {
                bestIdx = idx
                bestOp = op
            }
        }
        if (bestOp != null) {
            val l = Vars.resolve(s.substring(0, bestIdx).trim(), vars)
            val r = Vars.resolve(s.substring(bestIdx + bestOp.length).trim(), vars)
            return when (bestOp) {
                "=", "==" -> l == r
                "!=" -> l != r
                ">", ">=", "<", "<=" -> {
                    val a = l.toDoubleOrNull()
                    val b = r.toDoubleOrNull()
                    if (a == null || b == null) false
                    else when (bestOp) {
                        ">" -> a > b
                        ">=" -> a >= b
                        "<" -> a < b
                        else -> a <= b
                    }
                }
                "~" -> ContextGate.matchPattern(r, l)
                else -> !ContextGate.matchPattern(r, l)
            }
        }
        return Vars.resolve(s, vars).isNotBlank()
    }

    /** Resolves a For loop's value list: "1..5", "a,b,c" or an array %var. */
    private fun loopValues(a: Action, vars: Map<String, String>): List<String> =
        parseValueList(a.value, vars)

    /**
     * Parses a value spec into a list. Supports "1..5" (range, forward or
     * backward), "a,b,c" (comma list) and "%arr" (1-based array elements).
     * %VAR% references inside a plain spec are resolved first.
     */
    private fun parseValueList(spec: String, vars: Map<String, String>): List<String> {
        val raw = spec.trim()
        if (raw.isBlank()) return emptyList()
        if (raw.startsWith("%")) {
            val base = raw.removePrefix("%").trim()
            val out = mutableListOf<String>()
            var i = 1
            while (out.size < 1000) {
                val v = vars[base + i]
                if (v == null) break
                out.add(v)
                i++
            }
            return out
        }
        val resolved = Vars.resolve(raw, vars).trim()
        val range = Regex("^(-?\\d+)\\.\\.(-?\\d+)$").find(resolved)
        if (range != null) {
            val from = range.groupValues[1].toInt()
            val to = range.groupValues[2].toInt()
            val step = if (from <= to) 1 else -1
            val out = mutableListOf<String>()
            var v = from
            var guard = 0
            while (if (step > 0) v <= to else v >= to) {
                out.add(v.toString())
                if (++guard > 100000) break
                v += step
            }
            return out
        }
        return resolved.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** Reads a 1-based array %name1..%nameN (stops at the first gap). */
    private fun readArray(ctx: Context, vars: Map<String, String>, name: String): List<String> {
        val out = mutableListOf<String>()
        var i = 1
        while (i <= 10000) {
            val v = vars[name + i] ?: UserVars.get(ctx, name + i) ?: break
            out.add(v)
            i++
        }
        return out
    }

    /** Writes a 1-based array, keeps the base var in sync and clears leftovers. */
    private fun writeArray(ctx: Context, vars: MutableMap<String, String>, name: String, list: List<String>) {
        list.forEachIndexed { i, v ->
            val k = name + (i + 1)
            UserVars.set(ctx, k, v)
            vars[k] = v
        }
        var i = list.size + 1
        while (i <= list.size + 1000) {
            val k = name + i
            val inVars = vars.remove(k)
            val inDisk = UserVars.get(ctx, k)
            if (inVars == null && inDisk == null) break
            if (inDisk != null) UserVars.remove(ctx, k)
            i++
        }
        val base = list.joinToString(",")
        UserVars.set(ctx, name, base)
        vars[name] = base
    }

    private fun execute(
        ctx: Context,
        profile: Profile,
        task: Task,
        a: Action,
        vars: MutableMap<String, String>,
        attempts: Int,
        event: String,
        summary: String,
        depth: Int = 0
    ) {
        try {
            when (a.type) {
                Actions.SHELL -> if (a.value.isNotBlank()) {
                    val cmd = Vars.resolve(a.value, vars)
                    retry(attempts, "shell", profile.name) { runShell(ctx, profile, cmd, vars) }
                }

                Actions.SCRIPT -> if (a.value.isNotBlank()) {
                    val taskName = Vars.resolve(a.value, vars)
                    retry(attempts, "tasker", profile.name) { termuxTask(ctx, taskName, vars, event, summary) }
                }

                Actions.INTENT -> if (a.value.isNotBlank()) {
                    val action = Vars.resolve(a.value, vars)
                    val extras = Vars.resolve(a.extra, vars)
                    val pkg = Vars.resolve(a.extra2, vars)
                    retry(attempts, "send", profile.name) {
                        try {
                            val i = Intent(action)
                            if (pkg.isNotBlank()) i.setPackage(pkg)
                            parseExtras(extras).forEach { (k, v) -> putExtraTyped(i, k, v) }
                            ctx.sendBroadcast(i)
                            true
                        } catch (e: Exception) {
                            Log.w(TAG, "send broadcast failed", e)
                            false
                        }
                    }
                }

                Actions.NOTIFY -> retry(attempts, "notify", profile.name) {
                    try {
                        ensureChannel(ctx)
                        val text = Vars.resolve(a.value, vars).ifBlank { summary }
                        val n = android.app.Notification.Builder(ctx, CHANNEL_EVENT)
                            .setSmallIcon(android.R.drawable.ic_menu_more)
                            .setContentTitle("EVENTSH: ${profile.name}")
                            .setContentText(text)
                            .setAutoCancel(true)
                            .setWhen(System.currentTimeMillis())
                            .build()
                        ctx.getSystemService(NotificationManager::class.java)
                            .notify((profile.id + a.type).hashCode() and 0x7fffffff, n)
                        true
                    } catch (e: Exception) {
                        Log.w(TAG, "notify failed", e)
                        false
                    }
                }

                Actions.ROOT -> if (a.value.isNotBlank()) {
                    val cmd = Vars.resolve(a.value, vars)
                    Thread {
                        retry(attempts, "root", profile.name) {
                            try {
                                val out = RootBridge.execute(cmd)
                                EventLog.push("[${profile.name}] root -> ${out?.trim() ?: "ok"}")
                                out == null || !out.startsWith("exit=")
                            } catch (e: Exception) {
                                Log.w(TAG, "root cmd failed", e)
                                false
                            }
                        }
                    }.start()
                }

                Actions.FLASH -> {
                    val text = Vars.resolve(a.value, vars).ifBlank { summary }
                    val secs = (Vars.resolve(a.extra, vars).toIntOrNull() ?: 0).coerceIn(0, 30)
                    Flash.show(ctx, text, if (secs > 0) secs * 1000L else 2000L)
                    EventLog.push("[${profile.name}] flash: ${text.take(80)}")
                }

                Actions.VAR_SET -> {
                    val name = Vars.resolve(a.value, vars).trim()
                    val valExpr = Vars.resolve(a.extra, vars)
                    val append = a.extra2.equals("append", true)
                    if (name.isNotBlank()) {
                        val cur = UserVars.get(ctx, name) ?: vars[name]
                        val result = if (append) (cur ?: "") + valExpr else (MathExpr.tryEval(valExpr) ?: valExpr)
                        UserVars.set(ctx, name, result)
                        vars[name] = result
                        EventLog.push("[${profile.name}] var %$name = $result")
                    }
                }

                Actions.VAR_SPLIT -> {
                    val name = Vars.resolve(a.value, vars).trim()
                    val sep = Vars.resolve(a.extra, vars)
                    if (name.isNotBlank()) {
                        val cur = UserVars.get(ctx, name) ?: vars[name] ?: ""
                        val parts = if (sep.isEmpty()) cur.map { it.toString() }
                        else cur.split(sep)
                        parts.forEachIndexed { i, p ->
                            val k = name + (i + 1)
                            UserVars.set(ctx, k, p)
                            vars[k] = p
                        }
                        EventLog.push("[${profile.name}] split %$name -> ${parts.size} part(s)")
                    }
                }

                Actions.VAR_JOIN -> {
                    val base = Vars.resolve(a.value, vars).trim()
                    val joiner = Vars.resolve(a.extra, vars).ifBlank { "," }
                    val max = a.extra2.toIntOrNull() ?: Int.MAX_VALUE
                    if (base.isNotBlank()) {
                        val parts = mutableListOf<String>()
                        var i = 1
                        while (i <= max && parts.size < 1000) {
                            val v = UserVars.get(ctx, base + i) ?: vars[base + i]
                            if (v == null) break
                            parts.add(v)
                            i++
                        }
                        val joined = parts.joinToString(joiner)
                        UserVars.set(ctx, base, joined)
                        vars[base] = joined
                        EventLog.push("[${profile.name}] joined %$base from ${parts.size} part(s)")
                    }
                }

                Actions.VAR_QUERY -> {
                    val name = Vars.resolve(a.value, vars).trim()
                    val target = Vars.resolve(a.extra, vars).trim()
                    val dflt = Vars.resolve(a.extra2, vars)
                    val v = UserVars.get(ctx, name) ?: vars[name] ?: dflt
                    if (target.isNotBlank()) {
                        UserVars.set(ctx, target, v ?: "")
                        vars[target] = v ?: ""
                        EventLog.push("[${profile.name}] query %$name -> %$target")
                    } else {
                        EventLog.push("[${profile.name}] %$name = ${v ?: "(unset)"}")
                    }
                }

                Actions.ARRAY_SET -> {
                    val name = Vars.resolve(a.value, vars).trim().removePrefix("%")
                    if (name.isNotBlank()) {
                        val list = parseValueList(a.extra, vars)
                        writeArray(ctx, vars, name, list)
                        EventLog.push("[${profile.name}] array %$name set (${list.size})")
                    }
                }

                Actions.ARRAY_PUSH -> {
                    val name = Vars.resolve(a.value, vars).trim().removePrefix("%")
                    if (name.isNotBlank()) {
                        val cur = readArray(ctx, vars, name).toMutableList()
                        val added = parseValueList(a.extra, vars)
                        cur.addAll(added)
                        writeArray(ctx, vars, name, cur)
                        EventLog.push("[${profile.name}] array %$name push +${added.size} (${cur.size})")
                    }
                }

                Actions.ARRAY_PROCESS -> {
                    val name = Vars.resolve(a.value, vars).trim().removePrefix("%")
                    val op = Vars.resolve(a.extra, vars).trim().lowercase()
                    if (name.isNotBlank() && op.isNotBlank()) {
                        val cur = readArray(ctx, vars, name)
                        val out = when (op) {
                            "reverse" -> cur.reversed()
                            "sort" -> cur.sorted()
                            "sort desc", "sortdesc" -> cur.sortedDescending()
                            "unique" -> LinkedHashSet(cur).toList()
                            "upper" -> cur.map { it.uppercase() }
                            "lower" -> cur.map { it.lowercase() }
                            "trim" -> cur.map { it.trim() }
                            else -> null
                        }
                        if (out != null) {
                            writeArray(ctx, vars, name, out)
                            EventLog.push("[${profile.name}] array %$name $op -> ${out.size}")
                        } else {
                            EventLog.push("[${profile.name}] array process: unknown op '$op' (reverse|sort|sort desc|unique|upper|lower|trim)")
                        }
                    }
                }

                Actions.ARRAY_POP -> {
                    val name = Vars.resolve(a.value, vars).trim().removePrefix("%")
                    if (name.isNotBlank()) {
                        val cur = readArray(ctx, vars, name).toMutableList()
                        val idx = Vars.resolve(a.extra, vars).trim().toIntOrNull()
                        val popped = if (cur.isEmpty()) null
                        else if (idx == null) cur.removeAt(cur.lastIndex)
                        else if (idx in 1..cur.size) cur.removeAt(idx - 1)
                        else null
                        if (popped != null) {
                            writeArray(ctx, vars, name, cur)
                            val target = Vars.resolve(a.extra2, vars).trim().removePrefix("%")
                            if (target.isNotBlank()) {
                                UserVars.set(ctx, target, popped)
                                vars[target] = popped
                            }
                            EventLog.push("[${profile.name}] array %$name popped '$popped' (${cur.size} left)")
                        } else {
                            EventLog.push("[${profile.name}] array %$name pop: empty or bad index")
                        }
                    }
                }

                Actions.ARRAY_CLEAR -> {
                    val name = Vars.resolve(a.value, vars).trim().removePrefix("%")
                    if (name.isNotBlank()) {
                        var i = 1
                        while (i <= 10000) {
                            val k = name + i
                            val inVars = vars.remove(k)
                            val inDisk = UserVars.get(ctx, k)
                            if (inVars == null && inDisk == null) break
                            if (inDisk != null) UserVars.remove(ctx, k)
                            i++
                        }
                        UserVars.remove(ctx, name)
                        vars.remove(name)
                        EventLog.push("[${profile.name}] array %$name cleared")
                    }
                }

                Actions.WIFI_ON -> systemToggle(ctx, profile, a, "Wifi On", attempts)
                Actions.WIFI_OFF -> systemToggle(ctx, profile, a, "Wifi Off", attempts)
                Actions.BT_ON -> systemToggle(ctx, profile, a, "Bluetooth On", attempts)
                Actions.BT_OFF -> systemToggle(ctx, profile, a, "Bluetooth Off", attempts)
                Actions.DATA_ON -> systemToggle(ctx, profile, a, "Mobile Data On", attempts)
                Actions.DATA_OFF -> systemToggle(ctx, profile, a, "Mobile Data Off", attempts)
                Actions.DISPLAY_ON -> systemToggle(ctx, profile, a, "Display On", attempts)
                Actions.DISPLAY_OFF -> systemToggle(ctx, profile, a, "Display Off", attempts)
                Actions.ROTATE_ON -> systemToggle(ctx, profile, a, "Auto-Rotate On", attempts)
                Actions.ROTATE_OFF -> systemToggle(ctx, profile, a, "Auto-Rotate Off", attempts)

                Actions.WAIT -> if (a.value.isNotBlank()) {
                    val secs = (Vars.resolve(a.value, vars).toIntOrNull() ?: 0).coerceIn(0, 86400)
                    if (secs > 0) {
                        EventLog.push("[${profile.name}] waiting ${secs}s")
                        Thread.sleep(secs * 1000L)
                    }
                }

                Actions.WAIT_UNTIL -> {
                    val cond = Vars.resolve(a.value, vars)
                    val timeout = (Vars.resolve(a.extra, vars).toIntOrNull() ?: 30).coerceIn(1, 3600)
                    val start = System.currentTimeMillis()
                    while (!evalCondition(cond, vars)) {
                        if (System.currentTimeMillis() - start >= timeout * 1000L) {
                            EventLog.push("[${profile.name}] wait-until timed out after ${timeout}s")
                            break
                        }
                        Thread.sleep(500)
                    }
                    if (evalCondition(cond, vars)) EventLog.push("[${profile.name}] wait-until condition met")
                }

                Actions.SET_ALARM -> {
                    val cfg = Actions.alarmCfg(a.extra2)
                    val hm = Vars.resolve(a.value, vars)
                    val label = Vars.resolve(a.extra, vars)
                    val parts = hm.split(":")
                    val hour = parts.getOrNull(0)?.toIntOrNull() ?: 7
                    val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0
                    if (cfg.useSu) {
                        AlarmEngine.setAlarmSu(ctx, label, hour, minute, cfg.vibrate)
                    } else {
                        Thread { AlarmEngine.setAlarm(ctx, label, hour, minute, cfg) }.start()
                    }
                }

                Actions.CANCEL_ALARM -> {
                    val label = Vars.resolve(a.value, vars)
                    if (a.extra2 == "su") {
                        AlarmEngine.cancelAlarmSu(ctx)
                    } else {
                        Thread { AlarmEngine.cancel(ctx, label) }.start()
                    }
                }

                Actions.ALARM_VOLUME -> {
                    val vol = (Vars.resolve(a.value, vars).toIntOrNull() ?: 0).coerceIn(0, 15)
                    if (a.extra2 == "su") {
                        Thread {
                            val out = RootBridge.execute("settings put system alarm_volume $vol")
                            EventLog.push("[${profile.name}] alarm volume su -> ${out?.trim()?.take(60) ?: "ok"}")
                        }.start()
                    } else {
                        try {
                            val am = ctx.getSystemService(android.media.AudioManager::class.java)
                            am.setStreamVolume(android.media.AudioManager.STREAM_ALARM, vol, 0)
                            EventLog.push("[${profile.name}] alarm volume set to $vol")
                        } catch (e: Exception) {
                            Log.w(TAG, "alarm volume failed", e)
                        }
                    }
                }

                Actions.TASK_RUN -> if (a.value.isNotBlank()) {
                    val name = Vars.resolve(a.value, vars)
                    val t = findTask(ctx, name)
                    if (t != null) {
                        if (depth >= 32) {
                            EventLog.push("[${profile.name}] task run depth limit hit for '${t.name}'")
                        } else {
                            retry(attempts, "task", profile.name) {
                                runActions(ctx, profile, t, vars, attempts, event, summary, depth + 1)
                                true
                            }
                        }
                    } else {
                        EventLog.push("[${profile.name}] task '$name' not found")
                    }
                }

                Actions.TASK_STOP -> {
                    val name = Vars.resolve(a.value, vars).trim()
                    if (name.isBlank()) {
                        stopFlags[task.id]?.set(true)
                        EventLog.push("[${profile.name}] current task stopped by Task Stop")
                    } else {
                        val t = findTask(ctx, name)
                        if (t != null) {
                            stopFlags[t.id]?.set(true)
                            EventLog.push("[${profile.name}] stop requested for task '${t.name}'")
                        } else {
                            EventLog.push("[${profile.name}] task '$name' not found")
                        }
                    }
                }

                Actions.TASK_ENABLE, Actions.TASK_DISABLE -> if (a.value.isNotBlank()) {
                    val on = a.type == Actions.TASK_ENABLE
                    val name = Vars.resolve(a.value, vars)
                    val t = findTask(ctx, name)
                    if (t != null) {
                        val cur = Store.tasks(ctx).toMutableList()
                        val i = cur.indexOfFirst { it.id == t.id }
                        if (i >= 0) {
                            cur[i] = cur[i].copy(enabled = on)
                            Store.saveTasks(ctx, cur)
                        }
                        EventLog.push("[${profile.name}] task '${t.name}' ${if (on) "enabled" else "disabled"}")
                    } else {
                        EventLog.push("[${profile.name}] task '$name' not found")
                    }
                }

                Actions.PROFILE_ENABLE, Actions.PROFILE_DISABLE, Actions.PROFILE_DELETE -> {
                    val name = Vars.resolve(a.value, vars)
                    val p = findProfile(ctx, name)
                    if (p != null) {
                        when (a.type) {
                            Actions.PROFILE_ENABLE -> {
                                setProfileEnabled(ctx, p, true)
                                EventLog.push("[${profile.name}] profile '${p.name}' enabled")
                            }
                            Actions.PROFILE_DISABLE -> {
                                setProfileEnabled(ctx, p, false)
                                EventLog.push("[${profile.name}] profile '${p.name}' disabled")
                            }
                            else -> {
                                Scheduler.cancel(ctx, p)
                                val cur = Store.profiles(ctx).toMutableList()
                                cur.removeAll { it.id == p.id }
                                Store.saveProfiles(ctx, cur)
                                EventLog.push("[${profile.name}] profile '${p.name}' deleted")
                            }
                        }
                        EventHub.resync(ctx)
                    } else {
                        EventLog.push("[${profile.name}] profile '$name' not found")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "action ${a.type} failed", e)
        }
    }

    private fun setProfileEnabled(ctx: Context, p: Profile, on: Boolean) {
        val cur = Store.profiles(ctx).toMutableList()
        val i = cur.indexOfFirst { it.id == p.id }
        if (i < 0) return
        cur[i] = cur[i].copy(enabled = on)
        Store.saveProfiles(ctx, cur)
        if (on && (cur[i].isOneShotTimer || cur[i].isDailyTimer || cur[i].timeCtx != null)) {
            Scheduler.schedule(ctx, cur[i])
        } else if (!on) {
            Scheduler.cancel(ctx, cur[i])
        }
        EventHub.resync(ctx)
    }

    /**
     * Runs a system toggle (wifi / bluetooth / data / display / rotate).
     * Uses the public API when it still works (Android 12 and below for wifi /
     * bluetooth), otherwise runs through su (when the action's "Run with su"
     * option is on) or Shizuku. When neither is available a notification is
     * posted telling the user the action needs Shizuku on Android 13+.
     */
    private fun systemToggle(
        ctx: Context, profile: Profile, a: Action, label: String, attempts: Int
    ) {
        Thread {
            retry(attempts, label, profile.name) {
                try {
                    val useSu = a.extra2 == "su"
                    val cmd = Actions.suShell(a.type)
                    when (Privilege.runPrivileged(ctx, a.type, label, cmd, useSu)) {
                        Privilege.PrivResult.DONE -> true
                        Privilege.PrivResult.FAILED -> true // notified; do not retry
                        Privilege.PrivResult.DIRECT -> {
                            val ok = directToggle(ctx, a.type)
                            EventLog.push("[${profile.name}] $label (api) -> ${if (ok) "ok" else "failed"}")
                            ok
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "$label failed", e)
                    false
                }
            }
        }.start()
    }

    /**
     * Runs a toggle through the normal Android API. Only used on versions
     * where the API still works (wifi below Android 10, bluetooth below 13).
     */
    private fun directToggle(ctx: Context, type: String): Boolean = try {
        when (type) {
            Actions.WIFI_ON -> {
                val w = ctx.getSystemService(android.net.wifi.WifiManager::class.java)
                w.isWifiEnabled || w.setWifiEnabled(true)
            }
            Actions.WIFI_OFF -> {
                val w = ctx.getSystemService(android.net.wifi.WifiManager::class.java)
                !w.isWifiEnabled || w.setWifiEnabled(false)
            }
            Actions.BT_ON -> {
                val bt = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
                bt != null && (bt.isEnabled || bt.enable())
            }
            Actions.BT_OFF -> {
                val bt = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
                bt != null && (!bt.isEnabled || bt.disable())
            }
            else -> false
        }
    } catch (e: Exception) {
        Log.w(TAG, "direct toggle failed", e)
        false
    }

    /**
     * Runs a shell command in eventsh's own process (Tasker "Run Shell" style).
     * Uses the device's /system/bin/sh. stdout / stderr / exit code are stored
     * in %STDOUT / %STDERR / %EXIT (RAM vars) and added to the current task's
     * variable map so later actions can reference them.
     */
    private fun runShell(
        ctx: Context, profile: Profile, cmd: String, vars: MutableMap<String, String>
    ): Boolean {
        return try {
            val p = ProcessBuilder("/system/bin/sh", "-c", cmd).start()
            val out = StringBuilder()
            val err = StringBuilder()
            val t1 = Thread { out.append(readLimited(p.inputStream)) }
            val t2 = Thread { err.append(readLimited(p.errorStream)) }
            t1.start(); t2.start()
            val code = p.waitFor()
            t1.join(); t2.join()
            val outS = out.toString().trim()
            val errS = err.toString().trim()
            UserVars.set(ctx, "stdout", outS)
            UserVars.set(ctx, "stderr", errS)
            UserVars.set(ctx, "exit", code.toString())
            vars["STDOUT"] = outS
            vars["STDERR"] = errS
            vars["EXIT"] = code.toString()
            EventLog.push("[${profile.name}] shell($code) -> ${outS.take(160)}")
            code == 0
        } catch (e: Exception) {
            Log.w(TAG, "shell cmd failed", e)
            EventLog.push("[${profile.name}] shell FAILED: ${e.message?.take(120) ?: "error"}")
            false
        }
    }

    /** Reads a process stream, capped at 64 KiB to avoid runaway memory. */
    private fun readLimited(s: java.io.InputStream): String {
        val sb = StringBuilder()
        val buf = ByteArray(8192)
        var total = 0
        try {
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                if (total + n > 64 * 1024) {
                    sb.append(String(buf, 0, 64 * 1024 - total))
                    break
                }
                sb.append(String(buf, 0, n))
                total += n
            }
        } catch (e: Exception) {
        } finally {
            try { s.close() } catch (e: Exception) {}
        }
        return sb.toString()
    }

    /**
     * Runs an action up to [attempts] times with exponential backoff
     * (2s, 4s, 8s...). Logs success / failure to the event log.
     */
    private fun retry(
        attempts: Int,
        channel: String,
        label: String,
        action: (attempt: Int) -> Boolean
    ) {
        var delay = 2000L
        for (attempt in 1..attempts) {
            val ok = try {
                action(attempt)
            } catch (e: Exception) {
                Log.w(TAG, "$channel attempt $attempt failed", e)
                false
            }
            if (ok) {
                if (attempt > 1) EventLog.push("[$label] $channel ok after retry $attempt")
                return
            }
            if (attempt < attempts) {
                try { Thread.sleep(delay) } catch (e: InterruptedException) { return }
                delay *= 2
            }
        }
        EventLog.push("[$label] $channel FAILED after $attempts attempts")
    }

    /**
     * Runs a Termux script named [taskName] (i.e. ~/.termux/tasker/<name>.sh).
     * PRIMARY: Termux's official com.termux.RUN_COMMAND service (no third-party
     * plugin, args travel via intent = no disk writes). FALLBACK: the
     * Termux:Tasker plugin broadcast. RUN_COMMAND needs "Allow external apps"
     * enabled in Termux settings.
     */
    private fun termuxTask(
        ctx: Context, taskName: String, vars: Map<String, String>, event: String, summary: String
    ): Boolean {
        // 1) Termux RUN_COMMAND (official API, no plugin needed)
        try {
            ctx.packageManager.getPackageInfo("com.termux", 0)
            val home = "/data/data/com.termux/files/home"
            val i = Intent("com.termux.RUN_COMMAND").apply {
                setClassName("com.termux", "com.termux.app.RunCommandService")
                putExtra("com.termux.RUN_COMMAND_PATH", "$home/.termux/tasker/$taskName.sh")
                putExtra(
                    "com.termux.RUN_COMMAND_ARGUMENTS",
                    vars.map { "%${it.key}=${it.value}" }.toTypedArray()
                )
                putExtra("com.termux.RUN_COMMAND_WORKDIR", home)
                putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            }
            ctx.startService(i)
            return true
        } catch (e: SecurityException) {
            EventLog.push("[$taskName] RUN_COMMAND denied: Termux 'Allow external apps' OFF or not confirmed")
            Log.w(TAG, "termux RUN_COMMAND permission denied", e)
        } catch (e: ClassNotFoundException) {
            EventLog.push("[$taskName] RUN_COMMAND service missing: update Termux (0.117+)")
            Log.w(TAG, "termux RUN_COMMAND service not found", e)
        } catch (e: Exception) {
            EventLog.push("[$taskName] RUN_COMMAND failed: ${e.message?.take(100) ?: "unknown"}")
            Log.w(TAG, "termux RUN_COMMAND failed", e)
        }
        // 2) Termux:Tasker plugin fallback
        return try {
            ctx.packageManager.getPackageInfo("com.termux.tasker", 0)
            val b = Bundle().apply { vars.forEach { (k, v) -> putString(k, v) } }
            val i = Intent(ACTION_TASKER_REQ).apply {
                setPackage("com.termux.tasker")
                putExtra(EXTRA_TASKER_INTENT, taskName)
                putExtra(EXTRA_TASKER_MSG, "$event:$summary")
                putExtra(EXTRA_TASKER_BUNDLE, b)
            }
            ctx.sendBroadcast(i)
            true
        } catch (e: Exception) {
            Log.w(TAG, "tasker plugin not available", e)
            false
        }
    }

    /** Parses `key:value` extras. Separators: newline, `|` or `;`. */
    private fun parseExtras(spec: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        for (raw in spec.split('\n', '|', ';')) {
            val line = raw.trim()
            if (line.isBlank()) continue
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            out += line.substring(0, idx).trim() to line.substring(idx + 1).trim()
        }
        return out
    }

    /** Tasker-style typed extras: true/false -> boolean, L -> long, D -> double, else int/double/string. */
    private fun putExtraTyped(i: Intent, key: String, value: String) {
        val v = value.trim()
        when {
            v.equals("true", true) -> i.putExtra(key, true)
            v.equals("false", true) -> i.putExtra(key, false)
            v.endsWith("L") && v.dropLast(1).toLongOrNull() != null -> i.putExtra(key, v.dropLast(1).toLong())
            v.endsWith("D") && v.dropLast(1).toDoubleOrNull() != null -> i.putExtra(key, v.dropLast(1).toDouble())
            v.toIntOrNull() != null -> i.putExtra(key, v.toInt())
            v.toLongOrNull() != null -> i.putExtra(key, v.toLong())
            v.toDoubleOrNull() != null -> i.putExtra(key, v.toDouble())
            else -> i.putExtra(key, v)
        }
    }
}

/** A running For loop: start action index + remaining values + loop variable. */
private data class ForFrame(val start: Int, val iter: Iterator<String>, val varName: String)
