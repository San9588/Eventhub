package com.eventsh.app.engine

import android.content.Context
import org.json.JSONArray

object RuleStore {
    private const val PREFS = "eventsh"
    private const val KEY_RULES = "rules"
    private const val KEY_AUTOSTART = "autostart"

    fun defaults(): List<Rule> = listOf(
        Rule("screen_on", "screen_on", "SCREEN.ON", true, 0, 0, "scron"),
        Rule("screen_off", "screen_off", "SCREEN.OFF", true, 0, 2000, "scroff"),
        Rule("boot", "boot", "BOOT.COMPLETE", true, 0, 0, "boot"),
        Rule("charger_plug", "charger_plug", "CHARGER.PLUG", true, 0, 0, "charger_on"),
        Rule("charger_unplug", "charger_unplug", "CHARGER.UNPLUG", false, 0, 0, ""),
        Rule("battery_low", "battery_low", "BATTERY.LOW", false, 0, 0, ""),
        Rule("battery_full", "battery_full", "BATTERY.FULL", false, 0, 0, ""),
        Rule("wifi_conn", "wifi_conn", "WIFI.CONN", true, 0, 0, "wifi_on"),
        Rule("wifi_disconn", "wifi_disconn", "WIFI.DISCONN", false, 0, 0, ""),
        Rule("airplane_on", "airplane_on", "AIRPLANE.ON", false, 0, 0, ""),
        Rule("airplane_off", "airplane_off", "AIRPLANE.OFF", false, 0, 0, ""),
        Rule("headset_plug", "headset_plug", "HEADSET.PLUG", false, 0, 0, ""),
        Rule("headset_unplug", "headset_unplug", "HEADSET.UNPLUG", false, 0, 0, ""),
        Rule("sms", "sms", "SMS.RECV", false, 0, 0, ""),
        Rule("call_in", "call_in", "CALL.IN", false, 0, 0, ""),
        Rule("call_end", "call_end", "CALL.END", false, 0, 0, ""),
        Rule("app_install", "app_install", "APP.INSTALL", false, 0, 0, ""),
        Rule("app_remove", "app_remove", "APP.REMOVE", false, 0, 0, ""),
        Rule("app_update", "app_update", "APP.UPDATE", false, 0, 0, ""),
        Rule("time_set", "time_set", "TIME.SET", false, 0, 0, ""),
        Rule("tz_change", "tz_change", "TZ.CHANGE", false, 0, 0, ""),
        Rule("bt_conn", "bt_conn", "BT.CONN", false, 0, 0, ""),
        Rule("bt_disconn", "bt_disconn", "BT.DISCONN", false, 0, 0, ""),
        Rule("notify_post", "notify_post", "NOTIFY.POST", false, 0, 0, ""),
        Rule("fg_app", "fg_app", "FG.APP", false, 0, 0, "", filter = ""),
        Rule("app_open", "app_open", "APP.OPEN", false, 0, 0, "", filter = ""),
        Rule("app_close", "app_close", "APP.CLOSE", false, 0, 0, "", filter = ""),
        Rule("ram_pct", "ram_pct", "RAM.PCT", false, 60, 0, "", filter = "80"),
        Rule("disk_free", "disk_free", "DISK.FREE", false, 60, 0, "", filter = "500"),
        Rule("shell_event", "shell_event", "SHELL.EVENT", false, 0, 0, ""),
        Rule("time_tick", "time_tick", "TIME.TICK", false, 0, 0, "")
    )

    fun load(ctx: Context): List<Rule> {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_RULES, null) ?: return defaults()
        return try {
            val arr = JSONArray(raw)
            val list = ArrayList<Rule>(arr.length())
            for (i in 0 until arr.length()) list.add(Rule.fromJson(arr.getJSONObject(i)))
            list
        } catch (e: Exception) {
            defaults()
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
