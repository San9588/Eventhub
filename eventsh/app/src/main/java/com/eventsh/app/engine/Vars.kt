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

    /**
     * Resolves %NAME% (and bare %NAME when followed by a non-word char) from
     * the variable map, longest names first so %FOO doesn't collide with %FOOBAR.
     * Tasker-style array selectors are handled first:
     *   %name(#)  -> element count
     *   %name(-n) -> nth element from the end (-1 = last)
     */
    fun resolve(template: String, vars: Map<String, String>): String {
        var s = template
        if (s.contains('(')) s = applyArraySelectors(s, vars)
        for ((k, v) in vars.entries.sortedByDescending { it.key.length }) {
            s = s.replace("%${k}%", v)
            s = s.replace(Regex("%${Regex.escape(k)}(?![A-Za-z0-9_])"), v)
        }
        return s
    }

    /** Resolves `%name(#)` (count) and `%name(-n)` (nth from end) selectors. */
    private fun applyArraySelectors(s: String, vars: Map<String, String>): String {
        val re = Regex("%([A-Za-z0-9_]+)\\((#|-\\d+)\\)")
        val matches = re.findAll(s).toList().sortedByDescending { it.value.length }
        var out = s
        for (m in matches) {
            val name = m.groupValues[1]
            val sel = m.groupValues[2]
            val els = arrayElements(name, vars)
            val rep = if (sel == "#") els.size.toString()
            else {
                val idx = sel.toInt()
                els.getOrNull(if (idx < 0) els.size + idx else idx - 1) ?: ""
            }
            out = out.replace(m.value, rep)
        }
        return out
    }

    /** 1-based array elements from the vars map, falling back to the comma-joined base. */
    private fun arrayElements(name: String, vars: Map<String, String>): List<String> {
        val out = mutableListOf<String>()
        var i = 1
        while (vars.containsKey(name + i)) {
            out.add(vars[name + i] ?: "")
            i++
        }
        if (out.isNotEmpty()) return out
        return (vars[name] ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }
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
