package com.eventsh.app

import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.eventsh.app.engine.AppCtx
import com.eventsh.app.engine.Ctx
import com.eventsh.app.engine.DayCtx
import com.eventsh.app.engine.EventCatalog
import com.eventsh.app.engine.EventCtx
import com.eventsh.app.engine.EventLog
import com.eventsh.app.engine.LocationCtx
import com.eventsh.app.engine.Store
import com.eventsh.app.engine.TimeCtx
import com.eventsh.app.engine.VarCtx
import com.eventsh.app.engine.Watchers
import com.eventsh.app.ui.C
import com.eventsh.app.ui.UI
import java.util.Locale

/**
 * MainActivity CONTEXT (TRIGGER) EDITORS - Event / Time / Day / Variable / App /
 * Location dialogs plus the app picker and event picker.
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
                paramsBox.addView(ctxRow("BROWSE /sdcard ...", C.accent) {
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
            setTextColor(C.primary)
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
        val ll = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(actionTv)
            addView(paramsBox)
            addView(filterEt)
            addView(prioEt)
            addView(stopCb)
        }
        val d = AlertDialog.Builder(this)
            .setTitle("EVENT CONTEXT")
            .setMessage("tap event name to choose")
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
            setTextColor(C.primary)
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
            setTextColor(C.primary)
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
        val ll = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(singleCb)
            addView(fromTv)
            addView(toTv)
            addView(repeatEt)
        }
        val d = AlertDialog.Builder(this)
            .setTitle("TIME CONTEXT")
            .setMessage("from=to => instant point\nevery N min => repeat within range")
            .setView(ll)
            .setPositiveButton("OK") { _, _ ->
                val rep = (repeatEt.text.toString().toIntOrNull() ?: 0).coerceAtLeast(0)
                val t = if (singleCb.isChecked) {
                    val point = from.ifBlank { to }
                    if (point.isBlank()) {
                        EventLog.push("[ui] pick a time first")
                        return@setPositiveButton
                    }
                    TimeCtx(point, point, 0)
                } else TimeCtx(from, to, rep)
                onSave(t)
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
        val ll = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(sectionLabel("DAYS OF WEEK"))
            addView(dowGrid)
            addView(sectionLabel("MONTHS"))
            addView(monGrid)
            addView(domEt)
        }
        val d = AlertDialog.Builder(this)
            .setTitle("DAY CONTEXT")
            .setMessage("day-of-week, months and days-of-month all apply (AND); leave a group empty for any")
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
        val ll = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(nameEt)
            addView(valEt)
            addView(invCb)
        }
        val d = AlertDialog.Builder(this)
            .setTitle("VARIABLE CONTEXT")
            .setMessage("rule fires when variable matches the value pattern")
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
        val pkgs = existing?.packages?.toMutableSet() ?: mutableSetOf<String>()
        val pickTv = TextView(this).apply {
            textSize = 16f
            setPadding(dp(8f), dp(12f), dp(8f), dp(12f))
            text = if (pkgs.isEmpty()) "TAP HERE TO SELECT APPS" else "${pkgs.size} app(s) selected"
            setTextColor(C.primary)
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
        val latEt = editText("latitude (e.g. 28.6139)")
        val lonEt = editText("longitude (e.g. 77.2090)")
        val radEt = editText("radius meters (e.g. 500)")
        if (existing != null) {
            latEt.setText(String.format(Locale.US, "%.6f", existing.lat))
            lonEt.setText(String.format(Locale.US, "%.6f", existing.lon))
            radEt.setText(existing.radiusMeters.toInt().toString())
        }
        val curBtn = ctxRow("USE CURRENT LOCATION", C.accent) {
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
        val ll = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(curBtn)
            addView(sectionLabel("LATITUDE / LONGITUDE"))
            addView(latEt)
            addView(lonEt)
            addView(sectionLabel("RADIUS"))
            addView(radEt)
        }
        val d = AlertDialog.Builder(this)
            .setTitle("LOCATION CONTEXT (geo-fence)")
            .setMessage("profile fires when the device enters this circle. Battery-friendly: the OS batches fixes and nothing fires while you stay inside.")
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

fun MainActivity.appPick(selected: Set<String>, onDone: (List<String>) -> Unit) {
        val act = this
        val pm = packageManager
        val all = try {
            pm.getInstalledApplications(0)
                .filter { it.packageName != packageName }
        } catch (e: Exception) {
            emptyList()
        }
        fun label(ai: ApplicationInfo): String {
            val l = pm.getApplicationLabel(ai)?.toString() ?: ai.packageName
            return if (l.equals(ai.packageName, true)) l else "$l  [${ai.packageName}]"
        }
        fun isSystem(ai: ApplicationInfo) = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        val user = all.filter { !isSystem(it) }.sortedBy { label(it).lowercase() }
        val system = all.filter { isSystem(it) }.sortedBy { label(it).lowercase() }
        val checked = selected.toMutableSet()
        val rows = ArrayList<Any>()
        if (user.isNotEmpty()) {
            rows.add("USER APPS (${user.size})")
            rows.addAll(user)
        }
        if (system.isNotEmpty()) {
            rows.add("SYSTEM APPS (${system.size})")
            rows.addAll(system)
        }
        val lv = ListView(this)
        lv.adapter = object : BaseAdapter() {
            override fun getCount() = rows.size
            override fun getItem(pos: Int) = rows[pos]
            override fun getItemId(pos: Int) = pos.toLong()
            override fun getItemViewType(pos: Int) = if (rows[pos] is String) 0 else 1
            override fun getViewTypeCount() = 2
            override fun isEnabled(pos: Int) = rows[pos] !is String
            override fun getView(pos: Int, convertView: View?, parent: ViewGroup): View {
                val r = rows[pos]
                if (r is String) {
                    return UI.text(act, r, 13f, C.accent, bold = true).apply {
                        setPadding(dp(12f), dp(14f), dp(12f), dp(4f))
                        setBackgroundColor(C.surface)
                    }
                }
                val ai = r as ApplicationInfo
                val cb = CheckBox(act).apply {
                    isChecked = checked.contains(ai.packageName)
                    isClickable = false
                    isFocusable = false
                }
                val tv = UI.text(act, label(ai), 15f, C.text)
                return LinearLayout(act).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
                    setBackgroundColor(C.bg)
                    addView(cb)
                    addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        marginStart = dp(8f)
                    })
                    setOnClickListener {
                        if (!checked.remove(ai.packageName)) checked.add(ai.packageName)
                        cb.isChecked = checked.contains(ai.packageName)
                    }
                }
            }
        }
        AlertDialog.Builder(this)
            .setTitle("SELECT APPS (${all.size})")
            .setView(lv)
            .setPositiveButton("OK") { _, _ ->
                onDone(rows.filter { it is ApplicationInfo }.map { (it as ApplicationInfo).packageName }.filter { checked.contains(it) })
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

fun MainActivity.pickEvent(onPick: (String) -> Unit) {
        val act = this
        val used = Store.profiles(this).flatMap { it.eventActions }
            .filter { it.isNotBlank() }
            .distinct()
            .filter { it !in EventCatalog.STANDARD }
        val all = EventCatalog.STANDARD + used + "custom..."
        val filtered = all.toMutableList()

        val lv = ListView(this)
        val adapter = object : BaseAdapter() {
            override fun getCount() = filtered.size
            override fun getItem(pos: Int) = filtered[pos]
            override fun getItemId(pos: Int) = pos.toLong()
            override fun getView(pos: Int, convertView: View?, parent: ViewGroup): View {
                val label = filtered[pos]
                val isCustom = label !in EventCatalog.STANDARD
                return TextView(act).apply {
                    text = label
                    textSize = 15f
                    setTextColor(if (isCustom) C.accent else C.text)
                    setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
                }
            }
        }
        lv.adapter = adapter
        var pickerDialog: AlertDialog? = null
        lv.setOnItemClickListener { _, _, pos, _ ->
            val sel = filtered[pos]
            if (sel == "custom...") {
                val input = EditText(this).apply {
                    hint = "broadcast action string"
                    setTextColor(C.text)
                    setHintTextColor(C.hint)
                    textSize = 18f
                }
                AlertDialog.Builder(this)
                    .setTitle("CUSTOM EVENT")
                    .setMessage("your event name or any broadcast action")
                    .setView(input)
                    .setPositiveButton("OK") { _, _ ->
                        val v = input.text.toString().trim()
                        if (v.isNotEmpty()) {
                            pickerDialog?.dismiss()
                            onPick(v)
                        }
                    }
                    .setNegativeButton("CANCEL", null)
                    .show()
            } else {
                pickerDialog?.dismiss()
                onPick(sel)
            }
        }

        val search = EditText(this).apply {
            hint = "search events..."
            setHintTextColor(C.hint)
            setTextColor(C.text)
            textSize = 16f
            background = UI.rounded(C.surface, 10f, C.border, 1f)
            setPadding(dp(10f), dp(9f), dp(10f), dp(9f))
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    val q = s?.toString()?.trim()?.lowercase() ?: ""
                    filtered.clear()
                    if (q.isEmpty()) {
                        filtered.addAll(all)
                    } else {
                        filtered.addAll(all.filter { it.lowercase().contains(q) })
                    }
                    adapter.notifyDataSetChanged()
                }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(14f), dp(10f), dp(14f), dp(4f))
            })
            addView(UI.text(act, "custom event action: any broadcast string", 12f, C.hint).apply {
                setPadding(dp(16f), dp(4f), dp(16f), dp(2f))
            })
            addView(lv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }

        pickerDialog = AlertDialog.Builder(this)
            .setTitle("CHOOSE EVENT (${all.size})")
            .setView(col)
            .setNegativeButton("CANCEL", null)
            .show()
    }
