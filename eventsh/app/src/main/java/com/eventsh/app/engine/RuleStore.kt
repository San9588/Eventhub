package com.eventsh.app.engine

import android.content.Context
import org.json.JSONArray

object RuleStore {
    private const val PREFS = "eventsh"
    private const val KEY_RULES = "rules"
    private const val KEY_AUTOSTART = "autostart"

    /**
     * In-memory snapshot of the persisted rules. Background watchers poll on a
     * schedule; without a cache every poll would hit SharedPreferences on disk.
     * Kept fresh by [save] and dropped by [invalidate] whenever the process is
     * unsure of the on-disk state.
     */
    @Volatile private var cache: List<Rule>? = null

    /** Loads rules, hitting disk at most once per process unless invalidated. */
    fun load(ctx: Context): List<Rule> {
        cache?.let { return it }
        val rules = read(ctx)
        cache = rules
        return rules
    }

    /** Disk-free read for hot paths (watchers, receivers). */
    fun cached(ctx: Context): List<Rule> = cache ?: load(ctx)

    /** Forces the next [load] to re-read from disk. */
    fun invalidate() {
        cache = null
    }

    private fun read(ctx: Context): List<Rule> {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_RULES, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val list = ArrayList<Rule>(arr.length())
            for (i in 0 until arr.length()) list.add(Rule.fromJson(arr.getJSONObject(i)))
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun save(ctx: Context, rules: List<Rule>) {
        val arr = JSONArray()
        rules.forEach { arr.put(it.toJson()) }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_RULES, arr.toString()).apply()
        cache = rules
    }

    fun autostart(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_AUTOSTART, true)

    fun setAutostart(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_AUTOSTART, on).apply()
    }
}
