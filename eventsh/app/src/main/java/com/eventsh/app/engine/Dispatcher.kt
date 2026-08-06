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

        val taskName = Vars.resolve(rule.taskName, vars)
        val notifyText = Vars.resolve(rule.notifyText, vars)
        val rootCmd = Vars.resolve(rule.rootCmd, vars)
        val attempts = (rule.retries + 1).coerceAtLeast(1)

        // 1) Termux:Tasker plugin protocol -> ~/.termux/tasker/<task>.sh
        if (taskName.isNotBlank()) {
            retry(attempts, "tasker", rule) { attempt ->
                try {
                    val b = Bundle().apply {
                        vars.forEach { (k, v) -> putString(k, v) }
                    }
                    val i = Intent(ACTION_TASKER_REQ).apply {
                        setPackage("com.termux.tasker")
                        putExtra(EXTRA_TASKER_INTENT, taskName)
                        putExtra(EXTRA_TASKER_MSG, "$event:$summary")
                        putExtra(EXTRA_TASKER_BUNDLE, b)
                    }
                    ctx.sendBroadcast(i)
                    true
                } catch (e: Exception) {
                    Log.w(TAG, "tasker broadcast failed", e)
                    false
                }
            }
        }

        // 2) own generic broadcast (root scripts / custom receivers)
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

        // 3) notification
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

        // 4) root command
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
}
