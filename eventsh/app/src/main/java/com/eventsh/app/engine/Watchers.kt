package com.eventsh.app.engine

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.media.session.MediaSessionManager
import android.os.FileObserver
import android.os.Handler
import android.os.HandlerThread
import java.io.File

/**
 * Background watchers for event families that have no system broadcast:
 *  - foreground app events (`app_open` / `app_close` / `fg_app`) + App contexts
 *  - resource events (`ram_pct` / `disk_free`)
 *  - file events (`file_modified` / `file_opened` / `file_closed` /
 *    `file_deleted` / `file_moved` / `file_attr`) via recursive FileObserver
 *  - `music_track` (active media session metadata polling)
 *
 * All are *need-driven*: a worker thread runs only while at least one enabled
 * rule actually uses the corresponding events, and sleeps parked on a monitor
 * (zero CPU) otherwise. Needs are recomputed from the in-memory rule cache on
 * every [resync], so the threads never touch disk in their hot loop.
 */
object Watchers {
    private val lock = Object()

    @Volatile private var fgThread: Thread? = null
    @Volatile private var statsThread: Thread? = null
    @Volatile private var musicThread: Thread? = null

    @Volatile private var needApp = false
    @Volatile private var needAppState = false
    @Volatile private var needRam = false
    @Volatile private var needDisk = false
    @Volatile private var ramThreshold: Int? = null
    @Volatile private var diskThreshold: Long? = null
    @Volatile private var lastFg = ""

    @Volatile private var needMusic = false
    @Volatile private var lastTrack = ""

    @Volatile private var needFiles = false
    private var fileHandlerThread: HandlerThread? = null
    private var fileHandler: Handler? = null
    private val fileObservers = HashMap<String, FileObserver>()

    private val APP_EVENTS = setOf("app_open", "app_close", "fg_app")
    private val FILE_EVENTS = setOf(
        "file_modified", "file_opened", "file_closed",
        "file_deleted", "file_moved", "file_attr"
    )
    private val FILE_MASK = FileObserver.OPEN or FileObserver.ACCESS or
        FileObserver.MODIFY or FileObserver.ATTRIB or
        FileObserver.CLOSE_WRITE or FileObserver.CLOSE_NOWRITE or
        FileObserver.MOVED_FROM or FileObserver.MOVED_TO or
        FileObserver.DELETE or FileObserver.CREATE or FileObserver.DELETE_SELF

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
            if (musicThread?.isAlive != true) {
                musicThread = Thread { loopMusic(c) }.apply { name = "eventsh-music"; isDaemon = true; start() }
            }
            if (fileHandlerThread?.isAlive != true) {
                val ht = HandlerThread("eventsh-files").apply { start() }
                fileHandlerThread = ht
                fileHandler = Handler(ht.looper)
            }
        }
        if (needFiles) refreshFileObservers(c)
    }

    /**
     * Re-evaluates what the watchers need from the current ruleset and wakes
     * the workers if their workload changed. Called on rule edits / service start.
     */
    fun resync(ctx: Context) {
        val wasApp = needApp
        val wasStats = needRam || needDisk
        val wasMusic = needMusic
        val wasFiles = needFiles
        syncNeeds(ctx)
        if (needApp && !wasApp) lastFg = ""
        if (needMusic && !wasMusic) lastTrack = ""
        if (needApp != wasApp || (needRam || needDisk) != wasStats ||
            needMusic != wasMusic || needFiles != wasFiles) {
            synchronized(lock) { lock.notifyAll() }
        }
        if (needFiles) refreshFileObservers(ctx)
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
        needMusic = profiles.any { it.enabled && it.hasEvent("music_track") }
        needFiles = profiles.any { it.enabled && it.eventActions.any { e -> e in FILE_EVENTS } }
    }

    fun stop() {
        fgThread?.interrupt()
        statsThread?.interrupt()
        musicThread?.interrupt()
        fgThread = null
        statsThread = null
        musicThread = null
        needApp = false
        needAppState = false
        needRam = false
        needDisk = false
        needMusic = false
        needFiles = false
        lastFg = ""
        lastTrack = ""
        synchronized(lock) { lock.notifyAll() }
        stopFileObservers()
    }

    // ------------------------------------------------------------- file events
    private fun fileWatchPaths(ctx: Context): Set<String> = Store.cachedProfiles(ctx)
        .filter { it.enabled }
        .flatMap { it.contexts.filterIsInstance<EventCtx>() }
        .filter { it.action in FILE_EVENTS }
        .mapNotNull { c -> c.params["path"] ?: c.filter }
        .filter { it.isNotBlank() }
        .toSet()

    private fun refreshFileObservers(ctx: Context) {
        val handler = fileHandler ?: return
        val paths = fileWatchPaths(ctx)
        handler.post {
            teardownObservers()
            paths.forEach { path ->
                val f = File(path)
                if (!f.exists()) return@forEach
                val dirs = ArrayList<File>()
                collectDirs(f, dirs, 0)
                for (d in dirs) {
                    if (fileObservers.containsKey(d.absolutePath)) continue
                    try {
                        val obs = object : FileObserver(d.absolutePath, FILE_MASK) {
                            override fun onEvent(event: Int, path: String?) {
                                if (path == null) return
                                handleFileEvent(d.absolutePath, path, event)
                            }
                        }
                        obs.startWatching()
                        fileObservers[d.absolutePath] = obs
                    } catch (e: Exception) {
                    }
                }
            }
        }
    }

    private fun stopFileObservers() {
        fileHandler?.post { teardownObservers() }
        fileHandlerThread?.quitSafely()
        fileHandlerThread = null
        fileHandler = null
    }

    private fun teardownObservers() {
        fileObservers.values.forEach { obs ->
            try { obs.stopWatching() } catch (e: Exception) {}
        }
        fileObservers.clear()
    }

    private fun collectDirs(f: File, out: ArrayList<File>, depth: Int) {
        if (depth > 10 || out.size >= 400) return
        if (f.isDirectory) {
            out.add(f)
            val children = try { f.listFiles() } catch (e: Exception) { null } ?: return
            for (c in children) {
                if (c.isDirectory) collectDirs(c, out, depth + 1)
            }
        }
    }

    private fun handleFileEvent(dirPath: String, name: String, event: Int) {
        val full = if (name.startsWith(File.separator)) name else File(dirPath, name).absolutePath
        val data = mapOf("summary" to full, "path" to full, "name" to name)
        val ev = when {
            event and FileObserver.MODIFY != 0 -> "file_modified"
            event and FileObserver.OPEN != 0 -> "file_opened"
            event and (FileObserver.CLOSE_WRITE or FileObserver.CLOSE_NOWRITE) != 0 -> "file_closed"
            event and FileObserver.DELETE != 0 -> "file_deleted"
            event and (FileObserver.MOVED_FROM or FileObserver.MOVED_TO) != 0 -> "file_moved"
            event and FileObserver.ATTRIB != 0 -> "file_attr"
            else -> null
        }
        if (ev != null) EventHub.dispatch(ev, data)
    }

    // ------------------------------------------------------------- music track
    private fun loopMusic(ctx: Context) {
        while (!Thread.currentThread().isInterrupted) {
            if (!needMusic) {
                try { parkUntil { needMusic } } catch (e: InterruptedException) { break }
                continue
            }
            try {
                val msm = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
                val cn = ComponentName(ctx, com.eventsh.app.service.NotificationBridge::class.java)
                val controllers = msm.getActiveSessions(cn)
                val c = controllers.firstOrNull { ctl ->
                    ctl.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING
                } ?: controllers.firstOrNull()
                val md = c?.metadata
                val track = md?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE) ?: ""
                if (track.isNotBlank() && track != lastTrack) {
                    lastTrack = track
                    val artist = md?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST) ?: ""
                    val album = md?.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM) ?: ""
                    EventHub.dispatch(
                        "music_track",
                        mapOf(
                            "summary" to "$artist - $track",
                            "title" to track,
                            "artist" to artist,
                            "album" to album
                        )
                    )
                }
                Thread.sleep(2000)
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                try { Thread.sleep(5000) } catch (ie: InterruptedException) { break }
            }
        }
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
