package com.eventsh.app.engine

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context

/**
 * Background watchers for the two event families that have no system broadcast:
 *  - foreground app events (`app_open` / `app_close` / `fg_app`) + App contexts
 *  - resource events (`ram_pct` / `disk_free`)
 *
 * Both are *need-driven*: a worker thread runs only while at least one enabled
 * rule actually uses the corresponding events, and sleeps parked on a monitor
 * (zero CPU) otherwise. Needs are recomputed from the in-memory rule cache on
 * every [resync], so the threads never touch disk in their hot loop.
 */
object Watchers {
    private val lock = Object()

    @Volatile private var fgThread: Thread? = null
    @Volatile private var statsThread: Thread? = null

    @Volatile private var needApp = false
    @Volatile private var needAppState = false
    @Volatile private var needRam = false
    @Volatile private var needDisk = false
    @Volatile private var ramThreshold: Int? = null
    @Volatile private var diskThreshold: Long? = null
    @Volatile private var lastFg = ""

    private val APP_EVENTS = setOf("app_open", "app_close", "fg_app")

    fun start(ctx: Context) {
        val c = ctx.applicationContext
        syncNeeds(c)
        synchronized(lock) {
            if (fgThread?.isAlive != true) {
                fgThread = Thread { loopFg(c) }.apply { name = "eventsh-fg"; isDaemon = true; start() }
            }
            if (statsThread?.isAlive != true) {
                statsThread = Thread { loopStats(c) }.apply { name = "eventsh-stats"; isDaemon = true; start() }
            }
        }
    }

    /**
     * Re-evaluates what the watchers need from the current ruleset and wakes
     * the workers if their workload changed. Called on rule edits / service start.
     */
    fun resync(ctx: Context) {
        val wasApp = needApp
        val wasStats = needRam || needDisk
        syncNeeds(ctx)
        if (needApp && !wasApp) lastFg = ""
        if (needApp != wasApp || (needRam || needDisk) != wasStats) {
            synchronized(lock) { lock.notifyAll() }
        }
    }

    private fun syncNeeds(ctx: Context) {
        val profiles = Store.cachedProfiles(ctx)
        needApp = profiles.any {
            it.enabled && (it.eventActions.any { e -> e in APP_EVENTS } || it.appCtx != null)
        }
        needAppState = profiles.any {
            it.enabled && it.eventActions.isEmpty() &&
                it.timeCtx == null && it.appCtx != null
        }
        ramThreshold = profiles
            .filter { it.enabled && it.hasEvent("ram_pct") }
            .mapNotNull { it.eventContext?.let { c -> (c.params["value"] ?: c.filter).toIntOrNull() } }
            .firstOrNull()
        needRam = ramThreshold != null
        diskThreshold = profiles
            .filter { it.enabled && it.hasEvent("disk_free") }
            .mapNotNull { it.eventContext?.let { c -> (c.params["value"] ?: c.filter).toLongOrNull() } }
            .firstOrNull()
        needDisk = diskThreshold != null
    }

    fun stop() {
        fgThread?.interrupt()
        statsThread?.interrupt()
        fgThread = null
        statsThread = null
        needApp = false
        needAppState = false
        needRam = false
        needDisk = false
        lastFg = ""
        synchronized(lock) { lock.notifyAll() }
    }

    private fun loopFg(ctx: Context) {
        while (!Thread.currentThread().isInterrupted) {
            if (!needApp) {
                try { parkUntil { needApp } } catch (e: InterruptedException) { break }
                continue
            }
            try {
                val pkg = foregroundPkg(ctx)
                if (pkg != null && pkg != lastFg) {
                    val prev = lastFg
                    lastFg = pkg
                    if (prev.isNotEmpty() && prev != pkg) {
                        EventHub.dispatch("app_close", mapOf("summary" to prev, "pkg" to prev))
                    }
                    EventHub.dispatch("app_open", mapOf("summary" to pkg, "pkg" to pkg))
                    EventHub.dispatch("fg_app", mapOf("summary" to pkg, "pkg" to pkg))
                    if (needAppState) {
                        EventHub.dispatch("app.state", mapOf("summary" to pkg, "pkg" to pkg))
                    }
                }
                Thread.sleep(1500)
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                try { Thread.sleep(3000) } catch (ie: InterruptedException) { break }
            }
        }
    }

    private fun loopStats(ctx: Context) {
        while (!Thread.currentThread().isInterrupted) {
            if (!needRam && !needDisk) {
                try { parkUntil { needRam || needDisk } } catch (e: InterruptedException) { break }
                continue
            }
            try {
                val th = ramThreshold
                if (th != null) {
                    val (_, pct) = SysStats.mem()
                    EventHub.dispatch("ram_pct", mapOf("summary" to "$pct%", "value" to pct.toString()))
                }
                val dt = diskThreshold
                if (dt != null) {
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

    /** Blocks the caller until [cond] is true; zero CPU while parked. */
    private fun parkUntil(cond: () -> Boolean) {
        synchronized(lock) {
            while (!cond()) {
                lock.wait()
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
            }
        }
    }

    /** Current foreground package (updated only while a watcher needs it). */
    fun foregroundNow(): String? = lastFg.takeIf { it.isNotEmpty() }

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
