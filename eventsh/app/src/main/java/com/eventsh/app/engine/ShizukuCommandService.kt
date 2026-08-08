package com.eventsh.app.engine

/**
 * Shizuku user service. Shizuku instantiates this class in its own process
 * (running as shell/root uid), so commands executed here are privileged.
 * This class must NOT touch Android Context - it has no valid Context inside
 * the Shizuku process.
 */
class ShizukuCommandService : ICommandService.Stub() {

    override fun execute(command: String): String {
        val p = Runtime.getRuntime().exec(arrayOf("/system/bin/sh", "-c", command))
        val out = p.inputStream.bufferedReader().use { it.readText() }
        val err = p.errorStream.bufferedReader().use { it.readText() }
        p.waitFor()
        val merged = buildString {
            if (out.isNotBlank()) append(out.trim())
            if (err.isNotBlank()) {
                if (isNotEmpty()) append('\n')
                append(err.trim())
            }
        }
        return if (merged.isBlank()) "" else merged
    }
}
