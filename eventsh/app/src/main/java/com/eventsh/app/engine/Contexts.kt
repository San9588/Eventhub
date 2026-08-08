package com.eventsh.app.engine

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * Tasker-style composable profile contexts.
 *
 * A rule/profile is a set of contexts. All contexts must be satisfied for
 * the rule to fire (AND semantics), matching Tasker's profile model:
 *  - EventCtx  : instantaneous trigger (broadcast / watcher event)
 *  - TimeCtx   : time point / range / repeat (From / To / Repeat)
 *  - DayCtx    : days of week and/or days of month
 *  - VarCtx    : user variable value condition (Variable Value state)
 *  - AppCtx    : app / foreground restriction (Application context)
 */
sealed class Ctx {
    abstract val type: String
    abstract fun summary(): String
    abstract fun toJson(): JSONObject

    companion object {
        const val EVENT = "event"
        const val TIME = "time"
        const val DAY = "day"
        const val VAR = "var"
        const val APP = "app"

        fun fromJson(o: JSONObject): Ctx? = when (o.optString("type")) {
            EVENT -> {
                val po = o.optJSONObject("params")
                val params = if (po == null) emptyMap() else run {
                    val m = mutableMapOf<String, String>()
                    val ks = po.keys()
                    while (ks.hasNext()) {
                        val k = ks.next()
                        m[k] = po.getString(k)
                    }
                    m
                }
                EventCtx(
                    action = o.optString("action"),
                    filter = o.optString("filter"),
                    params = params,
                    priority = o.optInt("prio", 5),
                    stopEvent = o.optBoolean("stop")
                )
            }
            TIME -> TimeCtx(
                from = o.optString("from"),
                to = o.optString("to"),
                repeatMin = o.optInt("repeat", 0)
            )
            DAY -> DayCtx(
                dow = ints(o, "dow"),
                dom = ints(o, "dom")
            )
            VAR -> VarCtx(
                name = o.optString("name"),
                value = o.optString("value"),
                invert = o.optBoolean("invert")
            )
            APP -> AppCtx(
                packages = strings(o, "pkgs"),
                foregroundOnly = o.optBoolean("fgonly", true),
                invert = o.optBoolean("invert")
            )
            else -> null
        }

        private fun ints(o: JSONObject, tag: String): List<Int> {
            val arr = o.optJSONArray(tag) ?: return emptyList()
            return (0 until arr.length()).map { arr.getInt(it) }
        }

        private fun strings(o: JSONObject, tag: String): List<String> {
            val arr = o.optJSONArray(tag) ?: return emptyList()
            return (0 until arr.length()).map { arr.getString(it) }
        }
    }
}

/** Instantaneous event trigger. `action` is a broadcast action / event name. */
data class EventCtx(
    val action: String,
    val filter: String = "",
    val params: Map<String, String> = emptyMap(),
    val priority: Int = 5,
    val stopEvent: Boolean = false
) : Ctx() {
    override val type get() = Ctx.EVENT
    override fun summary(): String {
        if (params.isNotEmpty()) {
            val p = params.entries.joinToString(" ") { "${it.key}=${it.value}" }
            return "$action  / $p"
        }
        return if (filter.isBlank()) action else "$action  / $filter"
    }
    override fun toJson() = JSONObject().apply {
        put("type", type)
        put("action", action)
        put("filter", filter)
        if (params.isNotEmpty()) {
            val po = JSONObject()
            params.forEach { (k, v) -> po.put(k, v) }
            put("params", po)
        }
        put("prio", priority)
        put("stop", stopEvent)
    }
}

/** Time context. Blank fields mean "unset" (Tasker: 00:00 / 23:59 defaults). */
data class TimeCtx(
    val from: String = "",
    val to: String = "",
    val repeatMin: Int = 0
) : Ctx() {
    override val type get() = Ctx.TIME

    val fromMin: Int get() = parseHm(from) ?: 0
    val toMin: Int get() = parseHm(to) ?: (23 * 60 + 59)

    val isPoint: Boolean get() = from.isNotBlank() && (to.isBlank() || to == from)

    override fun summary(): String = when {
        repeatMin > 0 -> "${display(from)}-${display(to)} every ${repeatMin}m"
        isPoint -> "at ${display(from)}"
        else -> "${display(from)}-${display(to)}"
    }

    override fun toJson() = JSONObject().apply {
        put("type", type)
        put("from", from)
        put("to", to)
        put("repeat", repeatMin)
    }

    companion object {
        fun display(hhmm: String): String = if (hhmm.isBlank()) "--:--" else hhmm

        fun parseHm(s: String): Int? {
            val parts = s.trim().split(":")
            if (parts.size < 2) return null
            val h = parts[0].toIntOrNull() ?: return null
            val m = parts[1].toIntOrNull() ?: return null
            if (h in 0..23 && m in 0..59) return h * 60 + m
            return null
        }
    }
}

/** Day context. `dow` uses Calendar.DAY_OF_WEEK (1=Sun..7=Sat), `dom` is 1..31. */
data class DayCtx(
    val dow: List<Int> = emptyList(),
    val dom: List<Int> = emptyList()
) : Ctx() {
    override val type get() = Ctx.DAY

    override fun summary(): String {
        val names = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
        val d = dow.map { names[(it - 1).coerceIn(0, 6)] }.joinToString(",")
        val m = dom.joinToString(",") { it.toString() }
        return when {
            d.isNotEmpty() && m.isNotEmpty() -> "on $d + day $m"
            d.isNotEmpty() -> "on $d"
            m.isNotEmpty() -> "on day $m"
            else -> "every day"
        }
    }

    override fun toJson() = JSONObject().apply {
        put("type", type)
        put("dow", JSONArray(dow))
        put("dom", JSONArray(dom))
    }
}

/** Variable value condition. Blank value => matches any *set* value. */
data class VarCtx(
    val name: String = "",
    val value: String = "",
    val invert: Boolean = false
) : Ctx() {
    override val type get() = Ctx.VAR
    override fun summary(): String =
        "$name ${if (value.isBlank()) "set" else "= $value"}${if (invert) " (not)" else ""}"

    override fun toJson() = JSONObject().apply {
        put("type", type)
        put("name", name)
        put("value", value)
        put("invert", invert)
    }
}

/** App context. Restricts which app matches (data["pkg"] or current foreground). */
data class AppCtx(
    val packages: List<String> = emptyList(),
    val foregroundOnly: Boolean = true,
    val invert: Boolean = false
) : Ctx() {
    override val type get() = Ctx.APP
    override fun summary(): String {
        val n = if (packages.isEmpty()) "any app" else "${packages.size} app(s)"
        return (if (foregroundOnly) "fg:" else "app:") + n + (if (invert) " (not)" else "")
    }

    override fun toJson() = JSONObject().apply {
        put("type", type)
        put("pkgs", JSONArray(packages))
        put("fgonly", foregroundOnly)
        put("invert", invert)
    }
}

/**
 * Evaluates the non-event contexts of a profile at trigger time.
 * EventCtx is matched by EventHub itself; the others are AND gates here.
 */
object ContextGate {

    fun check(ctx: Context, profile: Profile, data: Map<String, String>): Boolean {
        profile.timeCtx?.let { if (!timeMatch(it, Calendar.getInstance())) return false }
        profile.dayCtx?.let { if (!dayMatch(it, Calendar.getInstance())) return false }
        profile.varCtx?.let { if (!varMatch(ctx, it)) return false }
        profile.appCtx?.let { if (!appMatch(it, data, ctx)) return false }
        return true
    }

    fun timeMatch(tc: TimeCtx, cal: Calendar): Boolean {
        val minute = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val from = tc.fromMin
        val to = tc.toMin
        val r = tc.repeatMin
        return if (r > 0) {
            if (to >= from) minute in from..to && (minute - from) % r == 0
            else (minute >= from && (minute - from) % r == 0) || (minute <= to && minute % r == 0)
        } else {
            if (to >= from) minute in from..to else minute >= from || minute <= to
        }
    }

    fun dayMatch(dc: DayCtx, cal: Calendar): Boolean {
        val wd = cal.get(Calendar.DAY_OF_WEEK)
        val dm = cal.get(Calendar.DAY_OF_MONTH)
        val dOk = dc.dow.isEmpty() || wd in dc.dow
        val mOk = dc.dom.isEmpty() || dm in dc.dom
        return dOk && mOk
    }

    private fun varMatch(ctx: Context, vc: VarCtx): Boolean {
        val v = UserVars.get(ctx, vc.name)
        val ok = if (vc.value.isBlank()) v != null else matchPattern(vc.value, v ?: "")
        return if (vc.invert) !ok else ok
    }

    private fun appMatch(ac: AppCtx, data: Map<String, String>, ctx: Context): Boolean {
        val pkg = data["pkg"] ?: Watchers.foregroundNow()
        // empty package list must NOT mean "match any app" (that made a 1-app
        // rule fire on every foreground change). non-invert: match only listed
        // apps; invert: match any app EXCEPT listed ones.
        val inList = pkg != null && ac.packages.contains(pkg)
        return if (ac.invert) !inList else inList
    }

    /** Tasker-style simple matching: `*` any, `+` at least one, `/` OR, `!` NOT. */
    fun matchPattern(pattern: String, target: String): Boolean {
        if (pattern.isBlank()) return true
        val negate = pattern.startsWith("!")
        val pat = if (negate) pattern.drop(1) else pattern
        val caseSensitive = pat.any { it.isUpperCase() }
        val any = pat.split("/").any { part -> wildcard(part, target, caseSensitive) }
        return if (negate) !any else any
    }

    private fun wildcard(pat: String, target: String, caseSensitive: Boolean): Boolean {
        if (pat.isBlank()) return true
        val sb = StringBuilder("^")
        for (ch in pat) {
            when (ch) {
                '*' -> sb.append(".*")
                '+' -> sb.append(".+")
                else -> sb.append(Regex.escape(ch.toString()))
            }
        }
        sb.append("$")
        val flags = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
        return Regex(sb.toString(), flags).matches(target)
    }
}
