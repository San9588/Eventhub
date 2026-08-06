package com.eventsh.app.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.PowerManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Vars {
    /** Builds the global variable map attached to every trigger. */
    fun all(ctx: Context, event: String, data: Map<String, String>): LinkedHashMap<String, String> {
        val m = LinkedHashMap<String, String>()
        // user-defined vars first (built-ins win on same name)
        for ((k, v) in UserVars.all(ctx)) m[k] = v
        m["EVENT"] = event
        m["SUMMARY"] = data["summary"] ?: ""
        for ((k, v) in data) if (k != "summary") m[k.uppercase()] = v
        val now = System.currentTimeMillis()
        m["TIME"] = SimpleDateFormat("HH:mm", Locale.US).format(Date(now))
        m["DATE"] = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(now))
        m["BATTERY"] = EventHub.batteryNow(ctx).toString()
        val (ramMb, ramPct) = SysStats.mem()
        m["RAM"] = ramMb.toString()
        m["RAM_PCT"] = ramPct.toString()
        m["DISK_FREE"] = SysStats.diskFreeMb().toString()
        m["WIFI"] = if (wifiConnected(ctx)) "ON" else "OFF"
        m["SCREEN"] = if ((ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive) "ON" else "OFF"
        m["AIRPLANE"] = airplaneMode(ctx)
        m["NET"] = netConnected(ctx)
        m["ROOT"] = if (RootBridge.available == true) "ON" else "OFF"
        return m
    }

    fun resolve(template: String, vars: Map<String, String>): String {
        var s = template
        for ((k, v) in vars) s = s.replace("%$k%", v)
        return s
    }

    private fun wifiConnected(ctx: Context): Boolean {
        return try {
            if (android.os.Build.VERSION.SDK_INT < 23) false
            else {
                val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val caps = cm.getNetworkCapabilities(cm.activeNetwork)
                caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun netConnected(ctx: Context): String {
        return try {
            if (android.os.Build.VERSION.SDK_INT < 23) "?"
            else {
                val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val caps = cm.getNetworkCapabilities(cm.activeNetwork)
                if (caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) "ON" else "OFF"
            }
        } catch (e: Exception) {
            "?"
        }
    }

    private fun airplaneMode(ctx: Context): String {
        return try {
            if (android.provider.Settings.Global.getInt(ctx.contentResolver, "airplane_mode_on", 0) == 1) "ON" else "OFF"
        } catch (e: Exception) {
            "?"
        }
    }
}
