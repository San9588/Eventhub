package com.eventsh.app.engine

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
    val daily: String = ""
) {
    val isOneShotTimer: Boolean get() = atEpoch > 0
    val isDailyTimer: Boolean get() = daily.isNotBlank()

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
    }

    companion object {
        fun fromJson(o: JSONObject): Rule = Rule(
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
            daily = o.optString("daily", "")
        )
    }
}
