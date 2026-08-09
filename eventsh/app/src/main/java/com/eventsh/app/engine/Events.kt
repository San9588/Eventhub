package com.eventsh.app.engine

object EventCatalog {
    val STANDARD: List<String> = listOf(
        "screen_on",
        "screen_off",
        "user_present",
        "charger_plug",
        "charger_unplug",
        "battery_low",
        "battery_full",
        "wifi_conn",
        "wifi_disconn",
        "wifi_on",
        "airplane_on",
        "airplane_off",
        "headset_plug",
        "headset_unplug",
        "call_in",
        "call_end",
        "sms",
        "bt_conn",
        "bt_disconn",
        "boot",
        "time_tick",
        "time_set",
        "tz_change",
        "app_install",
        "app_remove",
        "app_update",
        "fg_app",
        "app_open",
        "app_close",
        "notify_post",
        "ram_pct",
        "disk_free",
        "file_modified",
        "file_opened",
        "file_closed",
        "file_deleted",
        "file_moved",
        "file_attr",
        "music_track",
        "location.state",
        "timer.one",
        "timer.daily",
        "shell_event"
    )

    /**
     * Filterable per-event parameters, Tasker-style Event Edit.
     * key -> label shown in the event dialog. Values are pattern matches.
     * "value" on ram_pct/disk_free is numeric (min % / max MB).
     */
    val PARAMS: Map<String, List<Pair<String, String>>> = mapOf(
        "sms" to listOf("from" to "From", "body" to "Content"),
        "notify_post" to listOf("pkg" to "Package", "title" to "Title"),
        "wifi_conn" to listOf("ssid" to "SSID"),
        "app_open" to listOf("pkg" to "Package"),
        "app_close" to listOf("pkg" to "Package"),
        "fg_app" to listOf("pkg" to "Package"),
        "app_install" to listOf("pkg" to "Package"),
        "app_remove" to listOf("pkg" to "Package"),
        "app_update" to listOf("pkg" to "Package"),
        "file_modified" to listOf("path" to "File / folder path"),
        "file_opened" to listOf("path" to "File / folder path"),
        "file_closed" to listOf("path" to "File / folder path"),
        "file_deleted" to listOf("path" to "File / folder path"),
        "file_moved" to listOf("path" to "File / folder path"),
        "file_attr" to listOf("path" to "File / folder path"),
        "music_track" to listOf("title" to "Title"),
        "var.state" to listOf("name" to "Name", "value" to "Value"),
        "ram_pct" to listOf("value" to "Min %"),
        "disk_free" to listOf("value" to "Max MB")
    )
}
