package com.eventsh.app.engine

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context

object Watchers {
    @Volatile private var fgThread: Thread? = null
    @Volatile private var statsThread: Thread? = null
    @Volatile private var lastFg = ""
    private var ctxRef: Context? = null

    fun start(ctx: Context) {
        ctxRef = ctx.applicationContext
        synchronized(this) {
            if (fgThread?.isAlive != true) {
                fgThread = Thread { loopFg() }.apply { name = "eventsh-fg"; isDaemon = true; start() }
            }
            if (statsThread?.isAlive != true) {
                statsThread = Thread { loopStats() }.apply { name = "eventsh-stats"; isDaemon = true; start() }
            }
        }
    }

    fun stop() {
        fgThread?.interrupt()
        statsThread?.interrupt()
        fgThread = null
        statsThread = null
    }

    private fun loopFg() {
        val ctx = ctxRef ?: return
        while (!Thread.currentThread().isInterrupted) {
            try {
                val rules = RuleStore.load(ctx)
                val want = rules.any {
                    it.enabled &&
                        (it.event in setOf("app_open", "app_close", "fg_app") || it.appCtx != null)
                }
                if (want) {
                    val pkg = foregroundPkg(ctx)
                    if (pkg != null && pkg != lastFg) {
                        val prev = lastFg
                        lastFg = pkg
                        if (prev.isNotEmpty() && prev != pkg) {
                            EventHub.dispatch("app_close", mapOf("summary" to prev, "pkg" to prev))
                        }
                        EventHub.dispatch("app_open", mapOf("summary" to pkg, "pkg" to pkg))
                        EventHub.dispatch("fg_app", mapOf("summary" to pkg, "pkg" to pkg))
                        if (rules.any { it.enabled && it.event.isBlank() && it.timeCtx == null && it.appCtx != null }) {
                            EventHub.dispatch("app.state", mapOf("summary" to pkg, "pkg" to pkg))
                        }
                    }
                } else {
                    lastFg = ""
                }
                Thread.sleep(1500)
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                try { Thread.sleep(3000) } catch (ie: InterruptedException) { break }
            }
        }
    }

    /** Current foreground package (updated only while a watcher needs it). */
    fun foregroundNow(): String? = lastFg.takeIf { it.isNotEmpty() }

    private fun loopStats() {
        val ctx = ctxRef ?: return
        while (!Thread.currentThread().isInterrupted) {
            try {
                val rules = RuleStore.load(ctx)
                val ramRules = rules.filter { it.enabled && it.event == "ram_pct" && it.filter.toIntOrNull() != null }
                if (ramRules.isNotEmpty()) {
                    val (_, pct) = SysStats.mem()
                    EventHub.dispatch("ram_pct", mapOf("summary" to "$pct%", "value" to pct.toString()))
                }
                val diskRules = rules.filter { it.enabled && it.event == "disk_free" && it.filter.toLongOrNull() != null }
                if (diskRules.isNotEmpty()) {
                    val freeMb = SysStats.diskFreeMb()
                    EventHub.dispatch("disk_free", mapOf("summary" to "${freeMb}MB", "value" to freeMb.toString()))
                }
                Thread.sleep(30000)
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                try { Thread.sleep(10000) } catch (ie: InterruptedException) { break }
            }
        }
    }

    private fun foregroundPkg(ctx: Context): String? =
        usageStatsPkg(ctx) ?: rootPkg()

    /** Requires PACKAGE_USAGE_STATS special permission (adb grant / settings). */
    private fun usageStatsPkg(ctx: Context): String? {
        return try {
            if (android.os.Build.VERSION.SDK_INT < 21) null
            else {
                val usm = ctx.getSystemService(UsageStatsManager::class.java)
                val end = System.currentTimeMillis()
                val events = usm.queryEvents(end - 60_000, end)
                val ev = UsageEvents.Event()
                var last: String? = null
                while (events.hasNextEvent()) {
                    events.getNextEvent(ev)
                    if (ev.eventType == UsageEvents.Event.ACTIVITY_RESUMED) last = ev.packageName
                }
                last
            }
        } catch (e: SecurityException) {
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun rootPkg(): String? = try {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "dumpsys activity activities"))
        val text = p.inputStream.bufferedReader().use { it.readText() }
        p.waitFor()
        val m = Regex("topResumedActivity=ComponentInfo\\{(.+?)/(.+?)\\}")
            .find(text) ?: Regex("ResumedActivity: ComponentInfo\\{(.+?)/(.+?)\\}")
            .find(text)
        m?.groupValues?.get(1)
    } catch (e: Exception) {
        null
    }
}
