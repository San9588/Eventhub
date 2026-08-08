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
    private fun loopValues(a: Action, vars: Map<String, String>): List<String> {
        val raw = a.value.trim()
        if (raw.isBlank()) return emptyList()
        if (raw.startsWith("%")) {
            val base = raw.removePrefix("%")
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
        val spec = Vars.resolve(raw, vars).trim()
        val range = Regex("^(-?\\d+)\\.\\.(-?\\d+)$").find(spec)
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
        return spec.split(",").map { it.trim() }.filter { it.isNotEmpty() }
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

                Actions.VAR_SET -> {
                    val name = Vars.resolve(a.value, vars).trim()
                    val valExpr = Vars.resolve(a.extra, vars)
                    val append = a.extra2.equals("append", true)
                    if (name.isNotBlank()) {
                        val cur = UserVars.get(ctx, name) ?: vars[name]
                        val result = if (append) (cur ?: "") + valExpr else valExpr
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

                Actions.WIFI_ON -> systemToggle(ctx, profile, "svc wifi enable", "wifi on", attempts)
                Actions.WIFI_OFF -> systemToggle(ctx, profile, "svc wifi disable", "wifi off", attempts)
                Actions.BT_ON -> systemToggle(ctx, profile, "svc bluetooth enable", "bluetooth on", attempts)
                Actions.BT_OFF -> systemToggle(ctx, profile, "svc bluetooth disable", "bluetooth off", attempts)
                Actions.DATA_ON -> systemToggle(ctx, profile, "svc data enable", "mobile data on", attempts)
                Actions.DATA_OFF -> systemToggle(ctx, profile, "svc data disable", "mobile data off", attempts)
                Actions.DISPLAY_ON -> systemToggle(ctx, profile, "input keyevent KEYCODE_WAKEUP", "display on", attempts)
                Actions.DISPLAY_OFF -> systemToggle(ctx, profile, "input keyevent KEYCODE_POWER", "display off", attempts)
                Actions.ROTATE_ON -> systemToggle(ctx, profile, "settings put system accelerometer_rotation 1", "auto-rotate on", attempts)
                Actions.ROTATE_OFF -> systemToggle(ctx, profile, "settings put system accelerometer_rotation 0", "auto-rotate off", attempts)

                Actions.STOP -> {
                    stopFlags[task.id]?.set(true)
                    EventLog.push("[${profile.name}] task stopped by Stop action")
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

                Actions.TASK_STOP -> if (a.value.isNotBlank()) {
                    val name = Vars.resolve(a.value, vars)
                    val t = findTask(ctx, name)
                    if (t != null) {
                        stopFlags[t.id]?.set(true)
                        EventLog.push("[${profile.name}] stop requested for task '${t.name}'")
                    } else {
                        EventLog.push("[${profile.name}] task '$name' not found")
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
     * Runs a shell-level system toggle (root preferred; `svc`/`input`/`settings`
     * need su or a privileged shell). Runs on a worker thread like the root action.
     */
    private fun systemToggle(
        ctx: Context, profile: Profile, cmd: String, label: String, attempts: Int
    ) {
        Thread {
            retry(attempts, label, profile.name) {
                try {
                    val out = RootBridge.execute(cmd)
                    EventLog.push("[${profile.name}] $label -> ${out?.trim()?.take(80) ?: "ok"}")
                    out == null || !out.startsWith("exit=")
                } catch (e: Exception) {
                    Log.w(TAG, "$label failed", e)
                    false
                }
            }
        }.start()
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
