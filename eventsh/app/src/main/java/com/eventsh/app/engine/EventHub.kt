package com.eventsh.app.engine

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.provider.Telephony
import android.telephony.TelephonyManager
import java.util.concurrent.ConcurrentHashMap

object EventHub {
    private var context: Context? = null
    private val receiver = Receiver()
    private val lastFire = ConcurrentHashMap<String, Long>()
    private var regIntent = IntentFilter()
    private val standard = mutableSetOf(
        Intent.ACTION_SCREEN_ON,
        Intent.ACTION_SCREEN_OFF,
        Intent.ACTION_USER_PRESENT,
        Intent.ACTION_POWER_CONNECTED,
        Intent.ACTION_POWER_DISCONNECTED,
        Intent.ACTION_BATTERY_LOW,
        Intent.ACTION_BATTERY_OKAY,
        Intent.ACTION_AIRPLANE_MODE_CHANGED,
        WifiManager.NETWORK_STATE_CHANGED_ACTION,
        WifiManager.WIFI_STATE_CHANGED_ACTION,
        Intent.ACTION_HEADSET_PLUG,
        TelephonyManager.ACTION_PHONE_STATE_CHANGED,
        "android.provider.Telephony.SMS_RECEIVED",
        BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED,
        Intent.ACTION_TIME_TICK
    )

    private fun syncCustomActions(ctx: Context) {
        // watcher-driven (file/music) + synthetic (var/app/timer) events are not broadcasts
        val watcherOnly = setOf(
            "file_modified", "file_opened", "file_closed",
            "file_deleted", "file_moved", "file_attr", "music_track"
        )
        val custom = Store.cachedProfiles(ctx)
            .flatMap { it.eventActions }
            .filter { it.isNotBlank() }
            .filterNot { standard.contains(it) }
            .filterNot { it in watcherOnly }
            .toSet()
        synchronized(this) {
            try { ctx.unregisterReceiver(receiver) } catch (e: Exception) {}
            regIntent = IntentFilter()
            standard.forEach { regIntent.addAction(it) }
            custom.forEach { regIntent.addAction(it) }
            if (Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(receiver, regIntent, Context.RECEIVER_EXPORTED)
            } else {
                ctx.registerReceiver(receiver, regIntent)
            }
        }
        EventLog.push("[hub] receiver registered (${standard.size + custom.size} actions)")
    }

    fun register(ctx: Context) {
        context = ctx.applicationContext
        syncCustomActions(context!!)
        Watchers.resync(context!!)
    }

    /** Re-register after rule edits so new custom event actions take effect. */
    fun resync(ctx: Context) {
        val c = ctx.applicationContext
        context = c
        syncCustomActions(c)
        Watchers.resync(c)
    }

    fun unregister(ctx: Context) {
        try {
            ctx.unregisterReceiver(receiver)
        } catch (e: Exception) {
        }
        context = null
    }

    fun dispatch(event: String, data: Map<String, String>) {
        val ctx = context ?: return
        fireDirect(ctx, event, data)
    }

    fun fireDirect(ctx: Context, event: String, data: Map<String, String>) {
        val profiles = Store.cachedProfiles(ctx).filter { p ->
            p.enabled && (
                p.hasEvent(event) ||
                    // app-only / var-only / location-only profiles: driven by synthetic state events
                    (event == "app.state" && p.eventActions.isEmpty() && p.timeCtx == null && p.appCtx != null) ||
                    (event == "var.state" && p.eventActions.isEmpty() && p.timeCtx == null && p.varCtx != null) ||
                    (event == "location.state" && p.eventActions.isEmpty() && p.timeCtx == null && p.locationCtx != null)
                )
        }
        if (profiles.isEmpty()) return
        profiles.sortedByDescending { it.eventContext?.priority ?: it.priority }
            .forEach { fireRule(ctx, it, event, data) }
    }

    /**
     * Fires a single profile for an event, applying cooldown, the event
     * filter, and the remaining context gates (time/day/var/app).
     */
    fun fireRule(ctx: Context, profile: Profile, event: String, data: Map<String, String>) {
        if (!profile.enabled) return
        val now = System.currentTimeMillis()
        val last = lastFire[profile.id] ?: 0L
        val waitMs = profile.cooldownSec * 1000L
        if (now - last < waitMs) return
        if (!passesFilter(profile, event, data)) return
        if (!ContextGate.check(ctx, profile, data)) return
        lastFire[profile.id] = now
        Dispatcher.fire(ctx, profile, event, data)
    }

    private fun passesFilter(p: Profile, event: String, data: Map<String, String>): Boolean {
        // per-parameter filters, each matched against its own data key
        val ev = p.contexts.filterIsInstance<EventCtx>().firstOrNull { it.action == event }
        ev?.params?.forEach { (key, pat) ->
            val v = data[key]
            if (v == null) return false
            val num = pat.toLongOrNull()
            val dv = v.toLongOrNull()
            if (key == "value" && num != null && dv != null) {
                when (event) {
                    "ram_pct" -> if (dv < num) return false
                    "disk_free" -> if (dv > num) return false
                    else -> if (!summaryMatches(pat, v)) return false
                }
            } else {
                if (!summaryMatches(pat, v)) return false
            }
        }
        // legacy single summary filter (back-compat with old rules)
        val filter = ev?.filter
        if (filter.isNullOrBlank()) return true
        val num = filter.toLongOrNull()
        val value = data["value"]?.toLongOrNull()
        if (num != null && value != null) {
            return when (event) {
                "ram_pct" -> value >= num
                "disk_free" -> value <= num
                else -> summaryMatches(filter, data["summary"] ?: "")
            }
        }
        return summaryMatches(filter, data["summary"] ?: "")
    }

    /** `*`/`+`/`/`/`!` -> pattern match; otherwise case-insensitive substring (legacy behavior). */
    private fun summaryMatches(filter: String, summary: String): Boolean {
        return if (filter.any { it == '*' || it == '+' || it == '/' || it == '!' }) {
            ContextGate.matchPattern(filter, summary)
        } else {
            summary.contains(filter, true)
        }
    }

    fun batteryNow(ctx: Context): Int {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (level < 0 || scale <= 0) return -1
        return level * 100 / scale
    }

    /** Sanitizes an intent extra key into a usable variable name. */
    private fun varName(k: String): String {
        val sb = StringBuilder()
        for (c in k) sb.append(if (c.isLetterOrDigit()) c else '_')
        var s = sb.toString()
        if (s.isEmpty()) s = "extra"
        if (!s.first().isLetter()) s = "a$s"
        return s
    }

    class Receiver : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            try {
                when (intent.action) {
                    Intent.ACTION_SCREEN_ON -> dispatch("screen_on", mapOf("summary" to "screen on"))
                    Intent.ACTION_SCREEN_OFF -> dispatch("screen_off", mapOf("summary" to "screen off"))
                    Intent.ACTION_USER_PRESENT -> dispatch("user_present", mapOf("summary" to "unlocked"))
                    Intent.ACTION_POWER_CONNECTED -> dispatch("charger_plug", mapOf("summary" to "power connected"))
                    Intent.ACTION_POWER_DISCONNECTED -> dispatch("charger_unplug", mapOf("summary" to "power disconnected"))
                    Intent.ACTION_BATTERY_LOW -> dispatch("battery_low", mapOf("summary" to "battery low"))
                    Intent.ACTION_BATTERY_OKAY -> dispatch("battery_full", mapOf("summary" to "battery ok"))
                    Intent.ACTION_TIME_TICK -> dispatch("time_tick", mapOf("summary" to "tick"))
                    Intent.ACTION_AIRPLANE_MODE_CHANGED -> {
                        val on = intent.getBooleanExtra("state", false)
                        dispatch(if (on) "airplane_on" else "airplane_off", mapOf("summary" to if (on) "airplane on" else "airplane off"))
                    }
                    WifiManager.NETWORK_STATE_CHANGED_ACTION -> {
                        val ni = intent.getParcelableExtra<android.net.NetworkInfo>(WifiManager.EXTRA_NETWORK_INFO)
                        val connected = ni?.isConnected == true
                        val ssid = WifiState.ssid(ctx)
                        if (connected) dispatch("wifi_conn", mapOf("summary" to (ssid ?: "wifi"), "ssid" to (ssid ?: "")))
                        else dispatch("wifi_disconn", mapOf("summary" to "wifi lost"))
                    }
                    WifiManager.WIFI_STATE_CHANGED_ACTION -> {
                        val s = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, -1)
                        if (s == WifiManager.WIFI_STATE_ENABLED) dispatch("wifi_on", mapOf("summary" to "wifi enabled"))
                    }
                    Intent.ACTION_HEADSET_PLUG -> {
                        val plugged = intent.getIntExtra("state", 0) == 1
                        if (plugged) dispatch("headset_plug", mapOf("summary" to "headset plug"))
                        else dispatch("headset_unplug", mapOf("summary" to "headset unplug"))
                    }
                    TelephonyManager.ACTION_PHONE_STATE_CHANGED -> {
                        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
                        when (state) {
                            TelephonyManager.EXTRA_STATE_RINGING -> dispatch("call_in", mapOf("summary" to "ringing"))
                            TelephonyManager.EXTRA_STATE_IDLE -> dispatch("call_end", mapOf("summary" to "idle"))
                        }
                    }
                    "android.provider.Telephony.SMS_RECEIVED" -> {
                        val sms = Telephony.Sms.Intents.getMessagesFromIntent(intent).firstOrNull()
                        val from = sms?.getOriginatingAddress() ?: "?"
                        val body = sms?.displayMessageBody ?: ""
                        dispatch("sms", mapOf("summary" to "SMS from $from", "from" to from, "body" to body))
                    }
                    BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED -> {
                        val state = intent.getIntExtra(BluetoothAdapter.EXTRA_CONNECTION_STATE, -1)
                        if (state == BluetoothAdapter.STATE_CONNECTED)
                            dispatch("bt_conn", mapOf("summary" to "bt connected"))
                        else if (state == BluetoothAdapter.STATE_DISCONNECTED)
                            dispatch("bt_disconn", mapOf("summary" to "bt disconnected"))
                    }
                    else -> {
                        // fully custom event: rule.event = any broadcast action string
                        val act = intent.action
                        if (act != null) {
                            val data = HashMap<String, String>()
                            val sb = StringBuilder()
                            intent.extras?.keySet()?.forEach { k ->
                                val v = intent.extras?.get(k)
                                if (v != null) {
                                    sb.append(" $k=$v")
                                    data[varName(k)] = v.toString()
                                }
                            }
                            data["summary"] = sb.toString().trim()
                            dispatch(act, data)
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("EVENTSH", "receiver error", e)
            }
        }
    }
}

object WifiState {
    fun ssid(ctx: Context): String? = try {
        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE)
            as android.net.wifi.WifiManager
        wifi.connectionInfo?.ssid?.trim()?.removePrefix("\"")?.removeSuffix("\"")
    } catch (e: Exception) {
        null
    }
}
