package com.eventsh.app.engine

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log

/**
 * System-level side effects used by the action executor: shell commands,
 * Termux script dispatch, wifi / bluetooth / data / display / rotate toggles,
 * retry-with-backoff and typed intent extras. Everything here talks to the OS
 * or other apps; it never touches task control flow.
 */
internal fun retry(
    attempts: Int,
    channel: String,
    label: String,
    action: (attempt: Int) -> Boolean
): Boolean {
    var delay = 2000L
    for (attempt in 1..attempts) {
        val ok = try {
            action(attempt)
        } catch (e: Exception) {
            Log.w(Dispatcher.TAG, "$channel attempt $attempt failed", e)
            false
        }
        if (ok) {
            if (attempt > 1) EventLog.push("[$label] $channel ok after retry $attempt")
            return true
        }
        if (attempt < attempts) {
            try { Thread.sleep(delay) } catch (e: InterruptedException) { return false }
            delay *= 2
        }
    }
    EventLog.push("[$label] $channel FAILED after $attempts attempts")
    return false
}

/**
 * Runs a shell command in eventsh's own process (Tasker "Run Shell" style).
 * Uses the device's /system/bin/sh. stdout / stderr / exit code are stored
 * in %STDOUT / %STDERR / %EXIT (RAM vars) and added to the current task's
 * variable map so later actions can reference them.
 */
internal fun runShell(
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
        Log.w(Dispatcher.TAG, "shell cmd failed", e)
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
 * Runs a Termux script named [taskName] (i.e. ~/.termux/tasker/<name>.sh).
 * PRIMARY: Termux's official com.termux.RUN_COMMAND service (no third-party
 * plugin, args travel via intent = no disk writes). FALLBACK: the
 * Termux:Tasker plugin broadcast. RUN_COMMAND needs "Allow external apps"
 * enabled in Termux settings.
 */
internal fun termuxTask(
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
        Log.w(Dispatcher.TAG, "termux RUN_COMMAND permission denied", e)
    } catch (e: ClassNotFoundException) {
        EventLog.push("[$taskName] RUN_COMMAND service missing: update Termux (0.117+)")
        Log.w(Dispatcher.TAG, "termux RUN_COMMAND service not found", e)
    } catch (e: Exception) {
        EventLog.push("[$taskName] RUN_COMMAND failed: ${e.message?.take(100) ?: "unknown"}")
        Log.w(Dispatcher.TAG, "termux RUN_COMMAND failed", e)
    }
    // 2) Termux:Tasker plugin fallback
    return try {
        ctx.packageManager.getPackageInfo("com.termux.tasker", 0)
        val b = Bundle().apply { vars.forEach { (k, v) -> putString(k, v) } }
        val i = Intent(Dispatcher.ACTION_TASKER_REQ).apply {
            setPackage("com.termux.tasker")
            putExtra(Dispatcher.EXTRA_TASKER_INTENT, taskName)
            putExtra(Dispatcher.EXTRA_TASKER_MSG, "$event:$summary")
            putExtra(Dispatcher.EXTRA_TASKER_BUNDLE, b)
        }
        ctx.sendBroadcast(i)
        true
    } catch (e: Exception) {
        Log.w(Dispatcher.TAG, "tasker plugin not available", e)
        false
    }
}

/**
 * Runs a system toggle (wifi / bluetooth / data / display / rotate).
 * Uses the public API when it still works (Android 12 and below for wifi /
 * bluetooth), otherwise runs through su (when the action's "Run with su"
 * option is on) or Shizuku. When neither is available a notification is
 * posted telling the user the action needs Shizuku on Android 13+.
 */
internal fun systemToggle(
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
                Log.w(Dispatcher.TAG, "$label failed", e)
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
    Log.w(Dispatcher.TAG, "direct toggle failed", e)
    false
}

/** Parses `key:value` extras. Separators: newline, `|` or `;`. */
internal fun parseExtras(spec: String): List<Pair<String, String>> {
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
internal fun putExtraTyped(i: Intent, key: String, value: String) {
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
