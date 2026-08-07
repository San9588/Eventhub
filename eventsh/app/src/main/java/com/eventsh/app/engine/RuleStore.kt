package com.eventsh.app.engine

import android.content.Context
import org.json.JSONArray

object RuleStore {
    private const val PREFS = "eventsh"
    private const val KEY_RULES = "rules"
    private const val KEY_AUTOSTART = "autostart"

    fun load(ctx: Context): List<Rule> {
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
    }

    fun autostart(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_AUTOSTART, true)

    fun setAutostart(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_AUTOSTART, on).apply()
    }
}
