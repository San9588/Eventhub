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
    fun label(): String = Actions.label(type)

    fun summary(): String = when (type) {
        Actions.SCRIPT -> "Run ${value.ifBlank { "(no task)" }}"
        Actions.SHELL -> "sh -c ${value.ifBlank { "(empty)" }}"
        Actions.INTENT -> "Broadcast $value" + (if (extra.isBlank()) "" else "  |  $extra")
        Actions.NOTIFY -> value.ifBlank { "on fire" }
        Actions.ROOT -> value.ifBlank { "(empty)" }
        Actions.VAR_SET ->
            "Set %$value = ${extra.ifBlank { "(value)" }}" +
                (if (extra2.equals("append", true)) "  (append)" else "")
        Actions.VAR_SPLIT -> "Split %$value by '${extra.ifBlank { "," }}'"
        Actions.VAR_JOIN -> "Join %${value}1.." + (if (extra.isBlank()) "" else "  joiner: $extra")
        Actions.VAR_QUERY -> "Query %$value" + (if (extra.isBlank()) "" else "  -> %$extra")
        Actions.IF -> "If ${value.ifBlank { "(condition)" }}"
        Actions.ELSE -> "Else"
        Actions.END_IF -> "End If"
        Actions.FOR -> "For %${extra.ifBlank { "loop" }} in ${value.ifBlank { "(values)" }}"
        Actions.END_FOR -> "End For"
        Actions.WIFI_ON -> "Wifi On"
        Actions.WIFI_OFF -> "Wifi Off"
        Actions.BT_ON -> "Bluetooth On"
        Actions.BT_OFF -> "Bluetooth Off"
        Actions.DATA_ON -> "Mobile Data On"
        Actions.DATA_OFF -> "Mobile Data Off"
        Actions.DISPLAY_ON -> "Display On"
        Actions.DISPLAY_OFF -> "Display Off"
        Actions.ROTATE_ON -> "Auto-Rotate On"
        Actions.ROTATE_OFF -> "Auto-Rotate Off"
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

    const val VAR_SET = "var_set"
    const val VAR_SPLIT = "var_split"
    const val VAR_JOIN = "var_join"
    const val VAR_QUERY = "var_query"

    const val IF = "if"
    const val ELSE = "else"
    const val END_IF = "end_if"
    const val FOR = "for"
    const val END_FOR = "end_for"

    const val WIFI_ON = "wifi_on"
    const val WIFI_OFF = "wifi_off"
    const val BT_ON = "bt_on"
    const val BT_OFF = "bt_off"
    const val DATA_ON = "data_on"
    const val DATA_OFF = "data_off"
    const val DISPLAY_ON = "display_on"
    const val DISPLAY_OFF = "display_off"
    const val ROTATE_ON = "rotate_on"
    const val ROTATE_OFF = "rotate_off"

    data class Def(val type: String, val label: String, val category: String)

    val CATALOG: List<Def> = listOf(
        Def(SCRIPT, "Termux Script", "TASKER"),
        Def(SHELL, "Shell Command", "TASKER"),
        Def(INTENT, "Send Broadcast", "TASKER"),
        Def(NOTIFY, "Notify", "TASKER"),
        Def(ROOT, "Root Command", "TASKER"),
        Def(VAR_SET, "Variable Set", "VARIABLE"),
        Def(VAR_SPLIT, "Variable Split", "VARIABLE"),
        Def(VAR_JOIN, "Variable Join", "VARIABLE"),
        Def(VAR_QUERY, "Variable Query", "VARIABLE"),
        Def(IF, "If", "FLOW"),
        Def(ELSE, "Else", "FLOW"),
        Def(END_IF, "End If", "FLOW"),
        Def(FOR, "For", "FLOW"),
        Def(END_FOR, "End For", "FLOW"),
        Def(WIFI_ON, "Wifi On", "SYSTEM"),
        Def(WIFI_OFF, "Wifi Off", "SYSTEM"),
        Def(BT_ON, "Bluetooth On", "SYSTEM"),
        Def(BT_OFF, "Bluetooth Off", "SYSTEM"),
        Def(DATA_ON, "Mobile Data On", "SYSTEM"),
        Def(DATA_OFF, "Mobile Data Off", "SYSTEM"),
        Def(DISPLAY_ON, "Display On", "SYSTEM"),
        Def(DISPLAY_OFF, "Display Off", "SYSTEM"),
        Def(ROTATE_ON, "Auto-Rotate On", "SYSTEM"),
        Def(ROTATE_OFF, "Auto-Rotate Off", "SYSTEM")
    )

    fun label(type: String): String =
        CATALOG.find { it.type == type }?.label ?: type

    /** Actions that take no editable parameters (pure control flow / toggles). */
    fun noParams(type: String): Boolean = when (type) {
        ELSE, END_IF, END_FOR,
        WIFI_ON, WIFI_OFF, BT_ON, BT_OFF, DATA_ON, DATA_OFF,
        DISPLAY_ON, DISPLAY_OFF, ROTATE_ON, ROTATE_OFF -> true
        else -> false
    }

    val ALL: List<String> get() = CATALOG.map { it.type }
}
