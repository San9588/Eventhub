package com.eventsh.app.engine

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log

object Dispatcher {
    const val TAG = "EVENTSH"
    const val ACTION_TASKER_REQ = "net.dinglisch.android.tasker.REQBROADCAST"
    const val EXTRA_TASKER_INTENT = "net.dinglisch.android.tasker.extras.INTENT"
    const val EXTRA_TASKER_MSG = "net.dinglisch.android.tasker.extras.MSG"
    const val EXTRA_TASKER_BUNDLE = "net.dinglisch.android.tasker.extras.BUNDLE"

    const val ACTION_OWN = "com.eventsh.TRIGGER"
    const val CHANNEL_EVENT = "events"

    fun ensureChannel(ctx: Context) {
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(
                CHANNEL_EVENT, "Event alerts", NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "Rule triggered events" }
            ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    fun fire(ctx: Context, rule: Rule, event: String, data: Map<String, String>) {
        // all channels may retry with sleep backoff -> keep off the main thread
        Thread {
            fireInner(ctx, rule, event, data)
        }.start()
    }

    private fun fireInner(ctx: Context, rule: Rule, event: String, data: Map<String, String>) {
        val vars = Vars.all(ctx, event, data)
        val summary = vars["SUMMARY"] ?: ""
        EventLog.push("[${rule.label}] $summary")
        val attempts = (rule.retries + 1).coerceAtLeast(1)

        // 0) built-in shell runs FIRST so %STDOUT / %STDERR / %EXIT are
        //    available to the notify text / send extras / root cmd below.
        if (rule.shellCmd.isNotBlank()) {
            val cmd = Vars.resolve(rule.shellCmd, vars)
            retry(attempts, "shell", rule) { _ -> runShell(ctx, rule, cmd, vars) }
        }

        val taskName = Vars.resolve(rule.taskName, vars)
        val notifyText = Vars.resolve(rule.notifyText, vars)
        val rootCmd = Vars.resolve(rule.rootCmd, vars)
        val sendAction = Vars.resolve(rule.sendAction, vars)
        val sendExtras = Vars.resolve(rule.sendExtras, vars)
        val sendPackage = Vars.resolve(rule.sendPackage, vars)

        // 1) Termux script -> plugin protocol OR RUN_COMMAND (no plugin needed)
        if (taskName.isNotBlank()) {
            retry(attempts, "tasker", rule) { attempt ->
                termuxTask(ctx, taskName, vars, event, summary)
            }
        }

        // 2) send custom broadcast to other apps (Tasker-style Send Intent)
        if (sendAction.isNotBlank()) {
            retry(attempts, "send", rule) { attempt ->
                try {
                    val i = Intent(sendAction)
                    if (sendPackage.isNotBlank()) i.setPackage(sendPackage)
                    parseExtras(sendExtras).forEach { (k, v) -> putExtraTyped(i, k, v) }
                    ctx.sendBroadcast(i)
                    true
                } catch (e: Exception) {
                    Log.w(TAG, "send broadcast failed", e)
                    false
                }
            }
        }

        // 3) own generic broadcast (root scripts / custom receivers)
        retry(attempts, "broadcast", rule) { attempt ->
            try {
                val i = Intent(ACTION_OWN).apply {
                    putExtra("event", event)
                    putExtra("rule", rule.id)
                    vars.forEach { (k, v) -> putExtra(k, v) }
                }
                ctx.sendBroadcast(i)
                true
            } catch (e: Exception) {
                Log.w(TAG, "own broadcast failed", e)
                false
            }
        }

        // 4) notification
        if (rule.notify) {
            retry(attempts, "notify", rule) { attempt ->
                try {
                    ensureChannel(ctx)
                    val text = notifyText.ifBlank { "${rule.label}: $summary" }
                    val n = android.app.Notification.Builder(ctx, CHANNEL_EVENT)
                        .setSmallIcon(android.R.drawable.ic_menu_more)
                        .setContentTitle("EVENTSH: ${rule.label}")
                        .setContentText(text)
                        .setAutoCancel(true)
                        .setWhen(System.currentTimeMillis())
                        .build()
                    ctx.getSystemService(NotificationManager::class.java)
                        .notify(rule.id.hashCode() and 0x7fffffff, n)
                    true
                } catch (e: Exception) {
                    Log.w(TAG, "notify failed", e)
                    false
                }
            }
        }

        // 5) root command
        if (rootCmd.isNotBlank()) {
            Thread {
                retry(attempts, "root", rule) { attempt ->
                    try {
                        val out = RootBridge.execute(rootCmd)
                        EventLog.push("[${rule.label}] root -> ${out?.trim() ?: "ok"}")
                        out == null || !out.startsWith("exit=")
                    } catch (e: Exception) {
                        Log.w(TAG, "root cmd failed", e)
                        false
                    }
                }
            }.start()
        }
    }

    /**
     * Runs a shell command in eventsh's own process (Tasker "Run Shell" style).
     * Uses the device's /system/bin/sh. stdout / stderr / exit code are stored
     * in %STDOUT / %STDERR / %EXIT (RAM vars) and added to the current rule's
     * variable map so later actions in the same rule can reference them.
     */
    private fun runShell(
        ctx: Context, rule: Rule, cmd: String, vars: MutableMap<String, String>
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
            EventLog.push("[${rule.label}] shell($code) -> ${outS.take(160)}")
            code == 0
        } catch (e: Exception) {
            Log.w(TAG, "shell cmd failed", e)
            EventLog.push("[${rule.label}] shell FAILED: ${e.message?.take(120) ?: "error"}")
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
        rule: Rule,
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
                if (attempt > 1) EventLog.push("[${rule.label}] $channel ok after retry $attempt")
                return
            }
            if (attempt < attempts) {
                try { Thread.sleep(delay) } catch (e: InterruptedException) { return }
                delay *= 2
            }
        }
        EventLog.push("[${rule.label}] $channel FAILED after $attempts attempts")
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
