package com.eventsh.app

import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.eventsh.app.engine.AppCtx
import com.eventsh.app.engine.Ctx
import com.eventsh.app.engine.DayCtx
import com.eventsh.app.engine.EventCatalog
import com.eventsh.app.engine.EventCtx
import com.eventsh.app.engine.EventLog
import com.eventsh.app.engine.LocationCtx
import com.eventsh.app.engine.TimeCtx
import com.eventsh.app.engine.VarCtx
import com.eventsh.app.engine.Watchers
import com.eventsh.app.ui.Theme
import java.util.Locale

/**
 * MainActivity CONTEXT (TRIGGER) EDITORS - Event / Time / Day / Variable / App /
 * Location dialogs. The app picker and event picker live in MainPickList.kt.
 *
 * These are Kotlin extension functions on MainActivity.
 */
fun MainActivity.addContext(list: MutableList<Ctx>, refresh: () -> Unit) {
    AlertDialog.Builder(this)
        .setTitle("ADD CONTEXT")
        .setItems(arrayOf("EVENT", "TIME", "DAY", "VARIABLE", "APP", "LOCATION")) { _, which ->
            when (which) {
                0 -> eventCtxDialog(null, { list.add(it); refresh() }, null)
                1 -> timeCtxDialog(null, { list.add(it); refresh() }, null)
                2 -> dayCtxDialog(null, { list.add(it); refresh() }, null)
                3 -> varCtxDialog(null, { list.add(it); refresh() }, null)
                4 -> appCtxDialog(null, { list.add(it); refresh() }, null)
                5 -> locationCtxDialog(null, { list.add(it); refresh() }, null)
            }
        }
        .setNegativeButton("CANCEL", null)
        .show()
}

fun MainActivity.editContext(list: MutableList<Ctx>, index: Int, refresh: () -> Unit) {
    when (val c = list[index]) {
        is EventCtx -> eventCtxDialog(c, { list[index] = it; refresh() }, { list.removeAt(index); refresh() })
        is TimeCtx -> timeCtxDialog(c, { list[index] = it; refresh() }, { list.removeAt(index); refresh() })
        is DayCtx -> dayCtxDialog(c, { list[index] = it; refresh() }, { list.removeAt(index); refresh() })
        is VarCtx -> varCtxDialog(c, { list[index] = it; refresh() }, { list.removeAt(index); refresh() })
        is AppCtx -> appCtxDialog(c, { list[index] = it; refresh() }, { list.removeAt(index); refresh() })
        is LocationCtx -> locationCtxDialog(c, { list[index] = it; refresh() }, { list.removeAt(index); refresh() })
    }
}

fun MainActivity.eventCtxDialog(existing: EventCtx?, onSave: (EventCtx) -> Unit, onRemove: (() -> Unit)?) {
    val act = this
    val t = Theme.current
    var action = existing?.action ?: ""
    var params = HashMap<String, String>(existing?.params ?: emptyMap())
    val paramEt = HashMap<String, EditText>()
    val paramsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    fun rebuildParams() {
        paramEt.clear()
        paramsBox.removeAllViews()
        EventCatalog.PARAMS[action]?.forEach { (key, label) ->
            paramsBox.addView(sectionLabel(label))
            val et = editText("pattern ($key, * + / ! supported)")
            params[key]?.let { et.setText(it) }
            paramEt[key] = et
            paramsBox.addView(et)
        }
        if (action in FILE_EVENTS) {
            paramsBox.addView(ctxRow("BROWSE /sdcard ...", t.accentPrimary) {
                pendingFilePick = { path ->
                    if (path != null) {
                        paramEt["path"]?.setText(path)
                        params["path"] = path
                    }
                }
                try {
                    startActivityForResult(Intent(act, FilePickerActivity::class.java), REQ_FILE_PICK)
                } catch (e: Exception) {
                    EventLog.push("[ui] file picker unavailable: ${e.message}")
                }
            })
        }
    }
    rebuildParams()

    val actionTv = TextView(this).apply {
        textSize = 16f
        setPadding(dp(8f), dp(12f), dp(8f), dp(12f))
        text = if (action.isBlank()) "(tap to choose event)" else action
        setTextColor(t.accentPrimary)
        setOnClickListener {
            pickEvent { ev ->
                action = ev
                text = ev
                params = HashMap()
                rebuildParams()
            }
        }
    }
    val filterEt = editText("custom summary filter (advanced)")
    val prioEt = editText("priority (5)")
    val stopCb = checkBox("stop event (consume for other profiles)")
    if (existing != null) {
        filterEt.setText(existing.filter)
        prioEt.setText(existing.priority.toString())
        stopCb.isChecked = existing.stopEvent
    }
    val hint = TextView(this).apply {
        textSize = 12f
        setTextColor(t.textMuted)
        text = "tap event name to choose"
    }
    val ll = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(hint)
        addView(actionTv)
        addView(paramsBox)
        addView(filterEt)
        addView(prioEt)
        addView(stopCb)
    }
    val d = AlertDialog.Builder(this)
        .setTitle("EVENT CONTEXT")
        .setView(ll)
        .setPositiveButton("OK") { _, _ ->
            if (action.isBlank()) {
                EventLog.push("[ui] choose an event first")
                return@setPositiveButton
            }
            val saved = HashMap<String, String>()
            paramEt.forEach { (k, et) ->
                val v = et.text.toString().trim()
                if (v.isNotBlank()) saved[k] = v
            }
            onSave(
                EventCtx(
                    action = action,
                    filter = filterEt.text.toString().trim(),
                    params = saved,
                    priority = (prioEt.text.toString().toIntOrNull() ?: 5).coerceIn(1, 10),
                    stopEvent = stopCb.isChecked
                )
            )
        }
        .setNegativeButton("CANCEL", null)
    if (onRemove != null) d.setNeutralButton("REMOVE") { _, _ -> onRemove() }
    d.show()
}

fun MainActivity.timeCtxDialog(existing: TimeCtx?, onSave: (TimeCtx) -> Unit, onRemove: (() -> Unit)?) {
    val act = this
    val t = Theme.current
    var from = existing?.from ?: ""
    var to = existing?.to ?: ""
    val repeatEt = editText("repeat every N minutes (0 = no repeat)")
    if (existing != null && existing.repeatMin > 0) repeatEt.setText(existing.repeatMin.toString())
    val singleCb = checkBox("single exact time (no From/To range)")
    singleCb.isChecked = existing != null && existing.isPoint

    lateinit var fromTv: TextView
    lateinit var toTv: TextView

    fun syncViews() {
        fromTv.alpha = 1f
        toTv.alpha = if (singleCb.isChecked) 0.35f else 1f
        toTv.isClickable = !singleCb.isChecked
        toTv.text = if (singleCb.isChecked) "To: (same as From)" else "To: ${TimeCtx.display(to)}"
        fromTv.text = "From: ${TimeCtx.display(from)}"
    }

    fromTv = TextView(this).apply {
        textSize = 16f
        setPadding(dp(8f), dp(12f), dp(8f), dp(12f))
        text = "From: ${TimeCtx.display(from)}"
        setTextColor(t.accentPrimary)
        setOnClickListener {
            val (h, m) = hm(from)
            TimePickerDialog(act, { _, hh, mm ->
                from = String.format(Locale.US, "%02d:%02d", hh, mm)
                if (singleCb.isChecked) to = from
                syncViews()
            }, h, m, true).show()
        }
    }
    toTv = TextView(this).apply {
        textSize = 16f
        setPadding(dp(8f), dp(12f), dp(8f), dp(12f))
        text = "To: ${TimeCtx.display(to)}"
        setTextColor(t.accentPrimary)
        setOnClickListener {
            val (h, m) = hm(to)
            TimePickerDialog(act, { _, hh, mm ->
                to = String.format(Locale.US, "%02d:%02d", hh, mm)
                if (singleCb.isChecked) from = to
                syncViews()
            }, h, m, true).show()
        }
    }
    singleCb.setOnCheckedChangeListener { _, _ ->
        if (singleCb.isChecked) {
            if (from.isBlank() && to.isNotBlank()) from = to
            if (to.isBlank() && from.isNotBlank()) to = from
        }
        syncViews()
    }
    syncViews()
    val hint = TextView(this).apply {
        textSize = 12f
        setTextColor(t.textMuted)
        text = "from=to => instant point\nevery N min => repeat within range"
    }
    val ll = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(hint)
        addView(singleCb)
        addView(fromTv)
        addView(toTv)
        addView(repeatEt)
    }
    val d = AlertDialog.Builder(this)
        .setTitle("TIME CONTEXT")
        .setView(ll)
        .setPositiveButton("OK") { _, _ ->
            val rep = (repeatEt.text.toString().toIntOrNull() ?: 0).coerceAtLeast(0)
            val time = if (singleCb.isChecked) {
                val point = from.ifBlank { to }
                if (point.isBlank()) {
                    EventLog.push("[ui] pick a time first")
                    return@setPositiveButton
                }
                TimeCtx(point, point, 0)
            } else TimeCtx(from, to, rep)
            onSave(time)
        }
        .setNegativeButton("CANCEL", null)
    if (onRemove != null) d.setNeutralButton("REMOVE") { _, _ -> onRemove() }
    d.show()
}

fun MainActivity.hm(hhmm: String): Pair<Int, Int> {
    val parts = hhmm.split(":")
    return (parts.getOrNull(0)?.toIntOrNull() ?: 0) to (parts.getOrNull(1)?.toIntOrNull() ?: 0)
}

fun MainActivity.dayCtxDialog(existing: DayCtx?, onSave: (DayCtx) -> Unit, onRemove: (() -> Unit)?) {
    val names = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
    val dowCbs = names.mapIndexed { i, n ->
        checkBox(n).apply { isChecked = existing?.dow?.contains(i + 1) == true }
    }
    val monthNames = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    val monCbs = monthNames.mapIndexed { i, n ->
        checkBox(n).apply { isChecked = existing?.mon?.contains(i + 1) == true }
    }
    val domEt = editText("days of month (comma: 1,15,28)")
    if (existing != null) domEt.setText(existing.dom.joinToString(","))
    val dowGrid = GridLayout(this).apply { columnCount = 4 }
    dowCbs.forEach { dowGrid.addView(it) }
    val monGrid = GridLayout(this).apply { columnCount = 3 }
    monCbs.forEach { monGrid.addView(it) }
    val hint = TextView(this).apply {
        textSize = 12f
        setTextColor(Theme.current.textMuted)
        text = "day-of-week, months and days-of-month all apply (AND); leave a group empty for any"
    }
    val ll = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(hint)
        addView(sectionLabel("DAYS OF WEEK"))
        addView(dowGrid)
        addView(sectionLabel("MONTHS"))
        addView(monGrid)
        addView(domEt)
    }
    val d = AlertDialog.Builder(this)
        .setTitle("DAY CONTEXT")
        .setView(ll)
        .setPositiveButton("OK") { _, _ ->
            onSave(
                DayCtx(
                    dow = dowCbs.mapIndexedNotNull { i, cb -> if (cb.isChecked) i + 1 else null },
                    mon = monCbs.mapIndexedNotNull { i, cb -> if (cb.isChecked) i + 1 else null },
                    dom = domEt.text.toString().split(",")
                        .mapNotNull { it.trim().toIntOrNull() }
                        .filter { it in 1..31 }
                )
            )
        }
        .setNegativeButton("CANCEL", null)
    if (onRemove != null) d.setNeutralButton("REMOVE") { _, _ -> onRemove() }
    d.show()
}

fun MainActivity.varCtxDialog(existing: VarCtx?, onSave: (VarCtx) -> Unit, onRemove: (() -> Unit)?) {
    val nameEt = editText("variable name")
    val valEt = editText("value pattern (* = any)")
    val invCb = checkBox("invert (does NOT match)")
    if (existing != null) {
        nameEt.setText(existing.name)
        valEt.setText(existing.value)
        invCb.isChecked = existing.invert
    }
    val hint = TextView(this).apply {
        textSize = 12f
        setTextColor(Theme.current.textMuted)
        text = "rule fires when variable matches the value pattern"
    }
    val ll = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(hint)
        addView(nameEt)
        addView(valEt)
        addView(invCb)
    }
    val d = AlertDialog.Builder(this)
        .setTitle("VARIABLE CONTEXT")
        .setView(ll)
        .setPositiveButton("OK") { _, _ ->
            val n = nameEt.text.toString().trim()
            if (n.isEmpty()) {
                EventLog.push("[ui] variable name required")
                return@setPositiveButton
            }
            onSave(VarCtx(n, valEt.text.toString(), invCb.isChecked))
        }
        .setNegativeButton("CANCEL", null)
    if (onRemove != null) d.setNeutralButton("REMOVE") { _, _ -> onRemove() }
    d.show()
}

fun MainActivity.appCtxDialog(existing: AppCtx?, onSave: (AppCtx) -> Unit, onRemove: (() -> Unit)?) {
    val t = Theme.current
    val pkgs = existing?.packages?.toMutableSet() ?: mutableSetOf<String>()
    val pickTv = TextView(this).apply {
        textSize = 16f
        setPadding(dp(8f), dp(12f), dp(8f), dp(12f))
        text = if (pkgs.isEmpty()) "TAP HERE TO SELECT APPS" else "${pkgs.size} app(s) selected"
        setTextColor(t.accentPrimary)
        setOnClickListener {
            appPick(pkgs) { sel ->
                pkgs.clear()
                pkgs.addAll(sel)
                text = if (sel.isEmpty()) "TAP HERE TO SELECT APPS" else "${sel.size} app(s) selected"
            }
        }
    }
    val fgCb = checkBox("foreground only").apply { isChecked = existing?.foregroundOnly ?: true }
    val invCb = checkBox("invert (any app EXCEPT these)").apply { isChecked = existing?.invert ?: false }
    val ll = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(pickTv)
        addView(fgCb)
        addView(invCb)
    }
    val d = AlertDialog.Builder(this)
        .setTitle("APP CONTEXT")
        .setView(ll)
        .setPositiveButton("OK") { _, _ ->
            if (pkgs.isEmpty() && !invCb.isChecked) {
                EventLog.push("[ui] select at least one app (or enable invert)")
                return@setPositiveButton
            }
            onSave(AppCtx(pkgs.toList(), fgCb.isChecked, invCb.isChecked))
        }
        .setNegativeButton("CANCEL", null)
    if (onRemove != null) d.setNeutralButton("REMOVE") { _, _ -> onRemove() }
    d.show()
}

fun MainActivity.locationCtxDialog(existing: LocationCtx?, onSave: (LocationCtx) -> Unit, onRemove: (() -> Unit)?) {
    val t = Theme.current
    val latEt = editText("latitude (e.g. 28.6139)")
    val lonEt = editText("longitude (e.g. 77.2090)")
    val radEt = editText("radius meters (e.g. 500)")
    if (existing != null) {
        latEt.setText(String.format(Locale.US, "%.6f", existing.lat))
        lonEt.setText(String.format(Locale.US, "%.6f", existing.lon))
        radEt.setText(existing.radiusMeters.toInt().toString())
    }
    val curBtn = ctxRow("USE CURRENT LOCATION", t.accentPrimary) {
        val loc = Watchers.currentLoc()
            ?: lastKnownLocation()
        if (loc == null) {
            if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                EventLog.push("[loc] grant the Location permission first (Settings)")
                requestPermissions(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION), 30)
            } else {
                EventLog.push("[loc] no fix yet - open the app, then tap again")
            }
        } else {
            latEt.setText(String.format(Locale.US, "%.6f", loc[0]))
            lonEt.setText(String.format(Locale.US, "%.6f", loc[1]))
        }
    }
    val hint = TextView(this).apply {
        textSize = 12f
        setTextColor(t.textMuted)
        text = "profile fires when the device enters this circle. Battery-friendly: the OS batches fixes and nothing fires while you stay inside."
    }
    val ll = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(hint)
        addView(curBtn)
        addView(sectionLabel("LATITUDE / LONGITUDE"))
        addView(latEt)
        addView(lonEt)
        addView(sectionLabel("RADIUS"))
        addView(radEt)
    }
    val d = AlertDialog.Builder(this)
        .setTitle("LOCATION CONTEXT (geo-fence)")
        .setView(ll)
        .setPositiveButton("OK") { _, _ ->
            val lat = latEt.text.toString().trim().toDoubleOrNull()
            val lon = lonEt.text.toString().trim().toDoubleOrNull()
            val rad = radEt.text.toString().trim().toDoubleOrNull()
            if (lat == null || lon == null || rad == null || rad <= 0) {
                EventLog.push("[ui] enter a valid latitude, longitude and radius")
                return@setPositiveButton
            }
            onSave(LocationCtx(lat, lon, rad))
        }
        .setNegativeButton("CANCEL", null)
    if (onRemove != null) d.setNeutralButton("REMOVE") { _, _ -> onRemove() }
    d.show()
}

/** Last cached fix from any provider, without requesting a fresh one. */
fun MainActivity.lastKnownLocation(): DoubleArray? {
    return try {
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) null else {
            val lm = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
            val gps = lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
            val net = lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
            val best = gps ?: net
            if (best == null) null else doubleArrayOf(best.latitude, best.longitude)
        }
    } catch (e: Exception) {
        null
    }
}
