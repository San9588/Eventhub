package com.eventsh.app.engine

import android.os.StatFs
import java.io.File

object SysStats {
    /** returns (used MB, used percent) from /proc/meminfo */
    fun mem(): Pair<Long, Int> = try {
        val meminfo = File("/proc/meminfo").readText()
        val totalKb = parse(meminfo, "MemTotal")
        val availKb = parse(meminfo, "MemAvailable")
        if (totalKb > 0 && availKb > 0) {
            val used = totalKb - availKb
            (used / 1024) to ((used * 100) / totalKb).toInt()
        } else 0L to 0
    } catch (e: Exception) {
        0L to 0
    }

    private fun parse(meminfo: String, key: String): Long {
        val line = meminfo.lines().firstOrNull { it.startsWith("$key:") } ?: return 0L
        return line.replace(Regex("[^0-9]"), "").toLongOrNull() ?: 0L
    }

    fun diskFreeMb(): Long = try {
        val sf = StatFs(File.separator)
        sf.availableBytes / (1024L * 1024L)
    } catch (e: Exception) {
        0L
    }

    fun diskTotalMb(): Long = try {
        val sf = StatFs(File.separator)
        sf.totalBytes / (1024L * 1024L)
    } catch (e: Exception) {
        0L
    }
}
