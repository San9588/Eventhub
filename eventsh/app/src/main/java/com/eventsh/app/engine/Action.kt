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
    val extra2: String = "",
    val cond: String = ""
) {
    /** Parsed If-guard terms + connectors, or null when the action has none. */
    fun condTerms(): Pair<List<CondTerm>, List<String>>? = CondSpec.parse(cond)
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
        Actions.STOP -> "Stop this task"
        Actions.TASK_RUN -> "Run task ${value.ifBlank { "(task)" }}"
        Actions.TASK_STOP -> "Stop task ${value.ifBlank { "(task)" }}"
        Actions.TASK_ENABLE -> "Enable task ${value.ifBlank { "(task)" }}"
        Actions.TASK_DISABLE -> "Disable task ${value.ifBlank { "(task)" }}"
        Actions.PROFILE_ENABLE -> "Enable profile ${value.ifBlank { "(profile)" }}"
        Actions.PROFILE_DISABLE -> "Disable profile ${value.ifBlank { "(profile)" }}"
        Actions.PROFILE_DELETE -> "Delete profile ${value.ifBlank { "(profile)" }}"
        else -> value
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("type", type)
        put("value", value)
        if (extra.isNotBlank()) put("extra", extra)
        if (extra2.isNotBlank()) put("extra2", extra2)
        if (cond.isNotBlank()) put("cond", cond)
    }

    companion object {
        fun fromJson(o: JSONObject): Action =
            Action(
                o.optString("type"),
                o.optString("value"),
                o.optString("extra"),
                o.optString("extra2"),
                o.optString("cond")
            )
    }
}

/** One term of an action-level If guard, e.g. `%san = true`. */
data class CondTerm(val variable: String, val op: String = "=", val value: String = "")

/** Serialization + evaluation for an action's If condition (terms + connectors). */
object CondSpec {
    /** Encodes [terms] + [joins] as a compact JSON string for [Action.cond]. */
    fun encode(terms: List<CondTerm>, joins: List<String>): String = try {
        val arr = org.json.JSONArray()
        for (t in terms) {
            arr.put(org.json.JSONObject().put("v", t.variable).put("o", t.op).put("val", t.value))
        }
        org.json.JSONObject().put("t", arr).put("j", org.json.JSONArray(joins)).toString()
    } catch (e: Exception) {
        ""
    }

    /** Parses [s] into terms + connectors; null when blank or malformed. */
    fun parse(s: String): Pair<List<CondTerm>, List<String>>? {
        if (s.isBlank()) return null
        return try {
            val o = org.json.JSONObject(s)
            val arr = o.optJSONArray("t") ?: return null
            val terms = ArrayList<CondTerm>()
            for (i in 0 until arr.length()) {
                val e = arr.optJSONObject(i) ?: continue
                val v = e.optString("v").trim()
                if (v.isBlank()) continue
                terms.add(CondTerm(v, e.optString("o", "="), e.optString("val")))
            }
            if (terms.isEmpty()) return null
            val joins = ArrayList<String>()
            val jArr = o.optJSONArray("j")
            if (jArr != null) for (i in 0 until jArr.length()) joins.add(jArr.optString(i))
            terms to joins
        } catch (e: Exception) {
            null
        }
    }

    /** `%san = true  AND  %batt > 50` style one-liner. */
    fun summary(terms: List<CondTerm>, joins: List<String>): String = buildString {
        terms.forEachIndexed { i, t ->
            if (i > 0) {
                append("  ")
                append((joins.getOrNull(i - 1) ?: "and").uppercase())
                append("  ")
            }
            append("%").append(t.variable)
            append(" ").append(t.op)
            if (t.value.isNotBlank()) append(" ").append(t.value)
        }
    }

    /**
     * True when every term matches, combined left-to-right with the connectors
     * (and/or/xor). A blank variable resolves to its value; a missing variable
     * is treated as empty string.
     */
    fun matches(
        ctx: android.content.Context,
        terms: List<CondTerm>,
        joins: List<String>,
        vars: Map<String, String>
    ): Boolean {
        if (terms.isEmpty()) return true
        var acc = eval(terms[0], ctx, vars)
        for (i in 1 until terms.size) {
            val r = eval(terms[i], ctx, vars)
            acc = when ((joins.getOrNull(i - 1) ?: "and").lowercase()) {
                "or" -> acc || r
                "xor" -> acc != r
                else -> acc && r
            }
        }
        return acc
    }

    private fun eval(t: CondTerm, ctx: android.content.Context, vars: Map<String, String>): Boolean {
        val name = t.variable.trim().removePrefix("%")
        val lhs = vars[name] ?: UserVars.get(ctx, name) ?: ""
        val rhs = Vars.resolve(t.value, vars)
        return when (t.op) {
            "=", "==" -> lhs == rhs
            "!=" -> lhs != rhs
            ">", ">=", "<", "<=" -> {
                val a = lhs.toDoubleOrNull()
                val b = rhs.toDoubleOrNull()
                if (a == null || b == null) false
                else when (t.op) {
                    ">" -> a > b
                    ">=" -> a >= b
                    "<" -> a < b
                    else -> a <= b
                }
            }
            "~" -> ContextGate.matchPattern(rhs, lhs)
            "!~" -> !ContextGate.matchPattern(rhs, lhs)
            else -> lhs.isNotBlank()
        }
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

    const val STOP = "stop"
    const val TASK_RUN = "task_run"
    const val TASK_STOP = "task_stop"
    const val TASK_ENABLE = "task_enable"
    const val TASK_DISABLE = "task_disable"
    const val PROFILE_ENABLE = "profile_enable"
    const val PROFILE_DISABLE = "profile_disable"
    const val PROFILE_DELETE = "profile_delete"

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
        Def(ROTATE_OFF, "Auto-Rotate Off", "SYSTEM"),
        Def(STOP, "Stop Task", "TASKER"),
        Def(TASK_RUN, "Perform Task", "TASK"),
        Def(TASK_STOP, "Task Stop", "TASK"),
        Def(TASK_ENABLE, "Task Enable", "TASK"),
        Def(TASK_DISABLE, "Task Disable", "TASK"),
        Def(PROFILE_ENABLE, "Profile Enable", "PROFILE"),
        Def(PROFILE_DISABLE, "Profile Disable", "PROFILE"),
        Def(PROFILE_DELETE, "Profile Delete", "PROFILE")
    )

    fun label(type: String): String =
        CATALOG.find { it.type == type }?.label ?: type

    /** Actions that take no editable parameters (pure control flow / toggles). */
    fun noParams(type: String): Boolean = when (type) {
        ELSE, END_IF, END_FOR,
        WIFI_ON, WIFI_OFF, BT_ON, BT_OFF, DATA_ON, DATA_OFF,
        DISPLAY_ON, DISPLAY_OFF, ROTATE_ON, ROTATE_OFF,
        STOP -> true
        else -> false
    }

    val ALL: List<String> get() = CATALOG.map { it.type }

    /** Control-flow markers that cannot carry a meaningful per-action If guard. */
    fun isFlow(type: String): Boolean =
        type == IF || type == ELSE || type == END_IF || type == FOR || type == END_FOR
}
