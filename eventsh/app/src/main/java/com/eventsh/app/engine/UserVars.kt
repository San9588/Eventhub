package com.eventsh.app.engine

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * User-defined variables.
 * lowercase name (myvar)  -> RAM only  (volatile, dies with process)
 * UPPERCASE name (MYVAR)  -> saved to disk (persists across restarts)
 */
object UserVars {
    private const val PREFS = "uservars"
    private const val KEY = "vars"
    private val ram = ConcurrentHashMap<String, String>()
    private val disk = HashMap<String, String>()
    @Volatile private var initialized = false

    fun init(ctx: Context) {
        synchronized(this) {
            if (initialized) return
            initialized = true
            val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
                ?: return
            try {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    disk[o.getString("n")] = o.getString("v")
                }
            } catch (e: Exception) {
            }
        }
    }

    fun isDiskName(name: String): Boolean = name.isNotEmpty() && name.first().isUpperCase()

    fun set(ctx: Context, name: String, value: String) {
        if (name.isBlank()) return
        init(ctx)
        if (isDiskName(name)) {
            synchronized(disk) { disk[name] = value }
            persist(ctx)
        } else {
            ram[name] = value
        }
    }

    fun remove(ctx: Context, name: String) {
        init(ctx)
        if (isDiskName(name)) {
            synchronized(disk) { disk.remove(name) }
            persist(ctx)
        } else {
            ram.remove(name)
        }
    }

    fun get(ctx: Context, name: String): String? {
        init(ctx)
        return if (isDiskName(name)) disk[name] else ram[name]
    }

    fun entries(ctx: Context): List<Pair<String, String>> {
        init(ctx)
        val out = ArrayList<Pair<String, String>>()
        synchronized(disk) { disk.forEach { (k, v) -> out.add(k to v) } }
        ram.forEach { (k, v) -> out.add(k to v) }
        return out.sortedBy { it.first.lowercase() }
    }

    fun all(ctx: Context): Map<String, String> {
        init(ctx)
        val m = HashMap<String, String>()
        synchronized(disk) { m.putAll(disk) }
        m.putAll(ram)
        return m
    }

    private fun persist(ctx: Context) {
        try {
            val arr = JSONArray()
            synchronized(disk) {
                disk.forEach { (k, v) ->
                    arr.put(JSONObject().put("n", k).put("v", v))
                }
            }
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, arr.toString()).apply()
        } catch (e: Exception) {
        }
    }
}
