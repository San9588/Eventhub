package com.eventsh.app.engine

import org.json.JSONArray
import org.json.JSONObject

/**
 * A named, reusable set of [Action]s (Tasker "Task").
 * Profiles link to a Task via [Profile.taskId]; a Task can be shared by
 * many profiles and edited independently in the task editor.
 */
data class Task(
    val id: String,
    val name: String,
    val actions: List<Action> = emptyList(),
    val retries: Int = 0,
    val enabled: Boolean = true
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("retries", retries)
        put("enabled", enabled)
        val arr = JSONArray()
        actions.forEach { arr.put(it.toJson()) }
        put("actions", arr)
    }

    companion object {
        fun fromJson(o: JSONObject): Task {
            val actions = ArrayList<Action>()
            val arr = o.optJSONArray("actions")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { actions.add(Action.fromJson(it)) }
                }
            }
            return Task(
                id = o.optString("id", "tk_" + Math.random().toString().take(8)),
                name = o.optString("name", "TASK"),
                actions = actions,
                retries = o.optInt("retries", 0),
                enabled = o.optBoolean("enabled", true)
            )
        }
    }
}
