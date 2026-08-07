package com.eventsh.app.engine

import org.json.JSONArray
import org.json.JSONObject

data class Rule(
    val id: String,
    val event: String,
    val label: String,
    val enabled: Boolean = true,
    val cooldownSec: Long = 0L,
    val debounceMs: Long = 0L,
    val taskName: String = "",
    val notify: Boolean = true,
    val notifyText: String = "",
    val rootCmd: String = "",
    val filter: String = "",
    val retries: Int = 0,
    val atEpoch: Long = 0L,
    val daily: String = "",
    val contexts: List<Ctx> = emptyList()
) {
    val isOneShotTimer: Boolean get() = atEpoch > 0
    val isDailyTimer: Boolean get() = daily.isNotBlank()

    // Convenience accessors for the Tasker-style context blocks.
    val eventContext: EventCtx? get() = contexts.filterIsInstance<EventCtx>().firstOrNull()
    val timeCtx: TimeCtx? get() = contexts.filterIsInstance<TimeCtx>().firstOrNull()
    val dayCtx: DayCtx? get() = contexts.filterIsInstance<DayCtx>().firstOrNull()
    val varCtx: VarCtx? get() = contexts.filterIsInstance<VarCtx>().firstOrNull()
    val appCtx: AppCtx? get() = contexts.filterIsInstance<AppCtx>().firstOrNull()

    val hasTrigger: Boolean get() = event.isNotBlank() || timeCtx != null

    /** Human-readable single line describing the trigger context. */
    fun contextLine(): String {
        eventContext?.let { return it.summary() }
        timeCtx?.let { return it.summary() }
        contexts.firstOrNull()?.let { return it.summary() }
        return ""
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("event", event)
        put("label", label)
        put("enabled", enabled)
        put("cooldown", cooldownSec)
        put("debounce", debounceMs)
        put("task", taskName)
        put("notify", notify)
        put("notext", notifyText)
        put("root", rootCmd)
        put("filter", filter)
        put("retries", retries)
        put("at", atEpoch)
        put("daily", daily)
        val arr = JSONArray()
        contexts.forEach { arr.put(it.toJson()) }
        put("ctx", arr)
    }

    companion object {
        fun fromJson(o: JSONObject): Rule {
            val contexts = ArrayList<Ctx>()
            val arr = o.optJSONArray("ctx")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    Ctx.fromJson(arr.optJSONObject(i))?.let { contexts.add(it) }
                }
            }
            // migrate legacy rules: event/filter became the primary EventCtx
            if (contexts.none { it is EventCtx }) {
                val legacy = o.getString("event")
                if (legacy.isNotBlank()) contexts.add(0, EventCtx(legacy, o.optString("filter", "")))
            }
            return Rule(
                id = o.getString("id"),
                event = o.getString("event"),
                label = o.optString("label", o.getString("event")),
                enabled = o.optBoolean("enabled", true),
                cooldownSec = o.optLong("cooldown", 0L),
                debounceMs = o.optLong("debounce", 0L),
                taskName = o.optString("task", ""),
                notify = o.optBoolean("notify", true),
                notifyText = o.optString("notext", ""),
                rootCmd = o.optString("root", ""),
                filter = o.optString("filter", ""),
                retries = o.optInt("retries", 0),
                atEpoch = o.optLong("at", 0L),
                daily = o.optString("daily", ""),
                contexts = contexts
            )
        }
    }
}
