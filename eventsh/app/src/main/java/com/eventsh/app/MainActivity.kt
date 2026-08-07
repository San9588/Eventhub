package com.eventsh.app

import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.view.WindowInsets
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.eventsh.app.engine.AppCtx
import com.eventsh.app.engine.Ctx
import com.eventsh.app.engine.DayCtx
import com.eventsh.app.engine.EventCtx
import com.eventsh.app.engine.EventCatalog
import com.eventsh.app.engine.EventHub
import com.eventsh.app.engine.EventLog
import com.eventsh.app.engine.Permissions
import com.eventsh.app.engine.RootBridge
import com.eventsh.app.engine.Rule
import com.eventsh.app.engine.RuleStore
import com.eventsh.app.engine.Scheduler
import com.eventsh.app.engine.SysStats
import com.eventsh.app.engine.TimeCtx
import com.eventsh.app.engine.UserVars
import com.eventsh.app.engine.VarCtx
import com.eventsh.app.service.EventService
import com.eventsh.app.ui.TerminalView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class MainActivity : Activity() {

    private lateinit var view: TerminalView
    private val handler = Handler(Looper.getMainLooper())
    private var cpuRef = 0L
    private var permDialog: AlertDialog? = null
    private val permRows = mutableListOf<Pair<Permissions.Need, TextView>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        view = TerminalView(this)
        setContentView(view)

        if (Build.VERSION.SDK_INT >= 30) {
            view.setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
            view.requestApplyInsets()
        }

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 10)
        }
        UserVars.init(this)

        wire()
        if (RuleStore.autostart(this) && !isServiceRunning()) startServiceCompat()
        RootBridge.checkAsync()
        refreshScreen()
        updateStats()
        EventLog.listener = {
            runOnUiThread { refreshScreen() }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissions()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshPermissions()
    }

    private fun refreshPermissions() {
        val d = permDialog ?: return
        if (!d.isShowing) { permDialog = null; return }
        var allGranted = true
        for ((need, tv) in permRows) {
            if (need.granted(this)) {
                tv.text = "[ ${need.label} ] OK"
                tv.setTextColor(0xFF3C7852.toInt())
            } else {
                allGranted = false
            }
        }
        if (allGranted) {
            d.dismiss()
            permDialog = null
            EventLog.push("[perm] all set")
        }
    }

    override fun onDestroy() {
        EventLog.listener = null
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun wire() {
        view.onToggleRule = { r ->
            val cur = RuleStore.load(this).toMutableList()
            val i = cur.indexOfFirst { it.id == r.id }
            if (i >= 0) {
                cur[i] = r.copy(enabled = !r.enabled)
                RuleStore.save(this, cur)
                refreshScreen()
            }
        }
        view.onNav = { s ->
            view.screen = s
            view.invalidate()
        }
        view.onServiceToggle = {
            if (isServiceRunning()) {
                stopService(Intent(this, EventService::class.java))
            } else {
                startServiceCompat()
            }
            handler.postDelayed({ refreshScreen() }, 400)
        }
        view.onRootCheck = {
            RootBridge.checkAsync()
            handler.postDelayed({ refreshScreen() }, 900)
        }
        view.onAddVar = { varDialog(null) }
        view.onEditVar = { row -> varDialog(row) }
        view.onEditRule = { r -> ruleDialog(r) }
        view.onDeleteRule = { r -> deleteRule(r) }
        view.onAddRule = { ruleDialog(null) }
        view.onAddTimer = { timerDialog() }
    }

    private fun ruleDialog(existing: Rule?) {
        val contexts = (existing?.contexts?.toMutableList() ?: mutableListOf<Ctx>())
        if (contexts.none { it is EventCtx } && existing != null && existing.event.isNotBlank()) {
            contexts.add(0, EventCtx(existing.event, existing.filter))
        }

        val labelEt = editText("label")
        val taskEt = editText("termux task name")
        val sendEt = editText("send broadcast action (com.pkg.ACTION)")
        val sendXEt = editText("extras  key:value (per line | or ;)")
        val sendPEt = editText("package target (optional)")
        val textEt = editText("notify text (%VAR% ok)")
        val rootEt = editText("root command")
        val cdEt = editText("cooldown seconds (0)")
        val rtEt = editText("retries on failure (0)")
        val notifyCb = checkBox("show notification")
        if (existing != null) {
            labelEt.setText(existing.label)
            taskEt.setText(existing.taskName)
            sendEt.setText(existing.sendAction)
            sendXEt.setText(existing.sendExtras)
            sendPEt.setText(existing.sendPackage)
            textEt.setText(existing.notifyText)
            rootEt.setText(existing.rootCmd)
            cdEt.setText(existing.cooldownSec.toString())
            rtEt.setText(existing.retries.toString())
            notifyCb.isChecked = existing.notify
        } else {
            notifyCb.isChecked = true
        }

        // ---- contexts editor (Tasker-style: Event / Time / Day / Variable / App) ----
        val ctxBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun refreshCtx() {
            ctxBox.removeAllViews()
            if (contexts.isEmpty()) {
                ctxBox.addView(TextView(this).apply {
                    text = "(no contexts yet - add one)"
                    textSize = 16f
                    setTextColor(0xFF3C7852.toInt())
                    setPadding(8, 10, 8, 10)
                })
            } else {
                for (i in contexts.indices) {
                    val c = contexts[i]
                    val tag = when (c.type) {
                        Ctx.EVENT -> "EV"
                        Ctx.TIME -> "TM"
                        Ctx.DAY -> "DY"
                        Ctx.VAR -> "VA"
                        Ctx.APP -> "AP"
                        else -> "??"
                    }
                    val idx = i
                    ctxBox.addView(ctxRow("[$tag] ${c.summary()}", 0xFF00FF6E.toInt()) {
                        editContext(contexts, idx) { refreshCtx() }
                    })
                }
            }
            ctxBox.addView(ctxRow("[ + ADD CONTEXT ]", 0xFFFFB020.toInt()) {
                addContext(contexts) { refreshCtx() }
            })
        }
        refreshCtx()

        val ll = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(labelEt)
            addView(sectionLabel("CONTEXTS"))
            addView(ctxBox)
            addView(sectionLabel("RUN SCRIPT (TERMUX)"))
            addView(taskEt)
            addView(sectionLabel("SEND BROADCAST"))
            addView(sendEt)
            addView(sendXEt)
            addView(sendPEt)
            addView(sectionLabel("NOTIFY"))
            addView(notifyCb)
            addView(textEt)
            addView(sectionLabel("ROOT COMMAND"))
            addView(rootEt)
            addView(sectionLabel("TIMING"))
            addView(cdEt)
            addView(rtEt)
        }
        val scroll = ScrollView(this).apply { addView(ll) }

        val d = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "ADD RULE" else "EDIT RULE")
            .setMessage("tap a context to edit it\nall contexts must match (AND)")
            .setView(scroll)
            .setPositiveButton("SAVE") { _, _ ->
                val eventCtx = contexts.filterIsInstance<EventCtx>().firstOrNull()
                val timeCtx = contexts.filterIsInstance<TimeCtx>().firstOrNull()
                val appCtx = contexts.filterIsInstance<AppCtx>().firstOrNull()
                val varCtx = contexts.filterIsInstance<VarCtx>().firstOrNull()
                val event = eventCtx?.action ?: ""
                val filter = eventCtx?.filter ?: ""
                if (event.isBlank() && timeCtx == null && appCtx == null && varCtx == null) {
                    EventLog.push("[ui] add an EVENT, TIME, APP or VARIABLE context")
                    return@setPositiveButton
                }
                val base = existing ?: Rule(
                    id = "r_" + UUID.randomUUID().toString().take(8),
                    event = event, label = "NEW.RULE"
                )
                val newRule = base.copy(
                    label = labelEt.text.toString().trim().ifBlank { "RULE" },
                    event = event,
                    filter = filter,
                    contexts = contexts,
                    enabled = true,
                    cooldownSec = cdEt.text.toString().toLongOrNull() ?: 0L,
                    retries = (rtEt.text.toString().toIntOrNull() ?: 0).coerceIn(0, 10),
                    taskName = taskEt.text.toString().trim(),
                    sendAction = sendEt.text.toString().trim(),
                    sendExtras = sendXEt.text.toString(),
                    sendPackage = sendPEt.text.toString().trim(),
                    notifyText = textEt.text.toString(),
                    rootCmd = rootEt.text.toString().trim(),
                    notify = notifyCb.isChecked
                )
                val cur = RuleStore.load(this).toMutableList()
                val i = cur.indexOfFirst { it.id == base.id }
                if (i >= 0) cur[i] = newRule else cur.add(newRule)
                RuleStore.save(this, cur)
                when {
                    newRule.isOneShotTimer || newRule.isDailyTimer -> Scheduler.schedule(this, newRule)
                    newRule.event.isBlank() && newRule.timeCtx != null -> Scheduler.scheduleCtx(this, newRule)
                }
                if (isServiceRunning()) EventHub.resync(this)
                refreshScreen()
                val missing = Permissions.requiredFor(newRule).filter { !it.granted(this) }
                if (missing.isNotEmpty()) {
                    EventLog.push("[perm] ${missing.joinToString(", ") { it.label }}")
                    showPermissionsDialog(missing)
                }
            }
            .setNegativeButton("CANCEL", null)
        if (existing != null) {
            d.setNeutralButton("DELETE") { _, _ ->
                Scheduler.cancel(this, existing)
                val cur = RuleStore.load(this).toMutableList()
                cur.removeAll { it.id == existing.id }
                RuleStore.save(this, cur)
                if (isServiceRunning()) EventHub.resync(this)
                refreshScreen()
            }
        }
        d.show()
    }

    private fun deleteRule(r: Rule) {
        AlertDialog.Builder(this)
            .setTitle("DELETE RULE")
            .setMessage("delete '${r.label}'?")
            .setPositiveButton("DELETE") { _, _ ->
                Scheduler.cancel(this, r)
                val cur = RuleStore.load(this).toMutableList()
                cur.removeAll { it.id == r.id }
                RuleStore.save(this, cur)
                if (isServiceRunning()) EventHub.resync(this)
                refreshScreen()
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    /** Tasker-style permission prompt: tap a row to set up that permission. */
    private fun showPermissionsDialog(missing: List<Permissions.Need>) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(56, 16, 56, 24)
        }
        box.addView(TextView(this).apply {
            text = "Tap each one to set it up"
            textSize = 13f
            setTextColor(0xFF9DCAAD.toInt())
        })
        permRows.clear()
        missing.forEach { need ->
            val tv = TextView(this).apply {
                text = "[ ${need.label} ]  SET"
                setPadding(0, 28, 0, 2)
                textSize = 17f
                setTextColor(0xFF37F08B.toInt())
                setOnClickListener { need.open(this@MainActivity) }
            }
            box.addView(tv)
            box.addView(TextView(this).apply {
                text = need.detail
                textSize = 12f
                setTextColor(0xFF9DCAAD.toInt())
            })
            permRows += need to tv
        }
        val d = AlertDialog.Builder(this)
            .setTitle("PERMISSIONS NEEDED")
            .setView(box)
            .setPositiveButton("DONE", null)
            .show()
        permDialog = d
        refreshPermissions()
    }

    // ------------------------------------------------------------ context editors
    private fun addContext(list: MutableList<Ctx>, refresh: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle("ADD CONTEXT")
            .setItems(arrayOf("EVENT", "TIME", "DAY", "VARIABLE", "APP")) { _, which ->
                when (which) {
                    0 -> {
                        if (list.any { it is EventCtx }) {
                            EventLog.push("[ui] replacing existing EVENT context")
                            list.removeAll { it is EventCtx }
                        }
                        eventCtxDialog(null, { list.add(it); refresh() }, null)
                    }
                    1 -> timeCtxDialog(null, { list.add(it); refresh() }, null)
                    2 -> dayCtxDialog(null, { list.add(it); refresh() }, null)
                    3 -> varCtxDialog(null, { list.add(it); refresh() }, null)
                    4 -> appCtxDialog(null, { list.add(it); refresh() }, null)
                }
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun editContext(list: MutableList<Ctx>, index: Int, refresh: () -> Unit) {
        when (val c = list[index]) {
            is EventCtx -> eventCtxDialog(c, { list[index] = it; refresh() }, { list.removeAt(index); refresh() })
            is TimeCtx -> timeCtxDialog(c, { list[index] = it; refresh() }, { list.removeAt(index); refresh() })
            is DayCtx -> dayCtxDialog(c, { list[index] = it; refresh() }, { list.removeAt(index); refresh() })
            is VarCtx -> varCtxDialog(c, { list[index] = it; refresh() }, { list.removeAt(index); refresh() })
            is AppCtx -> appCtxDialog(c, { list[index] = it; refresh() }, { list.removeAt(index); refresh() })
        }
    }

    private fun eventCtxDialog(existing: EventCtx?, onSave: (EventCtx) -> Unit, onRemove: (() -> Unit)?) {
        var action = existing?.action ?: ""
        val actionTv = TextView(this).apply {
            textSize = 16f
            setPadding(8, 14, 8, 14)
            text = if (action.isBlank()) "(choose event)" else action
            setTextColor(0xFF00FF6E.toInt())
            setOnClickListener { pickEvent { ev -> action = ev; text = ev } }
        }
        val filterEt = editText("filter (substring or number)")
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
                onSave(
                    EventCtx(
                        action = action,
                        filter = filterEt.text.toString().trim(),
                        priority = (prioEt.text.toString().toIntOrNull() ?: 5).coerceIn(1, 10),
                        stopEvent = stopCb.isChecked
                    )
                )
            }
            .setNegativeButton("CANCEL", null)
        if (onRemove != null) d.setNeutralButton("REMOVE") { _, _ -> onRemove() }
        d.show()
    }

    private fun timeCtxDialog(existing: TimeCtx?, onSave: (TimeCtx) -> Unit, onRemove: (() -> Unit)?) {
        var from = existing?.from ?: ""
        var to = existing?.to ?: ""
        val repeatEt = editText("repeat every N minutes (0 = no repeat)")
        if (existing != null && existing.repeatMin > 0) repeatEt.setText(existing.repeatMin.toString())
        val singleCb = checkBox("single exact time (no From/To range)")
        singleCb.isChecked = existing != null && existing.from.isNotBlank() && existing.from == existing.to

        val fromTv = TextView(this).apply {
            textSize = 16f
            setPadding(8, 14, 8, 14)
            text = "From: ${TimeCtx.display(from)}"
            setTextColor(0xFF00FF6E.toInt())
            setOnClickListener {
                val (h, m) = hm(from)
                TimePickerDialog(this@MainActivity, { _, hh, mm ->
                    from = String.format(Locale.US, "%02d:%02d", hh, mm)
                    text = "From: $from"
                }, h, m, true).show()
            }
        }
        val toTv = TextView(this).apply {
            textSize = 16f
            setPadding(8, 14, 8, 14)
            text = "To: ${TimeCtx.display(to)}"
            setTextColor(0xFF00FF6E.toInt())
            setOnClickListener {
                val (h, m) = hm(to)
                TimePickerDialog(this@MainActivity, { _, hh, mm ->
                    to = String.format(Locale.US, "%02d:%02d", hh, mm)
                    text = "To: $to"
                }, h, m, true).show()
            }
        }
        fun syncViews() {
            fromTv.alpha = if (singleCb.isChecked) 0.35f else 1f
            toTv.alpha = if (singleCb.isChecked) 0.35f else 1f
            toTv.text = if (singleCb.isChecked) "To: (same as From)" else "To: ${TimeCtx.display(to)}"
        }
        singleCb.setOnCheckedChangeListener { _, _ -> syncViews() }
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
                val t = if (singleCb.isChecked) TimeCtx(from, from, 0) else TimeCtx(from, to, rep)
                onSave(t)
            }
            .setNegativeButton("CANCEL", null)
        if (onRemove != null) d.setNeutralButton("REMOVE") { _, _ -> onRemove() }
        d.show()
    }

    private fun hm(hhmm: String): Pair<Int, Int> {
        val parts = hhmm.split(":")
        return (parts.getOrNull(0)?.toIntOrNull() ?: 0) to (parts.getOrNull(1)?.toIntOrNull() ?: 0)
    }

    private fun dayCtxDialog(existing: DayCtx?, onSave: (DayCtx) -> Unit, onRemove: (() -> Unit)?) {
        val names = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
        val dowCbs = names.mapIndexed { i, n ->
            checkBox(n).apply { isChecked = existing?.dow?.contains(i + 1) == true }
        }
        val domEt = editText("days of month (comma: 1,15,28)")
        if (existing != null) domEt.setText(existing.dom.joinToString(","))
        val ll = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            dowCbs.forEach { addView(it) }
            addView(domEt)
        }
        val d = AlertDialog.Builder(this)
            .setTitle("DAY CONTEXT")
            .setMessage("days of week + days of month both apply (AND)")
            .setView(ll)
            .setPositiveButton("OK") { _, _ ->
                onSave(
                    DayCtx(
                        dow = dowCbs.mapIndexedNotNull { i, cb -> if (cb.isChecked) i + 1 else null },
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

    private fun varCtxDialog(existing: VarCtx?, onSave: (VarCtx) -> Unit, onRemove: (() -> Unit)?) {
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

    private fun appCtxDialog(existing: AppCtx?, onSave: (AppCtx) -> Unit, onRemove: (() -> Unit)?) {
        val pkgs = existing?.packages?.toMutableSet() ?: mutableSetOf<String>()
        val pickTv = TextView(this).apply {
            textSize = 16f
            setPadding(8, 14, 8, 14)
            text = if (pkgs.isEmpty()) "(select apps)" else "${pkgs.size} selected"
            setTextColor(0xFF00FF6E.toInt())
            setOnClickListener {
                appPick(pkgs) { sel ->
                    pkgs.clear()
                    pkgs.addAll(sel)
                    text = if (sel.isEmpty()) "(select apps)" else "${sel.size} selected"
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
                onSave(AppCtx(pkgs.toList(), fgCb.isChecked, invCb.isChecked))
            }
            .setNegativeButton("CANCEL", null)
        if (onRemove != null) d.setNeutralButton("REMOVE") { _, _ -> onRemove() }
        d.show()
    }

    private fun appPick(selected: Set<String>, onDone: (List<String>) -> Unit) {
        val pm = packageManager
        val apps = try {
            pm.getInstalledApplications(0)
                .filter { it.packageName != packageName }
                .sortedBy {
                    (pm.getApplicationLabel(it)?.toString() ?: it.packageName).lowercase()
                }
        } catch (e: Exception) {
            emptyList()
        }
        val labels = apps.map { ai ->
            val l = pm.getApplicationLabel(ai)?.toString() ?: ai.packageName
            if (l.equals(ai.packageName, true)) l else "$l  [${ai.packageName}]"
        }
        val pkgs = apps.map { it.packageName }
        val checked = BooleanArray(apps.size) { selected.contains(pkgs[it]) }
        AlertDialog.Builder(this)
            .setTitle("SELECT APPS (${apps.size})")
            .setMultiChoiceItems(labels.toTypedArray(), checked) { _, i, ch -> checked[i] = ch }
            .setPositiveButton("OK") { _, _ ->
                onDone(pkgs.filterIndexed { i, _ -> checked[i] })
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun sectionLabel(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(0xFF3C7852.toInt())
        setPadding(8, 12, 8, 2)
    }

    private fun ctxRow(text: String, color: Int, onClick: () -> Unit): TextView = TextView(this).apply {
        this.text = text
        textSize = 16f
        setTextColor(color)
        setPadding(8, 12, 8, 12)
        setBackgroundColor(0xFF071209.toInt())
        setOnClickListener { onClick() }
    }

    private fun pickEvent(onPick: (String) -> Unit) {
        val used = RuleStore.load(this).map { it.event }
            .filter { it.isNotBlank() }
            .distinct()
            .filter { it !in EventCatalog.STANDARD }
        val items = EventCatalog.STANDARD + used + "custom..."
        AlertDialog.Builder(this)
            .setTitle("CHOOSE EVENT")
            .setItems(items.toTypedArray()) { _, which ->
                val sel = items[which]
                if (sel == "custom...") {
                    val input = EditText(this).apply {
                        hint = "broadcast action string"
                        textSize = 18f
                    }
                    AlertDialog.Builder(this)
                        .setTitle("CUSTOM EVENT")
                        .setMessage("your event name or any broadcast action")
                        .setView(input)
                        .setPositiveButton("OK") { _, _ ->
                            val v = input.text.toString().trim()
                            if (v.isNotEmpty()) onPick(v)
                        }
                        .setNegativeButton("CANCEL", null)
                        .show()
                } else {
                    onPick(sel)
                }
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun editText(hint: String): EditText = EditText(this).apply {
        this.hint = hint
        textSize = 18f
    }

    private fun checkBox(text: String): CheckBox = CheckBox(this).apply {
        this.text = text
        textSize = 18f
    }

    private fun timerDialog() {
        val whenEt = editText("07:30 | +600 | epoch-ms")
        val labelEt = editText("label")
        val taskEt = editText("termux task name")
        val rootEt = editText("root command")
        val notifyCb = checkBox("show notification")
        val ll = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(whenEt)
            addView(labelEt)
            addView(taskEt)
            addView(rootEt)
            addView(notifyCb)
        }
        AlertDialog.Builder(this)
            .setTitle("ADD TIMER")
            .setMessage("07:30 = daily\n+600 = one-shot in 600s\n1730000000 = one-shot epoch ms")
            .setView(ll)
            .setPositiveButton("ARM") { _, _ ->
                val w = whenEt.text.toString().trim()
                val daily = if (w.contains(":")) w else ""
                val atEpoch = when {
                    w.startsWith("+") -> System.currentTimeMillis() + (w.drop(1).toLongOrNull() ?: 0L) * 1000
                    w.toLongOrNull() != null && daily.isEmpty() -> w.toLong()
                    else -> 0L
                }
                if (daily.isEmpty() && atEpoch <= 0) {
                    EventLog.push("[timer] bad time: $w")
                    return@setPositiveButton
                }
                val rule = Rule(
                    id = "t_" + UUID.randomUUID().toString().take(8),
                    event = "timer.one",
                    label = labelEt.text.toString().trim().ifBlank { "TIMER" },
                    enabled = true,
                    taskName = taskEt.text.toString().trim(),
                    notify = notifyCb.isChecked,
                    rootCmd = rootEt.text.toString().trim(),
                    atEpoch = atEpoch,
                    daily = daily
                )
                val cur = RuleStore.load(this).toMutableList().apply { add(rule) }
                RuleStore.save(this, cur)
                Scheduler.schedule(this, rule)
                EventLog.push("[timer] armed ${rule.label} " + if (daily.isNotBlank()) daily else atEpoch.toString())
                refreshScreen()
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun varDialog(existing: TerminalView.VarRow?) {
        val nameEt = editText("name  (UPPER=disk)")
        val valEt = editText("value")
        if (existing != null) {
            nameEt.setText(existing.name)
            valEt.setText(existing.value)
        }
        val ll = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(nameEt)
            addView(valEt)
        }
        val d = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "ADD VARIABLE" else "EDIT VARIABLE")
            .setMessage(if (existing == null) "lowercase name = RAM only\nUPPERCASE name = saved to disk" else null)
            .setView(ll)
            .setPositiveButton("SET") { _, _ ->
                val n = nameEt.text.toString().trim()
                val v = valEt.text.toString()
                if (n.isNotEmpty()) {
                    UserVars.set(this, n, v)
                    refreshScreen()
                }
            }
            .setNegativeButton("CANCEL", null)
        if (existing != null) {
            d.setNeutralButton("DELETE") { _, _ ->
                UserVars.remove(this, existing.name)
                refreshScreen()
            }
        }
        d.show()
    }

    private fun startServiceCompat() {
        try {
            val i = Intent(this, EventService::class.java)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        } catch (e: Exception) {
            EventLog.push("[ui] service start failed: ${e.message}")
        }
    }

    private fun isServiceRunning(): Boolean {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return am.getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == EventService::class.java.name }
    }

    private fun refreshScreen() {
        val rules = RuleStore.load(this)
        view.rules = rules
        view.armedCount = rules.count { it.enabled }
        view.logs = EventLog.snapshot(18)
        view.rootOk = RootBridge.available == true
        view.rootChecked = RootBridge.available != null
        view.running = isServiceRunning()
        view.userVars = UserVars.entries(this).map {
            TerminalView.VarRow(it.first, it.second, UserVars.isDiskName(it.first))
        }
        view.invalidate()
    }

    private fun updateStats() {
        val mem = Debug.MemoryInfo()
        Debug.getMemoryInfo(mem)
        view.ramText = "PSS ${mem.totalPss / 1024}MB"
        view.battText = "${EventHub.batteryNow(this)}%"
        view.timeText = SimpleDateFormat("HH:mm", Locale.US).format(Date())
        val (_, ramPct) = SysStats.mem()
        view.ramPctText = "RAM $ramPct%"
        val freeMb = SysStats.diskFreeMb()
        view.diskText = if (freeMb >= 1024) "DSK ${freeMb / 1024}G" else "DSK ${freeMb}M"
        val t = readProcStat()
        if (cpuRef != 0L && t != 0L) view.cpuText = "CPU ${(t - cpuRef).coerceIn(0, 200)}%"
        cpuRef = t
        refreshScreen()
        handler.postDelayed({ updateStats() }, 2000)
    }

    private fun readProcStat(): Long = try {
        val content = File("/proc/self/stat").readText()
        val idx = content.lastIndexOf(')')
        val rest = content.substring(idx + 2).split(' ').filter { it.isNotBlank() }
        rest[11].toLong() + rest[12].toLong()
    } catch (e: Exception) {
        0L
    }
}
