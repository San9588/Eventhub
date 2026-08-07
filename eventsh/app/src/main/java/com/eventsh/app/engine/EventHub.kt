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
        val custom = RuleStore.load(ctx)
            .map { it.event }
            .filter { it.isNotBlank() }
            .filterNot { standard.contains(it) }
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
    }

    /** Re-register after rule edits so new custom event actions take effect. */
    fun resync(ctx: Context) {
        val c = ctx.applicationContext
        context = c
        syncCustomActions(c)
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
        val rules = RuleStore.load(ctx).filter { r ->
            r.enabled && (
                r.event == event ||
                    // app-only / var-only rules: driven by synthetic state events
                    (event == "app.state" && r.event.isBlank() && r.timeCtx == null && r.appCtx != null) ||
                    (event == "var.state" && r.event.isBlank() && r.timeCtx == null && r.varCtx != null)
                )
        }
        if (rules.isEmpty()) return
        rules.sortedByDescending { it.eventContext?.priority ?: 5 }
            .forEach { fireRule(ctx, it, event, data) }
    }

    /**
     * Fires a single rule for an event, applying cooldown/debounce, the event
     * filter, and the remaining context gates (time/day/var/app).
     */
    fun fireRule(ctx: Context, rule: Rule, event: String, data: Map<String, String>) {
        if (!rule.enabled) return
        val now = System.currentTimeMillis()
        val last = lastFire[rule.id] ?: 0L
        val waitMs = rule.cooldownSec * 1000L + rule.debounceMs
        if (now - last < waitMs) return
        if (!passesFilter(rule, data)) return
        if (!ContextGate.check(ctx, rule, data)) return
        lastFire[rule.id] = now
        Dispatcher.fire(ctx, rule, event, data)
    }

    private fun passesFilter(r: Rule, data: Map<String, String>): Boolean {
        if (r.filter.isBlank()) return true
        val num = r.filter.toLongOrNull()
        val value = data["value"]?.toLongOrNull()
        if (num != null && value != null) {
            return when (r.event) {
                "ram_pct" -> value >= num
                "disk_free" -> value <= num
                else -> data["summary"]?.contains(r.filter, true) == true
            }
        }
        return data["summary"]?.contains(r.filter, true) == true
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
                        val from = Telephony.Sms.Intents.getMessagesFromIntent(intent)
                            .firstOrNull()?.getOriginatingAddress() ?: "?"
                        dispatch("sms", mapOf("summary" to "SMS from $from", "from" to from))
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
