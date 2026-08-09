package com.eventsh.app

import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.eventsh.app.engine.Actions
import com.eventsh.app.engine.AppCtx
import com.eventsh.app.engine.CondSpec
import com.eventsh.app.engine.Ctx
import com.eventsh.app.engine.DayCtx
import com.eventsh.app.engine.Dispatcher
import com.eventsh.app.engine.EventCtx
import com.eventsh.app.engine.EventHub
import com.eventsh.app.engine.EventLog
import com.eventsh.app.engine.LocationCtx
import com.eventsh.app.engine.Permissions
import com.eventsh.app.engine.Profile
import com.eventsh.app.engine.RootBridge
import com.eventsh.app.engine.Store
import com.eventsh.app.engine.SysStats
import com.eventsh.app.engine.Task
import com.eventsh.app.engine.TimeCtx
import com.eventsh.app.engine.UserVars
import com.eventsh.app.engine.VarCtx
import com.eventsh.app.service.EventService
import com.eventsh.app.ui.C
import com.eventsh.app.ui.UI
import java.io.File
import java.util.Locale

// ============================================================================
//  EVENTSH - MainActivity FILE MAP
// ----------------------------------------------------------------------------
//  This file keeps ONLY the activity core: fields, lifecycle, tab bar / top bar,
//  the list adapters, screen refresh + stats, the service helpers and the shared
//  UI helpers. Every other feature lives in its own file (same package) as a
//  Kotlin EXTENSION FUNCTION on MainActivity, so behaviour is unchanged.
//
//  FILE MAP
//    MainActivity.kt            <- this file (core UI + adapters + refresh)
//    MainSettingsUi.kt          Settings tab UI: buildSettings(), switchRow(),
//                               actionRowContent(), cardContainer(), matchWrap()
//    MainProfileUi.kt           Profile + task UI: toggleProfile(), deleteProfile(),
//                               exportRules()/importRules(), profileDialog(),
//                               taskPickDialog(), openTaskEditor()
//    MainContextEditors.kt      Trigger/context dialogs: eventCtxDialog(),
//                               timeCtxDialog(), dayCtxDialog(), varCtxDialog(),
//                               appCtxDialog(), locationCtxDialog(), appPick(),
//                               pickEvent()
//    MainDialogs.kt             timerDialog(), varDialog(), showPermissionsDialog()
//
//  Where to add new code:
//    - a new settings row .......... MainSettingsUi.kt  -> buildSettings()
//    - a new profile action ........ MainProfileUi.kt
//    - a new trigger type .......... MainContextEditors.kt
//    - a new dialog ................ MainDialogs.kt
//    - a new list / adapter ........ this file, next to the other adapters
// ============================================================================

class MainActivity : Activity() {

    // ------------------------------------------------------------ tabs
    private val TAB_NAMES = arrayOf("Profiles", "Tasks", "Vars", "Log", "Settings")
    private val TAB_PROFILES = 0
    private val TAB_TASKS = 1
    private val TAB_VARS = 2
    private val TAB_LOG = 3
    private val TAB_SETTINGS = 4

    internal val handler = Handler(Looper.getMainLooper())
    private val refreshRunnable = Runnable { refreshScreen() }
    private var cpuRef = 0L
    internal var permDialog: AlertDialog? = null
    internal val permRows = mutableListOf<Pair<Permissions.Need, TextView>>()
    private var resumed = false
    private var currentTab = 0
    internal var suppressSwitch = false
    private val expandedIds = HashSet<String>()

    internal val FILE_EVENTS = setOf(
        "file_modified", "file_opened", "file_closed",
        "file_deleted", "file_moved", "file_attr"
    )
    internal val REQ_FILE_PICK = 7002
    internal val REQ_OPEN_BACKUP = 7003
    internal val REQ_CREATE_BACKUP = 7004
    @Volatile internal var pendingFilePick: ((String?) -> Unit)? = null
    @Volatile internal var pendingBackupText: String? = null

    // data
    private var profiles: List<Profile> = emptyList()
    internal var tasks: List<Task> = emptyList()
    private var logs: List<String> = emptyList()
    internal var running = false
    private var rootOk = false
    private var rootChecked = false
    private var userVars: List<VarEntry> = emptyList()
    private var ramText = "PSS 0MB"
    private var cpuText = "CPU 0.0%"
    private var battText = "--%"

    // view refs
    internal lateinit var contentFrame: FrameLayout
    private lateinit var tabIndicators: List<View>
    private lateinit var profileList: ListView
    private lateinit var taskList: ListView
    private lateinit var varList: ListView
    private lateinit var logList: ListView
    internal lateinit var settingsScroll: ScrollView
    private lateinit var profileEmpty: TextView
    private lateinit var taskEmpty: TextView
    private lateinit var varEmpty: TextView
    private lateinit var logEmpty: TextView
    private lateinit var fabAdd: View
    private lateinit var fabAi: View
    private lateinit var svcChip: TextView
    private lateinit var rootChip: TextView
    private lateinit var statusChip: TextView
    internal lateinit var aboutText: TextView
    internal lateinit var svcSwitch: Switch
    internal lateinit var svcSwitchRow: TextView
    internal lateinit var autoSwitch: Switch
    internal lateinit var rootStatusTv: TextView
    internal lateinit var shizukuStatusTv: TextView
    internal lateinit var usageStatusTv: TextView
    internal lateinit var notifStatusTv: TextView
    internal lateinit var overlayStatusTv: TextView
    internal lateinit var exactStatusTv: TextView
    internal lateinit var battOptStatusTv: TextView
    internal lateinit var locStatusTv: TextView

    private lateinit var profileAdapter: ProfileAdapter
    private lateinit var taskAdapter: TaskAdapter
    private lateinit var varAdapter: VarAdapter
    private lateinit var logAdapter: LogAdapter

    data class VarEntry(val name: String, val value: String, val disk: Boolean)

    // ------------------------------------------------------------ lifecycle
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 10)
        }
        UserVars.init(this)
        buildUi()
        if (Store.autostart(this) && !isServiceRunning()) startServiceCompat()
        RootBridge.checkAsync()
        refreshScreen()
        updateStats()
        EventLog.listener = {
            // coalesce bursts of log pushes into a single UI refresh
            handler.removeCallbacks(refreshRunnable)
            handler.postDelayed(refreshRunnable, 150)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        ActionEditor.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_FILE_PICK) {
            val cb = pendingFilePick
            pendingFilePick = null
            if (cb != null) {
                cb(if (resultCode == Activity.RESULT_OK) data?.getStringExtra(FilePickerActivity.RESULT_PATH) else null)
            }
        }
        if (requestCode == REQ_OPEN_BACKUP && resultCode == Activity.RESULT_OK && data?.data != null) {
            try {
                val text = contentResolver.openInputStream(data.data!!)
                    ?.bufferedReader()?.use { it.readText() } ?: ""
                confirmImport(text)
            } catch (e: Exception) {
                EventLog.push("[bak] read backup failed: ${e.message?.take(80) ?: "error"}")
            }
        }
        if (requestCode == REQ_CREATE_BACKUP && resultCode == Activity.RESULT_OK && data?.data != null) {
            val text = pendingBackupText
            pendingBackupText = null
            if (text != null) {
                try {
                    contentResolver.openOutputStream(data.data!!)?.use { os ->
                        os.write(text.toByteArray())
                    }
                    EventLog.push("[bak] backup saved to your chosen location")
                } catch (e: Exception) {
                    EventLog.push("[bak] save failed: ${e.message?.take(80) ?: "error"}")
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        refreshPermissions()
        refreshScreen()
        updateStats()
    }

    override fun onPause() {
        resumed = false
        super.onPause()
    }

    override fun onDestroy() {
        EventLog.listener = null
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshPermissions()
    }

    internal fun refreshPermissions() {
        val d = permDialog ?: return
        if (!d.isShowing) { permDialog = null; return }
        var allGranted = true
        for ((need, tv) in permRows) {
            if (need.granted(this)) {
                tv.text = "[ ${need.label} ] OK"
                tv.setTextColor(C.ok)
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

    // ------------------------------------------------------------ UI build
    internal fun dp(v: Float): Int = UI.dp(this, v)

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C.bg)
        }
        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                )
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
            root.requestApplyInsets()
        }
        root.addView(buildTopBar())
        root.addView(buildTabBar())
        contentFrame = FrameLayout(this).apply { setBackgroundColor(C.bg) }
        root.addView(contentFrame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        buildLists()
        buildSettings()
        buildFabs()
        selectTab(TAB_PROFILES)
    }

    private fun chip(label: String, color: Int): TextView = TextView(this).apply {
        text = label
        textSize = 11f
        setTextColor(color)
        typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(8f), dp(3f), dp(8f), dp(3f))
        background = UI.rounded(C.chipBg, 8f)
    }

    private fun buildTopBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(12f), dp(16f), dp(8f))
            setBackgroundColor(C.surface)
        }
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(
            UI.text(this, "EVENTSH", 20f, C.primary, bold = true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        svcChip = chip("SERVICE OFF", C.disabled)
        titleRow.addView(svcChip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(8f)
        })
        rootChip = chip("SU:?", C.hint)
        titleRow.addView(rootChip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(8f)
        })
        bar.addView(titleRow)
        statusChip = UI.text(this, "", 12f, C.textSec)
        bar.addView(statusChip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(4f)
        })
        return bar
    }

    private fun buildTabBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(C.surface)
        }
        val indicators = ArrayList<View>()
        for ((i, name) in TAB_NAMES.withIndex()) {
            val ind = View(this).apply { setBackgroundColor(C.primary) }
            val tv = UI.text(this, name, 13f, C.textSec, bold = true).apply {
                gravity = Gravity.CENTER
            }
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
                addView(ind, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3f)))
            }
            cell.setOnClickListener { selectTab(i) }
            bar.addView(cell, LinearLayout.LayoutParams(0, dp(52f), 1f))
            indicators.add(ind)
        }
        tabIndicators = indicators
        return bar
    }

    private fun selectTab(i: Int) {
        currentTab = i
        for (j in TAB_NAMES.indices) {
            tabIndicators[j].alpha = if (j == i) 1f else 0.15f
        }
        profileList.visibility = if (i == TAB_PROFILES) View.VISIBLE else View.GONE
        taskList.visibility = if (i == TAB_TASKS) View.VISIBLE else View.GONE
        varList.visibility = if (i == TAB_VARS) View.VISIBLE else View.GONE
        logList.visibility = if (i == TAB_LOG) View.VISIBLE else View.GONE
        settingsScroll.visibility = if (i == TAB_SETTINGS) View.VISIBLE else View.GONE
        fabAdd.visibility = if (i == TAB_SETTINGS || i == TAB_LOG) View.GONE else View.VISIBLE
        fabAi.visibility = fabAdd.visibility
        refreshEmptyViews()
    }

    private fun emptyLabel(msg: String): TextView = TextView(this).apply {
        text = msg
        textSize = 14f
        gravity = Gravity.CENTER
        setTextColor(C.hint)
        setLineSpacing(dp(4f).toFloat(), 1f)
    }

    private fun buildLists() {
        profileEmpty = emptyLabel("No profiles yet\nTap + to create one")
        taskEmpty = emptyLabel("No tasks yet\nTap + to create one")
        varEmpty = emptyLabel("No variables yet\nTap + to add one")
        logEmpty = emptyLabel("No events logged yet")

        profileList = ListView(this).apply {
            divider = null
            dividerHeight = 0
            setSelector(android.R.color.transparent)
            setBackgroundColor(C.bg)
        }
        taskList = ListView(this).apply {
            divider = null
            dividerHeight = 0
            setSelector(android.R.color.transparent)
            setBackgroundColor(C.bg)
        }
        varList = ListView(this).apply {
            divider = null
            dividerHeight = 0
            setSelector(android.R.color.transparent)
            setBackgroundColor(C.bg)
        }
        logList = ListView(this).apply {
            divider = null
            dividerHeight = 0
            setSelector(android.R.color.transparent)
            setBackgroundColor(C.bg)
        }

        profileAdapter = ProfileAdapter()
        taskAdapter = TaskAdapter()
        varAdapter = VarAdapter()
        logAdapter = LogAdapter()
        profileList.adapter = profileAdapter
        taskList.adapter = taskAdapter
        varList.adapter = varAdapter
        logList.adapter = logAdapter
        profileList.setOnItemClickListener { _, _, pos, _ -> toggleExpand(pos) }
        taskList.setOnItemClickListener { _, _, pos, _ -> if (pos in tasks.indices) openTaskEditor(tasks[pos]) }
        varList.setOnItemClickListener { _, _, pos, _ -> if (pos in userVars.indices) varDialog(userVars[pos]) }

        contentFrame.addView(profileList, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        contentFrame.addView(taskList, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        contentFrame.addView(varList, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        contentFrame.addView(logList, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        contentFrame.addView(profileEmpty, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        contentFrame.addView(taskEmpty, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        contentFrame.addView(varEmpty, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        contentFrame.addView(logEmpty, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun buildFabs() {
        fabAdd = ImageView(this).apply {
            setImageResource(R.drawable.ic_add)
            setColorFilter(C.onPrimary)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(C.primary)
            }
            elevation = dp(6f).toFloat()
            setPadding(dp(16f), dp(16f), dp(16f), dp(16f))
            contentDescription = "Add"
            setOnClickListener { onFabAdd() }
        }
        val lpAdd = FrameLayout.LayoutParams(dp(56f), dp(56f))
        lpAdd.gravity = Gravity.BOTTOM or Gravity.END
        lpAdd.setMargins(0, 0, dp(16f), dp(24f))
        fabAdd.layoutParams = lpAdd
        contentFrame.addView(fabAdd)

        fabAi = ImageView(this).apply {
            setImageResource(R.drawable.ic_ai)
            setColorFilter(C.onPrimary)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(C.accent)
            }
            elevation = dp(6f).toFloat()
            setPadding(dp(12f), dp(12f), dp(12f), dp(12f))
            contentDescription = "AI"
            setOnClickListener {
                android.widget.Toast.makeText(this@MainActivity, "AI generation coming soon", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        val lpAi = FrameLayout.LayoutParams(dp(48f), dp(48f))
        lpAi.gravity = Gravity.BOTTOM or Gravity.END
        lpAi.setMargins(0, 0, dp(20f), dp(88f))
        fabAi.layoutParams = lpAi
        contentFrame.addView(fabAi)
    }

    private fun refreshEmptyViews() {
        profileEmpty.visibility = if (currentTab == TAB_PROFILES && profiles.isEmpty()) View.VISIBLE else View.GONE
        taskEmpty.visibility = if (currentTab == TAB_TASKS && tasks.isEmpty()) View.VISIBLE else View.GONE
        varEmpty.visibility = if (currentTab == TAB_VARS && userVars.isEmpty()) View.VISIBLE else View.GONE
        logEmpty.visibility = if (currentTab == TAB_LOG && logs.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun onFabAdd() {
        when (currentTab) {
            TAB_PROFILES -> profileDialog(null)
            TAB_TASKS -> openTaskEditor(null)
            TAB_VARS -> varDialog(null)
        }
    }

    // ------------------------------------------------------------ adapters
    private fun buildCtxChip(c: Ctx): View {
        val (tag, color) = when (c) {
            is EventCtx -> "EV" to C.primary
            is TimeCtx -> "TM" to C.accent
            is DayCtx -> "DY" to C.warning
            is VarCtx -> "VA" to C.warning
            is AppCtx -> "AP" to C.accent
            is LocationCtx -> "LC" to C.warning
            else -> "??" to C.hint
        }
        return TextView(this).apply {
            text = "[$tag] ${c.summary()}"
            textSize = 12f
            setTextColor(color)
            setPadding(dp(8f), dp(5f), dp(8f), dp(5f))
            background = UI.rounded(C.chipBg, 8f)
        }
    }

    private fun taskActionLines(task: Task): List<Pair<Int, String>> {
        val out = ArrayList<Pair<Int, String>>()
        for (a in task.actions) {
            val icon = when (a.type) {
                Actions.SCRIPT, Actions.SHELL, Actions.ROOT -> R.drawable.ic_terminal
                Actions.INTENT -> R.drawable.ic_send
                Actions.NOTIFY, Actions.FLASH -> R.drawable.ic_notify
                Actions.VAR_SET, Actions.VAR_SPLIT, Actions.VAR_JOIN, Actions.VAR_QUERY,
                Actions.ARRAY_SET, Actions.ARRAY_PUSH, Actions.ARRAY_PROCESS, Actions.ARRAY_POP, Actions.ARRAY_CLEAR -> R.drawable.ic_var
                Actions.IF, Actions.ELSE, Actions.END_IF, Actions.FOR, Actions.END_FOR -> R.drawable.ic_list
                else -> R.drawable.ic_settings
            }
            val cond = a.condTerms()?.let { (t, j) -> CondSpec.summary(t, j) }
            val labelPrefix = if (a.label.isBlank()) "" else "{${a.label}}  "
            out += icon to ("$labelPrefix${a.typeLabel()}  ${a.summary()}" +
                (if (cond.isNullOrBlank()) "" else "   [IF $cond]"))
        }
        return out
    }

    private fun cardWrap(card: View): View {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6f), dp(3f), dp(6f), dp(3f))
            setBackgroundColor(C.bg)
            addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        wrap.layoutParams = AbsListView.LayoutParams(
            AbsListView.LayoutParams.MATCH_PARENT,
            AbsListView.LayoutParams.WRAP_CONTENT
        )
        return wrap
    }

    private fun actionRow(icon: Int, text: String, color: Int): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f), dp(5f), dp(12f), dp(5f))
        }
        val iv = ImageView(this).apply {
            setImageResource(icon)
            setColorFilter(color)
        }
        val lp = LinearLayout.LayoutParams(dp(18f), dp(18f))
        row.addView(iv, lp)
        row.addView(
            UI.text(this, text, 13f, C.text).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(10f)
            }
        )
        return row
    }

    inner class ProfileAdapter : BaseAdapter() {
        override fun getCount() = profiles.size
        override fun getItem(pos: Int) = profiles[pos]
        override fun getItemId(pos: Int) = pos.toLong()

        override fun getView(pos: Int, convertView: View?, parent: ViewGroup): View {
            val p = profiles[pos]
            val enabled = p.enabled
            val titleColor = if (enabled) C.text else C.textSec
            val accent = if (enabled) C.primary else C.disabled
            val task = tasks.find { it.id == p.taskId }

            val card = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
                background = UI.rounded(C.card, 14f, C.border, 1f)
            }

            // header: icon + name + switch
            val header = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val icon = ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_bolt)
                setColorFilter(accent)
            }
            header.addView(icon, LinearLayout.LayoutParams(dp(24f), dp(24f)))
            header.addView(
                UI.text(this@MainActivity, p.name, 16f, titleColor, bold = true).apply {
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(10f)
                }
            )
            val sw = Switch(this@MainActivity)
            sw.isChecked = enabled
            sw.setOnCheckedChangeListener { _, _ -> this@MainActivity.toggleProfile(p) }
            header.addView(sw)
            card.addView(header)

            // subtitle: context summary + linked task name
            val sub = p.contextLine().ifBlank {
                if (p.isDailyTimer) "daily ${p.daily}" else if (p.isOneShotTimer) "one-shot timer" else "no trigger set"
            }
            val taskTxt = task?.name ?: if (p.taskId.isBlank()) "" else "(unlinked task)"
            card.addView(
                UI.text(this@MainActivity, sub + (if (taskTxt.isNotBlank()) "   -> $taskTxt" else ""), 13f, C.textSec).apply {
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(4f)
                }
            )

            if (p.id in expandedIds) {
                // left = triggers | right = linked task
                val twoCol = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                }
                val colL = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                }
                val colR = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                }
                colL.addView(UI.text(this@MainActivity, "TRIGGERS", 11f, C.accent, bold = true).apply { letterSpacing = 0.08f })
                if (p.contexts.isEmpty()) {
                    colL.addView(UI.text(this@MainActivity, "none", 13f, C.hint).apply {
                        setPadding(0, dp(4f), 0, 0)
                    })
                } else {
                    for (c in p.contexts) {
                        colL.addView(buildCtxChip(c), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                            topMargin = dp(4f)
                        })
                    }
                }
                colR.addView(UI.text(this@MainActivity, "TASK", 11f, C.accent, bold = true).apply { letterSpacing = 0.08f })
                colR.addView(
                    UI.text(this@MainActivity, task?.name ?: "(no task linked)", 14f, if (task != null) C.primary else C.hint, bold = task != null).apply {
                        setPadding(0, dp(4f), 0, 0)
                    }
                )
                if (task != null) {
                    colR.addView(
                        UI.text(this@MainActivity, "${task.actions.size} action(s)", 12f, C.hint),
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                            topMargin = dp(2f)
                        }
                    )
                }
                twoCol.addView(colL, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                twoCol.addView(colR, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(12f)
                })
                card.addView(twoCol, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(10f)
                })

                // linked task actions
                val acts = task?.let { taskActionLines(it) } ?: emptyList()
                if (acts.isNotEmpty()) {
                    val actHeader = UI.text(this@MainActivity, "ACTIONS", 11f, C.accent, bold = true).apply {
                        letterSpacing = 0.08f
                    }
                    card.addView(actHeader, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = dp(10f)
                    })
                    for ((ic, txt) in acts) card.addView(actionRow(ic, txt, C.primary))
                }

                // buttons
                val btnRow = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.END
                }
                btnRow.addView(materialButton("TEST", C.accent, { this@MainActivity.testProfile(p) }))
                btnRow.addView(materialButton("EDIT", C.primary, { this@MainActivity.profileDialog(p) }), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = dp(8f)
                })
                btnRow.addView(materialButton("DELETE", C.danger, { this@MainActivity.deleteProfile(p) }), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = dp(8f)
                })
                card.addView(btnRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(8f)
                })
            }

            card.setOnClickListener { toggleExpand(p.id) }
            return cardWrap(card)
        }
    }

    private fun materialButton(label: String, color: Int, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(color)
            setPadding(dp(10f), dp(5f), dp(10f), dp(5f))
            background = UI.rounded(C.chipBg, 8f)
            setOnClickListener { onClick() }
        }

    private fun toggleExpand(id: String) {
        if (!expandedIds.add(id)) expandedIds.remove(id)
        profileAdapter.notifyDataSetChanged()
    }

    private fun toggleExpand(pos: Int) {
        if (pos in profiles.indices) toggleExpand(profiles[pos].id)
    }

    inner class TaskAdapter : BaseAdapter() {
        override fun getCount() = tasks.size
        override fun getItem(pos: Int) = tasks[pos]
        override fun getItemId(pos: Int) = pos.toLong()

        override fun getView(pos: Int, convertView: View?, parent: ViewGroup): View {
            val t = tasks[pos]
            val usedBy = profiles.count { it.taskId == t.id }
            val enabled = t.enabled

            val card = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
                background = UI.rounded(C.card, 14f, C.border, 1f)
            }
            val header = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val icon = ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_list)
                setColorFilter(if (enabled) C.primary else C.disabled)
            }
            header.addView(icon, LinearLayout.LayoutParams(dp(24f), dp(24f)))
            header.addView(
                UI.text(this@MainActivity, t.name, 16f, if (enabled) C.text else C.textSec, bold = true).apply {
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(10f)
                }
            )
            header.addView(playStopButton(t))
            header.addView(iconButton(R.drawable.ic_edit, C.textSec, { this@MainActivity.openTaskEditor(t) }))
            card.addView(header)

            val acts = taskActionLines(t)
            card.addView(
                UI.text(this@MainActivity, "${t.actions.size} action(s)  ·  retry ${t.retries}  ·  used by $usedBy profile(s)" + (if (enabled) "" else "  ·  DISABLED"), 12f, C.hint),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(4f)
                }
            )
            if (acts.isNotEmpty()) {
                card.addView(UI.vsep(this@MainActivity, dp(6f)))
                for ((ic, txt) in acts) card.addView(actionRow(ic, txt, if (enabled) C.primary else C.textSec))
            }
            card.setOnClickListener { this@MainActivity.openTaskEditor(t) }
            return cardWrap(card)
        }
    }

    private fun iconButton(icon: Int, color: Int, onClick: () -> Unit): View =
        ImageView(this).apply {
            setImageResource(icon)
            setColorFilter(color)
            setPadding(dp(6f), dp(6f), dp(6f), dp(6f))
            contentDescription = "action"
            setOnClickListener { onClick() }
        }

    private fun playStopButton(t: Task): View {
        val running = Dispatcher.isTaskRunning(t.id)
        return TextView(this).apply {
            text = if (running) "STOP" else "RUN"
            textSize = 12f
            setPadding(dp(10f), dp(4f), dp(10f), dp(4f))
            setTextColor(if (running) C.danger else C.accent)
            background = UI.rounded(C.card, 10f, if (running) C.danger else C.accent, 1f)
            setOnClickListener {
                if (Dispatcher.isTaskRunning(t.id)) {
                    Dispatcher.stopTask(t.id)
                    EventLog.push("[ui] stopping ${t.name}")
                } else {
                    EventLog.push("[ui] running ${t.name} (${t.actions.size} actions)")
                    Dispatcher.runTask(this@MainActivity, t.id)
                }
                handler.postDelayed({ refreshScreen() }, 350)
            }
        }
    }

    inner class VarAdapter : BaseAdapter() {
        override fun getCount() = userVars.size
        override fun getItem(pos: Int) = userVars[pos]
        override fun getItemId(pos: Int) = pos.toLong()

        override fun getView(pos: Int, convertView: View?, parent: ViewGroup): View {
            val v = userVars[pos]
            val card = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
                background = UI.rounded(C.card, 14f, C.border, 1f)
            }
            val icon = ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_var)
                setColorFilter(if (v.disk) C.warning else C.accent)
            }
            card.addView(icon, LinearLayout.LayoutParams(dp(22f), dp(22f)))
            card.addView(
                UI.text(this@MainActivity, v.name, 15f, if (v.disk) C.warning else C.primary, bold = true),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = dp(10f)
                }
            )
            card.addView(
                UI.text(this@MainActivity, v.value.ifBlank { "(empty)" }, 14f, if (v.value.isBlank()) C.hint else C.text).apply {
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(12f)
                }
            )
            return cardWrap(card)
        }
    }

    inner class LogAdapter : BaseAdapter() {
        override fun getCount() = logs.size
        override fun getItem(pos: Int) = logs[pos]
        override fun getItemId(pos: Int) = pos.toLong()

        override fun getView(pos: Int, convertView: View?, parent: ViewGroup): View {
            val line = logs[pos]
            val color = when {
                line.contains("FAILED") || line.contains("failed") -> C.danger
                line.contains("[perm]") -> C.warning
                line.startsWith("[") && line.contains("]") -> C.text
                else -> C.textSec
            }
            val row = UI.text(this@MainActivity, line, 13f, color).apply {
                setPadding(dp(14f), dp(8f), dp(14f), dp(8f))
            }
            return cardWrap(row)
        }
    }

    // ------------------------------------------------------------ refresh / stats
    internal fun refreshScreen() {
        profiles = Store.profiles(this)
        tasks = Store.tasks(this)
        logs = EventLog.snapshot(60)
        running = isServiceRunning()
        rootOk = RootBridge.available == true
        rootChecked = RootBridge.available != null
        userVars = UserVars.entries(this).map { VarEntry(it.first, it.second, UserVars.isDiskName(it.first)) }

        svcChip.text = if (running) "SERVICE ON" else "SERVICE OFF"
        svcChip.setTextColor(if (running) C.primary else C.disabled)
        rootChip.text = if (rootChecked) (if (rootOk) "SU:ON" else "SU:OFF") else "SU:?"
        rootChip.setTextColor(if (rootOk) C.warning else if (rootChecked) C.hint else C.hint)
        val armed = profiles.count { it.enabled }
        statusChip.text = if (profiles.isEmpty()) "No profiles yet - tap + to begin"
        else "$armed/${profiles.size} armed  ·  ${profiles.count { it.timeCtx != null || it.isDailyTimer || it.isOneShotTimer }} timers  ·  ${tasks.size} task(s)"

        profileAdapter.notifyDataSetChanged()
        taskAdapter.notifyDataSetChanged()
        varAdapter.notifyDataSetChanged()
        logAdapter.notifyDataSetChanged()
        refreshEmptyViews()
        refreshSettings()
    }

    private fun refreshSettings() {
        if (!::svcSwitch.isInitialized) return
        suppressSwitch = true
        svcSwitch.isChecked = running
        suppressSwitch = false
        svcSwitchRow.text = if (running) "listening for events" else "stopped - profiles idle"
        rootStatusTv.text = when {
            !rootChecked -> "?"
            rootOk -> "ON"
            else -> "OFF"
        }
        rootStatusTv.setTextColor(if (rootOk) C.ok else C.danger)
        val shiz = com.eventsh.app.engine.ShizukuClient.available
        shizukuStatusTv.text = if (shiz) "READY" else "TAP"
        shizukuStatusTv.setTextColor(if (shiz) C.ok else C.warning)
        val usageNeed = Permissions.Need("usage", "Usage access", "", Permissions.Kind.SPECIAL, settingsAction = android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS)
        usageStatusTv.text = if (usageNeed.granted(this)) "OK" else "SET"
        usageStatusTv.setTextColor(if (usageNeed.granted(this)) C.ok else C.warning)
        val notifNeed = Permissions.Need("notif_listener", "Notification access", "", Permissions.Kind.SPECIAL, settingsAction = android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        notifStatusTv.text = if (notifNeed.granted(this)) "OK" else "SET"
        notifStatusTv.setTextColor(if (notifNeed.granted(this)) C.ok else C.warning)
        val overlayOk = com.eventsh.app.engine.Flash.canOverlay(this)
        overlayStatusTv.text = if (overlayOk) "OK" else "SET"
        overlayStatusTv.setTextColor(if (overlayOk) C.ok else C.warning)
        val am = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        val exactOk = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        exactStatusTv.text = if (exactOk) "OK" else "SET"
        exactStatusTv.setTextColor(if (exactOk) C.ok else C.warning)
        val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val battOk = pm.isIgnoringBatteryOptimizations(packageName)
        battOptStatusTv.text = if (battOk) "OK" else "SET"
        battOptStatusTv.setTextColor(if (battOk) C.ok else C.warning)
        val locOk = checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        locStatusTv.text = if (locOk) "OK" else "SET"
        locStatusTv.setTextColor(if (locOk) C.ok else C.warning)
        if (::aboutText.isInitialized) {
            aboutText.text = "EVENTSH v0.1.0\n$ramText   $cpuText   battery $battText\nprofiles: ${profiles.count { it.enabled }} armed / ${profiles.size}"
        }
    }

    private fun updateStats() {
        if (!resumed) { cpuRef = 0L; return }
        val mem = Debug.MemoryInfo()
        Debug.getMemoryInfo(mem)
        ramText = "PSS ${mem.totalPss / 1024}MB"
        battText = "${EventHub.batteryNow(this)}%"
        val (_, ramPct) = SysStats.mem()
        val t = readProcStat()
        if (cpuRef != 0L && t != 0L) cpuText = "CPU ${(t - cpuRef).coerceIn(0, 200)}%"
        cpuRef = t
        if (currentTab == TAB_SETTINGS) refreshScreen()
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
    internal fun editText(hint: String): EditText = EditText(this).apply {
        this.hint = hint
        setHintTextColor(C.hint)
        setTextColor(C.text)
        textSize = 16f
        background = UI.rounded(C.surface, 10f, C.border, 1f)
        setPadding(dp(10f), dp(9f), dp(10f), dp(9f))
    }

    internal fun checkBox(text: String): CheckBox = CheckBox(this).apply {
        this.text = text
        setTextColor(C.text)
        textSize = 15f
    }

    internal fun sectionLabel(text: String): TextView =
        UI.text(this, text.uppercase(Locale.US), 12f, C.accent, bold = true).apply {
            letterSpacing = 0.1f
            setPadding(dp(4f), dp(14f), dp(4f), dp(6f))
        }

    internal fun ctxRow(text: String, color: Int, onClick: () -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 15f
            setTextColor(color)
            setPadding(dp(10f), dp(10f), dp(10f), dp(10f))
            background = UI.rounded(C.card, 10f, C.border, 1f)
            setOnClickListener { onClick() }
        }
    // ------------------------------------------------------------ service
    internal fun startServiceCompat() {
        try {
            val i = Intent(this, EventService::class.java)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        } catch (e: Exception) {
            EventLog.push("[ui] service start failed: ${e.message}")
        }
    }

    internal fun isServiceRunning(): Boolean {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return am.getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == EventService::class.java.name }
    }
}
