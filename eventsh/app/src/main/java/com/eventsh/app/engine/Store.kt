package com.eventsh.app.engine

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistence for Profiles and Tasks (Tasker model: profiles = contexts,
 * tasks = actions). Both are cached in memory so background hot paths
 * (watchers, receivers) never touch disk. A one-time migration converts
 * legacy "rules" (merged profile+actions) into the split model.
 */
object Store {
    private const val PREFS = "eventsh"
    private const val KEY_PROFILES = "profiles"
    private const val KEY_TASKS = "tasks"
    private const val KEY_LEGACY_RULES = "rules"
    private const val KEY_AUTOSTART = "autostart"

    @Volatile private var profilesCache: List<Profile>? = null
    @Volatile private var tasksCache: List<Task>? = null

    // ---------------------------------------------------------------- profiles
    fun profiles(ctx: Context): List<Profile> {
        profilesCache?.let { return it }
        migrate(ctx)
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PROFILES, null)
        val list = if (raw == null) emptyList() else parseProfiles(raw)
        profilesCache = list
        return list
    }

    /** Disk-free read for hot paths (watchers, receivers). */
    fun cachedProfiles(ctx: Context): List<Profile> = profilesCache ?: profiles(ctx)

    fun saveProfiles(ctx: Context, list: List<Profile>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_PROFILES, arr.toString()).apply()
        profilesCache = list
    }

    // ---------------------------------------------------------------- tasks
    fun tasks(ctx: Context): List<Task> {
        tasksCache?.let { return it }
        migrate(ctx)
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TASKS, null)
        val list = if (raw == null) emptyList() else parseTasks(raw)
        tasksCache = list
        return list
    }

    fun cachedTasks(ctx: Context): List<Task> = tasksCache ?: tasks(ctx)

    fun saveTasks(ctx: Context, list: List<Task>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_TASKS, arr.toString()).apply()
        tasksCache = list
    }

    /** Drops both caches so the next read hits disk (called on external writes). */
    fun invalidate() {
        profilesCache = null
        tasksCache = null
    }

    fun autostart(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_AUTOSTART, true)

    fun setAutostart(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_AUTOSTART, on).apply()
    }

    // ---------------------------------------------------------------- parsing
    private fun parseProfiles(raw: String): List<Profile> = try {
        val arr = JSONArray(raw)
        val list = ArrayList<Profile>(arr.length())
        for (i in 0 until arr.length()) list.add(Profile.fromJson(arr.getJSONObject(i)))
        list
    } catch (e: Exception) {
        emptyList()
    }

    private fun parseTasks(raw: String): List<Task> = try {
        val arr = JSONArray(raw)
        val list = ArrayList<Task>(arr.length())
        for (i in 0 until arr.length()) list.add(Task.fromJson(arr.getJSONObject(i)))
        list
    } catch (e: Exception) {
        emptyList()
    }

    /**
     * One-time upgrade from the old merged "rules" blob to the split model.
     * Each legacy rule becomes a Profile plus a generated Task holding its
     * action fields (shell/script/send/notify/root). Best-effort: on any
     * parse error the legacy key is left untouched.
     */
    private fun migrate(ctx: Context) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_LEGACY_RULES) || prefs.contains(KEY_PROFILES)) return
        try {
            val raw = prefs.getString(KEY_LEGACY_RULES, null) ?: return
            val arr = JSONArray(raw)
            val profs = ArrayList<Profile>()
            val tasks = ArrayList<Task>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val contexts = ArrayList<Ctx>()
                val ctxArr = o.optJSONArray("ctx")
                if (ctxArr != null) {
                    for (j in 0 until ctxArr.length()) {
                        Ctx.fromJson(ctxArr.optJSONObject(j))?.let { contexts.add(it) }
                    }
                }
                if (contexts.none { it is EventCtx }) {
                    val legacy = o.optString("event", "")
                    if (legacy.isNotBlank()) contexts.add(0, EventCtx(legacy, o.optString("filter", "")))
                }
                val acts = ArrayList<Action>()
                val sh = o.optString("shell", "")
                val tn = o.optString("task", "")
                val sa = o.optString("send", "")
                val nt = o.optBoolean("notify", false)
                val nc = o.optString("notext", "")
                val rc = o.optString("root", "")
                if (sh.isNotBlank()) acts.add(Action(Actions.SHELL, sh))
                if (tn.isNotBlank()) acts.add(Action(Actions.SCRIPT, tn))
                if (sa.isNotBlank()) acts.add(Action(Actions.INTENT, sa, o.optString("sendExtras", ""), o.optString("sendPkg", "")))
                if (nt) acts.add(Action(Actions.NOTIFY, nc))
                if (rc.isNotBlank()) acts.add(Action(Actions.ROOT, rc))
                val taskId = if (acts.isEmpty()) "" else {
                    val t = Task(
                        id = "tk_" + o.optString("id", "legacy"),
                        name = o.optString("label", "TASK"),
                        actions = acts,
                        retries = o.optInt("retries", 0)
                    )
                    tasks.add(t)
                    t.id
                }
                profs.add(
                    Profile(
                        id = o.optString("id", "p_" + Math.random().toString().take(8)),
                        name = o.optString("label", o.optString("event", "PROFILE")),
                        enabled = o.optBoolean("enabled", true),
                        priority = o.optInt("prio", 5),
                        cooldownSec = o.optLong("cooldown", 0L),
                        contexts = contexts,
                        taskId = taskId,
                        atEpoch = o.optLong("at", 0L),
                        daily = o.optString("daily", "")
                    )
                )
            }
            val ed = prefs.edit()
            ed.putString(KEY_PROFILES, JSONArray().apply { profs.forEach { put(it.toJson()) } }.toString())
            ed.putString(KEY_TASKS, JSONArray().apply { tasks.forEach { put(it.toJson()) } }.toString())
            ed.remove(KEY_LEGACY_RULES)
            ed.apply()
        } catch (e: Exception) {
            // keep legacy key; next launch retries migration
        }
    }
}
