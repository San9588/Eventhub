package com.eventsh.app.engine

import java.io.BufferedReader
import java.io.InputStreamReader

object RootBridge {
    @Volatile var available: Boolean? = null

    fun checkAsync() {
        Thread {
            available = hasRoot()
        }.start()
    }

    fun hasRoot(): Boolean = try {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
        val out = BufferedReader(InputStreamReader(p.inputStream)).readText()
        val exit = p.waitFor()
        p.destroy()
        exit == 0 && out.isNotEmpty() && out.contains("uid=0")
    } catch (e: Exception) {
        false
    }

    fun execute(cmd: String): String? = try {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
        val out = BufferedReader(InputStreamReader(p.inputStream)).readText()
        val exit = p.waitFor()
        p.destroy()
        if (exit == 0) out else "exit=$exit $out"
    } catch (e: Exception) {
        e.message
    }
}
