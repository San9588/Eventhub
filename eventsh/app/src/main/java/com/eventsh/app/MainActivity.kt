package com.eventsh.app

import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
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
import android.widget.TextView
import com.eventsh.app.engine.EventCatalog
import com.eventsh.app.engine.EventHub
import com.eventsh.app.engine.EventLog
import com.eventsh.app.engine.RootBridge
import com.eventsh.app.engine.Rule
import com.eventsh.app.engine.RuleStore
import com.eventsh.app.engine.Scheduler
import com.eventsh.app.engine.SysStats
import com.eventsh.app.engine.UserVars
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
        view.onAddRule = { ruleDialog(null) }
        view.onAddTimer = { timerDialog() }
    }

    private fun ruleDialog(existing: Rule?) {
        val isTimer = existing?.isOneShotTimer == true || existing?.isDailyTimer == true
        val labelEt = editText("label")
        val eventTv = TextView(this).apply {
            textSize = 18f
            setPadding(8, 14, 8, 14)
            if (isTimer) {
                text = existing?.event ?: "timer"
                setTextColor(0xFF3C7852.toInt())
            } else {
                text = existing?.event ?: "(choose event)"
                setTextColor(0xFF00FF6E.toInt())
                setOnClickListener { pickEvent { ev -> text = ev } }
            }
        }
        val cdEt = editText("cooldown seconds (0)")
        val rtEt = editText("retries on failure (0)")
        val taskEt = editText("termux task name")
        val textEt = editText("notify text (%VAR% ok)")
        val rootEt = editText("root command")
        val filterEt = editText("filter (substring)")
        val notifyCb = checkBox("show notification")
        if (existing != null) {
            labelEt.setText(existing.label)
            cdEt.setText(existing.cooldownSec.toString())
            rtEt.setText(existing.retries.toString())
            taskEt.setText(existing.taskName)
            textEt.setText(existing.notifyText)
            rootEt.setText(existing.rootCmd)
            filterEt.setText(existing.filter)
            notifyCb.isChecked = existing.notify
        } else {
            notifyCb.isChecked = true
        }
        val ll = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(eventTv)
            addView(labelEt)
            addView(cdEt)
            addView(rtEt)
            addView(taskEt)
            addView(textEt)
            addView(rootEt)
            addView(filterEt)
            addView(notifyCb)
        }
        val d = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "ADD RULE" else "EDIT RULE")
            .setMessage("tap event to choose (custom = any broadcast action)")
            .setView(ll)
            .setPositiveButton("SAVE") { _, _ ->
                val event = if (isTimer) (existing?.event ?: "timer")
                else eventTv.text.toString().trim()
                if (event.isEmpty() || event == "(choose event)") {
                    EventLog.push("[ui] choose an event first")
                    return@setPositiveButton
                }
                val base = existing ?: Rule(
                    id = "r_" + UUID.randomUUID().toString().take(8),
                    event = event, label = "NEW.RULE"
                )
                val newRule = base.copy(
                    label = labelEt.text.toString().trim().ifBlank { "RULE" },
                    event = event,
                    enabled = true,
                    cooldownSec = cdEt.text.toString().toLongOrNull() ?: 0L,
                    retries = (rtEt.text.toString().toIntOrNull() ?: 0).coerceIn(0, 10),
                    taskName = taskEt.text.toString().trim(),
                    notifyText = textEt.text.toString(),
                    rootCmd = rootEt.text.toString().trim(),
                    filter = filterEt.text.toString().trim(),
                    notify = notifyCb.isChecked
                )
                val cur = RuleStore.load(this).toMutableList()
                val i = cur.indexOfFirst { it.id == base.id }
                if (i >= 0) cur[i] = newRule else cur.add(newRule)
                RuleStore.save(this, cur)
                if (newRule.isOneShotTimer || newRule.isDailyTimer) Scheduler.schedule(this, newRule)
                if (isServiceRunning()) EventHub.resync(this)
                refreshScreen()
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
