package com.eventsh.app.engine

import org.json.JSONObject

/**
 * A single step inside a Task, Tasker "Action" style.
 * [value] and [extra]/[extra2] are task-specific per [type].
 */
data class Action(
    val type: String,
    val value: String = "",
    val extra: String = "",
    val extra2: String = ""
) {
    fun label(): String = when (type) {
        Actions.SCRIPT -> "Script"
        Actions.SHELL -> "Shell"
        Actions.INTENT -> "Send Intent"
        Actions.NOTIFY -> "Notify"
        Actions.ROOT -> "Root"
        else -> type
    }

    fun summary(): String = when (type) {
        Actions.SCRIPT -> "Run ${value.ifBlank { "(no task)" }}"
        Actions.SHELL -> "sh -c ${value.ifBlank { "(empty)" }}"
        Actions.INTENT -> "Broadcast $value" + (if (extra.isBlank()) "" else "  |  $extra")
        Actions.NOTIFY -> value.ifBlank { "on fire" }
        Actions.ROOT -> value.ifBlank { "(empty)" }
        else -> value
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("type", type)
        put("value", value)
        if (extra.isNotBlank()) put("extra", extra)
        if (extra2.isNotBlank()) put("extra2", extra2)
    }

    companion object {
        fun fromJson(o: JSONObject): Action =
            Action(
                o.optString("type"),
                o.optString("value"),
                o.optString("extra"),
                o.optString("extra2")
            )
    }
}

object Actions {
    const val SCRIPT = "script"
    const val SHELL = "shell"
    const val INTENT = "intent"
    const val NOTIFY = "notify"
    const val ROOT = "root"

    val ALL = listOf(SCRIPT, SHELL, INTENT, NOTIFY, ROOT)
}
