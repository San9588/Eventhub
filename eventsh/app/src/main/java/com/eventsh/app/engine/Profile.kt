package com.eventsh.app.engine

import org.json.JSONArray
import org.json.JSONObject

/**
 * A profile is a SET of contexts that link to a Task (Tasker "Profile").
 * When every context is satisfied the linked Task runs. Profiles carry no
 * actions themselves - those live in the linked Task.
 */
data class Profile(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val priority: Int = 5,
    val cooldownSec: Long = 0L,
    val contexts: List<Ctx> = emptyList(),
    val taskId: String = "",
    val atEpoch: Long = 0L,
    val daily: String = ""
) {
    val isOneShotTimer: Boolean get() = atEpoch > 0
    val isDailyTimer: Boolean get() = daily.isNotBlank()

    // Convenience accessors for the Tasker-style context blocks.
    val eventContext: EventCtx? get() = contexts.filterIsInstance<EventCtx>().firstOrNull()
    val timeCtx: TimeCtx? get() = contexts.filterIsInstance<TimeCtx>().firstOrNull()
    val dayCtx: DayCtx? get() = contexts.filterIsInstance<DayCtx>().firstOrNull()
    val varCtx: VarCtx? get() = contexts.filterIsInstance<VarCtx>().firstOrNull()
    val appCtx: AppCtx? get() = contexts.filterIsInstance<AppCtx>().firstOrNull()

    val hasTrigger: Boolean get() = eventActions.isNotEmpty() || timeCtx != null || appCtx != null || varCtx != null

    /** All broadcast event names this profile listens for (from every EventCtx). */
    val eventActions: List<String> get() = contexts.filterIsInstance<EventCtx>().map { it.action }

    /** True if any EventCtx listens for [name]. */
    fun hasEvent(name: String): Boolean = eventActions.contains(name)

    /** Human-readable single line describing the trigger context. */
    fun contextLine(): String {
        val events = contexts.filterIsInstance<EventCtx>()
        if (events.size > 1) return "${events.size} events"
        eventContext?.let { return it.summary() }
        timeCtx?.let { return it.summary() }
        contexts.firstOrNull()?.let { return it.summary() }
        return ""
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("enabled", enabled)
        put("prio", priority)
        put("cooldown", cooldownSec)
        put("taskId", taskId)
        put("at", atEpoch)
        put("daily", daily)
        val arr = JSONArray()
        contexts.forEach { arr.put(it.toJson()) }
        put("ctx", arr)
    }

    companion object {
        fun fromJson(o: JSONObject): Profile {
            val contexts = ArrayList<Ctx>()
            val arr = o.optJSONArray("ctx")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    Ctx.fromJson(arr.optJSONObject(i))?.let { contexts.add(it) }
                }
            }
            // migrate legacy rules: event/filter became the primary EventCtx
            if (contexts.none { it is EventCtx }) {
                val legacy = o.optString("event", "")
                if (legacy.isNotBlank()) contexts.add(0, EventCtx(legacy, o.optString("filter", "")))
            }
            return Profile(
                id = o.optString("id", "p_" + Math.random().toString().take(8)),
                name = o.optString("name", o.optString("label", "PROFILE")),
                enabled = o.optBoolean("enabled", true),
                priority = o.optInt("prio", 5),
                cooldownSec = o.optLong("cooldown", 0L),
                contexts = contexts,
                taskId = o.optString("taskId", ""),
                atEpoch = o.optLong("at", 0L),
                daily = o.optString("daily", "")
            )
        }
    }
}
