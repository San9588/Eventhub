package com.eventsh.app.engine

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * The action executor: the big when-switch that maps every [Actions] type to
 * its implementation. This is the file to touch when adding or changing what a
 * single action does. It also owns the task/profile lookup helpers used by the
 * Task Run / Task Stop / Profile Enable-Disable-Delete actions.
 *
 * Helper functions live in:
 *   DispatcherValues.kt  condition eval, value lists, arrays
 *   DispatcherSystem.kt  shell, termux, toggles, retry, intent extras
 */
internal fun execute(
    ctx: Context,
    profile: Profile,
    task: Task,
    a: Action,
    vars: MutableMap<String, String>,
    attempts: Int,
    event: String,
    summary: String,
    depth: Int = 0
): Boolean {
    return try {
        when (a.type) {
            Actions.SHELL -> if (a.value.isNotBlank()) {
                val cmd = Vars.resolve(a.value, vars)
                retry(attempts, "shell", profile.name) { runShell(ctx, profile, cmd, vars) }
            } else true

            Actions.SCRIPT -> if (a.value.isNotBlank()) {
                val taskName = Vars.resolve(a.value, vars)
                retry(attempts, "termux", profile.name) { termuxTask(ctx, taskName, vars) }
            } else true

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
                        Log.w(Dispatcher.TAG, "send broadcast failed", e)
                        false
                    }
                }
            } else true

            Actions.NOTIFY -> retry(attempts, "notify", profile.name) {
                try {
                    Dispatcher.ensureChannel(ctx)
                    val text = Vars.resolve(a.value, vars).ifBlank { summary }
                    val n = android.app.Notification.Builder(ctx, Dispatcher.CHANNEL_EVENT)
                        .setSmallIcon(android.R.drawable.ic_menu_more)
                        .setContentTitle("Maniflow: ${profile.name}")
                        .setContentText(text)
                        .setAutoCancel(true)
                        .setWhen(System.currentTimeMillis())
                        .build()
                    ctx.getSystemService(NotificationManager::class.java)
                        .notify((profile.id + a.type).hashCode() and 0x7fffffff, n)
                    true
                } catch (e: Exception) {
                    Log.w(Dispatcher.TAG, "notify failed", e)
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
                            Log.w(Dispatcher.TAG, "root cmd failed", e)
                            false
                        }
                    }
                }.start()
                true
            } else true

            Actions.FLASH -> {
                val text = Vars.resolve(a.value, vars).ifBlank { summary }
                val secs = (Vars.resolve(a.extra, vars).toIntOrNull() ?: 0).coerceIn(0, 30)
                Flash.show(ctx, text, if (secs > 0) secs * 1000L else 2000L)
                EventLog.push("[${profile.name}] flash: ${text.take(80)}")
                true
            }

            Actions.SPEAK -> if (a.value.isNotBlank()) {
                val text = Vars.resolve(a.value, vars)
                val pitch = Vars.resolve(a.extra, vars).toFloatOrNull() ?: 1f
                val rate = Vars.resolve(a.extra2, vars).toFloatOrNull() ?: 1f
                val ok = Tts.speak(ctx, text, pitch, rate)
                EventLog.push("[${profile.name}] speak -> ${if (ok) "ok" else "failed"}")
                ok
            } else true

            Actions.HTTP -> if (a.value.isNotBlank()) {
                val cfg = Actions.httpCfg(a.extra2)
                retry(attempts, "http", profile.name) {
                    try {
                        val res = HttpApi.execute(ctx, a.value, cfg, vars)
                        val resultVar = cfg.resultVar.trim().removePrefix("%")
                            .ifBlank { "http_result" }
                        UserVars.set(ctx, resultVar, res.body)
                        vars[resultVar] = res.body
                        UserVars.set(ctx, "http_code", res.code.toString())
                        vars["http_code"] = res.code.toString()
                        EventLog.push(
                            "[${profile.name}] http ${Vars.resolve(cfg.method, vars).uppercase()} -> " +
                                "${res.code} (${res.body.length} chars) saved to %$resultVar"
                        )
                        true
                    } catch (e: Exception) {
                        Log.w(Dispatcher.TAG, "http request failed", e)
                        EventLog.push("[${profile.name}] http FAILED: ${e.message?.take(120) ?: "error"}")
                        false
                    }
                }
            } else true

            Actions.VAR_SET -> {
                val name = Vars.resolve(a.value, vars).trim()
                val valExpr = Vars.resolve(a.extra, vars)
                val (append, sep) = Actions.appendCfg(a.extra2)
                if (name.isNotBlank()) {
                    val cur = UserVars.get(ctx, name) ?: vars[name]
                    val result = if (append) (cur ?: "") + sep + valExpr else (MathExpr.tryEval(valExpr) ?: valExpr)
                    UserVars.set(ctx, name, result)
                    vars[name] = result
                    EventLog.push("[${profile.name}] var %$name = $result")
                    true
                } else false
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
                    true
                } else false
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
                    true
                } else false
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
                true
            }

            Actions.ARRAY_SET -> {
                val name = Vars.resolve(a.value, vars).trim().removePrefix("%")
                if (name.isNotBlank()) {
                    val (append, sep) = Actions.appendCfg(a.extra2)
                    val list = parseValueList(a.extra, vars)
                    val out = if (append) readArray(ctx, vars, name) + list else list
                    writeArray(ctx, vars, name, out, sep.ifBlank { "," })
                    EventLog.push("[${profile.name}] array %$name ${if (append) "append" else "set"} (${out.size})")
                    true
                } else false
            }

            Actions.ARRAY_PUSH -> {
                val name = Vars.resolve(a.value, vars).trim().removePrefix("%")
                if (name.isNotBlank()) {
                    val cur = readArray(ctx, vars, name).toMutableList()
                    val added = parseValueList(a.extra, vars)
                    cur.addAll(added)
                    writeArray(ctx, vars, name, cur)
                    EventLog.push("[${profile.name}] array %$name push +${added.size} (${cur.size})")
                    true
                } else false
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
                        true
                    } else {
                        EventLog.push("[${profile.name}] array process: unknown op '$op' (reverse|sort|sort desc|unique|upper|lower|trim)")
                        false
                    }
                } else false
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
                        true
                    } else {
                        EventLog.push("[${profile.name}] array %$name pop: empty or bad index")
                        false
                    }
                } else false
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
                    true
                } else false
            }

            Actions.WIFI_ON -> { systemToggle(ctx, profile, a, "Wifi On", attempts); true }
            Actions.WIFI_OFF -> { systemToggle(ctx, profile, a, "Wifi Off", attempts); true }
            Actions.BT_ON -> { systemToggle(ctx, profile, a, "Bluetooth On", attempts); true }
            Actions.BT_OFF -> { systemToggle(ctx, profile, a, "Bluetooth Off", attempts); true }
            Actions.DATA_ON -> { systemToggle(ctx, profile, a, "Mobile Data On", attempts); true }
            Actions.DATA_OFF -> { systemToggle(ctx, profile, a, "Mobile Data Off", attempts); true }
            Actions.DISPLAY_ON -> { systemToggle(ctx, profile, a, "Display On", attempts); true }
            Actions.DISPLAY_OFF -> { systemToggle(ctx, profile, a, "Display Off", attempts); true }
            Actions.ROTATE_ON -> { systemToggle(ctx, profile, a, "Auto-Rotate On", attempts); true }
            Actions.ROTATE_OFF -> { systemToggle(ctx, profile, a, "Auto-Rotate Off", attempts); true }

            Actions.WAIT -> {
                val secs = (Vars.resolve(a.value, vars).toIntOrNull() ?: 0).coerceIn(0, 86400)
                if (secs > 0) {
                    EventLog.push("[${profile.name}] waiting ${secs}s")
                    Thread.sleep(secs * 1000L)
                }
                true
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
                true
            }

            Actions.SET_ALARM -> {
                val cfg = Actions.alarmCfg(a.extra2)
                val hm = Vars.resolve(a.value, vars)
                val label = Vars.resolve(a.extra, vars)
                val parts = hm.split(":")
                val hour = parts.getOrNull(0)?.toIntOrNull() ?: 7
                val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0
                // always the system clock app (no in-app alarm engine);
                // su just switches to the `am start` root path
                if (cfg.useSu) AlarmEngine.setAlarmSu(ctx, label, hour, minute, cfg.vibrate)
                else AlarmEngine.setAlarmSystem(ctx, label, hour, minute, cfg)
                true
            }

            Actions.CANCEL_ALARM -> {
                // system-clock alarms live in the clock app - there is no
                // public API to cancel them silently, so without root we
                // just tell the user to open the clock app
                val label = Vars.resolve(a.value, vars)
                if (a.extra2 == "su") {
                    AlarmEngine.cancelAlarmSu(ctx)
                } else {
                    EventLog.push("[alarm] cancel '$label' - open the clock app to dismiss system alarms (no public API)")
                }
                true
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
                        Log.w(Dispatcher.TAG, "alarm volume failed", e)
                    }
                }
                true
            }

            Actions.TASK_RUN -> if (a.value.isNotBlank()) {
                val name = Vars.resolve(a.value, vars)
                val t = findTask(ctx, name)
                if (t != null) {
                    if (depth >= 32) {
                        EventLog.push("[${profile.name}] task run depth limit hit for '${t.name}'")
                        false
                    } else {
                        retry(attempts, "task", profile.name) {
                            Dispatcher.runActions(ctx, profile, t, vars, attempts, event, summary, depth + 1)
                            true
                        }
                    }
                } else {
                    EventLog.push("[${profile.name}] task '$name' not found")
                    false
                }
            } else true

            Actions.TASK_STOP -> {
                val name = Vars.resolve(a.value, vars).trim()
                if (name.isBlank()) {
                    dispatcherStopFlags[task.id]?.set(true)
                    EventLog.push("[${profile.name}] current task stopped by Task Stop")
                } else {
                    val t = findTask(ctx, name)
                    if (t != null) {
                        dispatcherStopFlags[t.id]?.set(true)
                        EventLog.push("[${profile.name}] stop requested for task '${t.name}'")
                    } else {
                        EventLog.push("[${profile.name}] task '$name' not found")
                    }
                }
                true
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
                    true
                } else {
                    EventLog.push("[${profile.name}] task '$name' not found")
                    false
                }
            } else true

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
                    true
                } else {
                    EventLog.push("[${profile.name}] profile '$name' not found")
                    false
                }
            }

            else -> {
                Log.w(Dispatcher.TAG, "unknown action type ${a.type}")
                false
            }
        }
    } catch (e: Exception) {
        Log.w(Dispatcher.TAG, "action ${a.type} failed", e)
        false
    }
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
