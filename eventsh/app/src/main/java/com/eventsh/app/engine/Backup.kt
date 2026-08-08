package com.eventsh.app.engine

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Versioned, extensible backup of all app data (profiles, tasks, variables).
 *
 * The file format is a single JSON object:
 *   {
 *     "format": "eventsh-backup",
 *     "version": 1,
 *     "exportedAt": 1730000000000,
 *     "sections": {
 *       "profiles": [...],
 *       "tasks": [...],
 *       "vars": [...]
 *     }
 *   }
 *
 * HOW TO ADD A NEW FEATURE TO BACKUP
 * ----------------------------------
 * When you introduce a NEW persisted collection that should survive a restore
 * (events, sensors, plugin configs, alarms, ...):
 *   1. add ONE entry to [sections] below,
 *   2. give it a unique [Section.key],
 *   3. [Section.export]: return the collection as a JSONArray,
 *   4. [Section.apply]: parse the array and write it back to the store.
 * Nothing else needs to change - export/import pick up new sections
 * automatically. Sections missing from an old file (or unknown in a NEWER
 * app) are skipped, so backups stay readable across versions.
 */
object Backup {
    const val FORMAT_NAME = "eventsh-backup"
    const val VERSION = 1

    enum class Mode { REPLACE, MERGE }

    /** One backupable data collection. */
    class Section(
        val key: String,
        val export: (Context) -> JSONArray,
        val apply: (Context, JSONArray) -> Unit
    )

    private val sections: List<Section> = listOf(
        Section(
            key = "profiles",
            export = { ctx ->
                JSONArray().apply { Store.profiles(ctx).forEach { put(it.toJson()) } }
            },
            apply = { ctx, arr ->
                val list = ArrayList<Profile>()
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { list.add(Profile.fromJson(it)) }
                }
                Store.saveProfiles(ctx, list)
            }
        ),
        Section(
            key = "tasks",
            export = { ctx ->
                JSONArray().apply { Store.tasks(ctx).forEach { put(it.toJson()) } }
            },
            apply = { ctx, arr ->
                val list = ArrayList<Task>()
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { list.add(Task.fromJson(it)) }
                }
                Store.saveTasks(ctx, list)
            }
        ),
        Section(
            key = "vars",
            export = { ctx ->
                JSONArray().apply {
                    UserVars.diskEntries(ctx).forEach { (n, v) ->
                        put(JSONObject().put("n", n).put("v", v))
                    }
                }
            },
            apply = { ctx, arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val n = o.optString("n")
                    if (n.isNotBlank()) UserVars.set(ctx, n, o.optString("v"))
                }
            }
        )
    )

    fun export(ctx: Context): JSONObject = JSONObject()
        .put("format", FORMAT_NAME)
        .put("version", VERSION)
        .put("exportedAt", System.currentTimeMillis())
        .put("sections", JSONObject().apply {
            sections.forEach { s -> put(s.key, s.export(ctx)) }
        })

    /** Parses raw JSON; null when it is not an eventsh backup. */
    fun parse(raw: String): JSONObject? = try {
        val o = JSONObject(raw)
        if (o.optString("format") == FORMAT_NAME) {
            o
        } else if (o.has("sections") || o.has("profiles") || o.has("tasks") || o.has("vars")) {
            // legacy pre-v1 shape: { "profiles": [...], "tasks": [...] }
            JSONObject()
                .put("format", FORMAT_NAME)
                .put("version", 0)
                .put("sections", JSONObject()
                    .put("profiles", o.optJSONArray("profiles") ?: JSONArray())
                    .put("tasks", o.optJSONArray("tasks") ?: JSONArray())
                    .put("vars", o.optJSONArray("vars") ?: JSONArray()))
        } else {
            null
        }
    } catch (e: Exception) {
        null
    }

    /** True when [o] was written by a newer app than this one. */
    fun isNewer(o: JSONObject): Boolean = o.optInt("version", 1) > VERSION

    /**
     * Writes every section of [o] into the app store and re-arms timers /
     * alarms. [REPLACE] overwrites profiles + tasks and clears persisted
     * variables first. [MERGE] appends profiles/tasks with fresh ids (re-linking
     * profile -> task references) and overwrites variables by name.
     */
    fun apply(ctx: Context, o: JSONObject, mode: Mode) {
        val secs = o.optJSONObject("sections") ?: return
        if (mode == Mode.REPLACE) {
            // profiles + tasks are fully overwritten by their Section.apply;
            // clear persisted variables so removed vars don't survive a restore
            UserVars.diskEntries(ctx).forEach { (n, _) -> UserVars.remove(ctx, n) }
            sections.forEach { s -> secs.optJSONArray(s.key)?.let { s.apply(ctx, it) } }
        } else {
            mergeProfilesAndTasks(ctx, secs)
            secs.optJSONArray("vars")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val v = arr.optJSONObject(i) ?: continue
                    val n = v.optString("n")
                    if (n.isNotBlank()) UserVars.set(ctx, n, v.optString("v"))
                }
            }
        }
        Store.invalidate()
        Scheduler.rescheduleAll(ctx)
        AlarmEngine.rescheduleAll(ctx)
    }

    /** Appends imported profiles/tasks, remapping task ids so links survive. */
    private fun mergeProfilesAndTasks(ctx: Context, secs: JSONObject) {
        val idMap = HashMap<String, String>()
        val tasks = Store.tasks(ctx).toMutableList()
        secs.optJSONArray("tasks")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val t = Task.fromJson(o)
                val fresh = "tk_" + java.util.UUID.randomUUID().toString().take(8)
                idMap[t.id] = fresh
                tasks.add(t.copy(id = fresh))
            }
        }
        Store.saveTasks(ctx, tasks)

        val profiles = Store.profiles(ctx).toMutableList()
        secs.optJSONArray("profiles")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val p = Profile.fromJson(o)
                val fresh = "p_" + java.util.UUID.randomUUID().toString().take(8)
                val relinked = if (p.taskId.isBlank()) p.copy(id = fresh)
                else p.copy(id = fresh, taskId = idMap[p.taskId] ?: p.taskId)
                profiles.add(relinked)
            }
        }
        Store.saveProfiles(ctx, profiles)
    }
}
