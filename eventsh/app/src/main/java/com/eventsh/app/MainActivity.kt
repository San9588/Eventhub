package com.eventsh.app

import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import com.eventsh.app.engine.EventHub
import com.eventsh.app.engine.EventLog
import com.eventsh.app.engine.Permissions
import com.eventsh.app.engine.Profile
import com.eventsh.app.engine.RootBridge
import com.eventsh.app.engine.Store
import com.eventsh.app.engine.SysStats
import com.eventsh.app.engine.Task
import com.eventsh.app.engine.UserVars
import com.eventsh.app.service.EventService
import com.eventsh.app.theme.ThemeController
import com.eventsh.app.ui.Maniflow
import com.eventsh.app.ui.Theme
import java.io.File

// ============================================================================
//  MANIFLOW - MainActivity CORE
// ----------------------------------------------------------------------------
//  This file keeps ONLY the activity core: fields, lifecycle, tab bar,
//  screen wiring + refresh, the shared UI helpers and service helpers.
//
//  FILE MAP
//    MainActivity.kt         <- this file (core UI + refresh + helpers)
//    MainHomeUi.kt           Home tab: hero header, chat card, snapshot, flows
//    MainLists.kt            Tasks / Vars / Log adapters + list row helpers
//    MainSettingsUi.kt       Settings tab UI
//    MainProfileUi.kt        Profile + task actions + profile editor dialog
//    MainContextEditors.kt   Trigger/context dialogs
//    MainPickList.kt         App picker + event picker
//    MainDialogs.kt          timer / variable / permissions dialogs
// ============================================================================

class MainActivity : Activity() {

    // ------------------------------------------------------------ tabs
    internal val TAB_NAMES = arrayOf("Profiles", "Tasks", "Vars", "Log", "Settings")
    internal val TAB_HOME = 0
    internal val TAB_TASKS = 1
    internal val TAB_VARS = 2
    internal val TAB_LOG = 3
    internal val TAB_SETTINGS = 4

    internal val handler = Handler(Looper.getMainLooper())
    private val refreshRunnable = Runnable { refreshScreen() }
    private var cpuRef = 0L
    internal var permDialog: AlertDialog? = null
    internal val permRows = mutableListOf<Pair<Permissions.Need, TextView>>()
    private var resumed = false
    internal var currentTab = 0
    internal val expandedIds = HashSet<String>()

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
    internal var profiles: List<Profile> = emptyList()
    internal var tasks: List<Task> = emptyList()
    internal var logs: List<String> = emptyList()
    internal var running = false
    internal var rootOk = false
    internal var rootChecked = false
    internal var userVars: List<VarEntry> = emptyList()
    private var ramText = "PSS 0MB"
    private var cpuText = "CPU 0.0%"
    internal var battText = "--%"

    // view refs
    internal lateinit var contentFrame: FrameLayout
    private lateinit var tabIndicators: List<View>
    private lateinit var homeRoot: View
    private lateinit var taskRoot: View
    private lateinit var varRoot: View
    private lateinit var logRoot: View
    internal lateinit var homeList: ListView
    internal lateinit var taskList: ListView
    internal lateinit var varList: ListView
    internal lateinit var logList: ListView
    internal lateinit var settingsRoot: View
    internal lateinit var settingsScroll: ScrollView
    internal lateinit var homeHeaderStatus: TextView
    internal lateinit var homeHeadline: TextView
    internal lateinit var homeEmpty: View
    internal lateinit var homePillService: TextView
    internal lateinit var homePillRoot: TextView
    internal lateinit var statFlows: TextView
    internal lateinit var statTasks: TextView
    internal lateinit var statBattery: TextView
    internal lateinit var taskEmpty: View
    internal lateinit var varEmpty: View
    internal lateinit var logEmpty: View
    private lateinit var fabAdd: View
    private lateinit var fabAi: View
    internal lateinit var aboutText: TextView
    internal lateinit var svcSwitch: com.eventsh.app.ui.ManiflowToggle
    internal lateinit var svcSwitchRow: TextView
    internal lateinit var autoSwitch: com.eventsh.app.ui.ManiflowToggle
    internal lateinit var rootStatusTv: TextView
    internal lateinit var shizukuStatusTv: TextView
    internal lateinit var usageStatusTv: TextView
    internal lateinit var notifStatusTv: TextView
    internal lateinit var overlayStatusTv: TextView
    internal lateinit var exactStatusTv: TextView
    internal lateinit var battOptStatusTv: TextView
    internal lateinit var locStatusTv: TextView
    internal lateinit var aiKeyField: EditText
    internal lateinit var aiKeyStatusTv: TextView
    internal lateinit var recentThemesBox: LinearLayout

    // Extension UI code reads the AI settings views via this instead of
    // ::isInitialized, which would need backing-field access from outside.
    internal fun aiSettingsUiReady(): Boolean = ::aiKeyStatusTv.isInitialized && ::recentThemesBox.isInitialized

    private var renderedGeneration = -1
    internal var aiThemesFingerprint = 0
    private var pendingOpenTab = -1
    private var themeReceiverRegistered = false
    private val themeResetReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (i?.action == ThemeController.ACTION_THEME_RESET) {
                ThemeController.restoreFromDisk(this@MainActivity)
                rebuildUi()
            }
        }
    }

    private lateinit var taskAdapter: TaskListAdapter
    private lateinit var varAdapter: VarListAdapter
    private lateinit var logAdapter: LogListAdapter
    internal lateinit var flowAdapter: FlowListAdapter

    data class VarEntry(val name: String, val value: String, val disk: Boolean)

    // ------------------------------------------------------------ lifecycle
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 10)
        }
        UserVars.init(this)
        pendingOpenTab = intent?.getIntExtra("open_tab", -1) ?: -1
        ThemeController.restoreFromDisk(this)
        buildUi()
        renderedGeneration = ThemeController.generation
        registerThemeResetReceiver()
        if (pendingOpenTab >= 0) selectTab(pendingOpenTab)
        if (Store.autostart(this) && !isServiceRunning()) startServiceCompat()
        RootBridge.checkAsync()
        refreshScreen()
        updateStats()
        EventLog.listener = {
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
        if (renderedGeneration != ThemeController.generation) {
            rebuildUi()
        } else {
            refreshScreen()
            updateStats()
        }
    }

    override fun onPause() {
        resumed = false
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val tab = intent.getIntExtra("open_tab", -1)
        if (tab in 0..TAB_NAMES.lastIndex) selectTab(tab)
    }

    override fun onDestroy() {
        EventLog.listener = null
        if (themeReceiverRegistered) {
            try { unregisterReceiver(themeResetReceiver) } catch (e: Exception) {}
            themeReceiverRegistered = false
        }
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
                tv.setTextColor(Theme.current.statGreen)
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
    internal fun dp(v: Float): Int = Maniflow.dpf(this, v)

    private fun buildUi() {
        val t = Theme.current
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(t.surfaceBg)
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
        contentFrame = FrameLayout(this).apply { setBackgroundColor(t.surfaceBg) }
        root.addView(contentFrame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(buildTabBar())
        setContentView(root)
        buildTabs()
        buildFabs()
        selectTab(TAB_HOME)
    }

    private fun buildTabBar(): View {
        val t = Theme.current
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Maniflow.rounded(this@MainActivity, t.surfaceBg, t.radiusHeader,
                radii = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, dp(t.radiusHeader.toFloat()).toFloat(), dp(t.radiusHeader.toFloat()).toFloat()))
            elevation = dp(2f).toFloat()
        }
        wrap.addView(Maniflow.divider(this))
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(t.surfaceBg)
        }
        val indicators = ArrayList<View>()
        for ((i, name) in TAB_NAMES.withIndex()) {
            val ind = View(this).apply { setBackgroundColor(t.accentPrimary) }
            val tv = Maniflow.text(this, name, 14f, if (i == currentTab) t.accentPrimary else t.textMuted, bold = true).apply {
                gravity = Gravity.CENTER
                tag = "tab.label.$i"
            }
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
                addView(ind, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3f)))
            }
            cell.setOnClickListener { selectTab(i) }
            bar.addView(cell, LinearLayout.LayoutParams(0, dp(54f), 1f))
            indicators.add(ind)
        }
        tabIndicators = indicators
        wrap.addView(bar)
        return wrap
    }

    internal fun selectTab(i: Int) {
        currentTab = i
        for (j in TAB_NAMES.indices) {
            tabIndicators[j].alpha = if (j == i) 1f else 0f
            (contentFrame.rootView.findViewWithTag<TextView>("tab.label.$j"))?.setTextColor(
                if (j == i) Theme.current.accentPrimary else Theme.current.textMuted
            )
        }
        homeRoot.visibility = if (i == TAB_HOME) View.VISIBLE else View.GONE
        taskRoot.visibility = if (i == TAB_TASKS) View.VISIBLE else View.GONE
        varRoot.visibility = if (i == TAB_VARS) View.VISIBLE else View.GONE
        logRoot.visibility = if (i == TAB_LOG) View.VISIBLE else View.GONE
        settingsRoot.visibility = if (i == TAB_SETTINGS) View.VISIBLE else View.GONE
        settingsScroll.visibility = if (i == TAB_SETTINGS) View.VISIBLE else View.GONE
        fabAdd.visibility = if (i == TAB_SETTINGS || i == TAB_LOG) View.GONE else View.VISIBLE
        fabAi.visibility = fabAdd.visibility
        refreshEmptyViews()
    }

    private fun registerThemeResetReceiver() {
        val filter = IntentFilter(ThemeController.ACTION_THEME_RESET)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(themeResetReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(themeResetReceiver, filter)
        }
        themeReceiverRegistered = true
    }

    /** Rebuilds the whole view hierarchy so every screen picks up the new theme. */
    internal fun rebuildUi() {
        val tab = currentTab
        buildUi()
        if (tab != TAB_HOME) selectTab(tab)
        renderedGeneration = ThemeController.generation
        refreshScreen()
    }

    private fun tabScaffold(headerTitle: String): Pair<View, FrameLayout> {
        val t = Theme.current
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(t.surfaceBg)
        }
        root.addView(Maniflow.header(this, headerTitle))
        val frame = FrameLayout(this).apply { setBackgroundColor(t.surfaceBg) }
        root.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root to frame
    }

    private fun buildTabs() {
        // HOME
        homeRoot = buildHome()
        contentFrame.addView(homeRoot, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // TASKS
        val (tRoot, tFrame) = tabScaffold("Tasks")
        taskRoot = tRoot
        taskList = ListView(this).apply {
            divider = null
            dividerHeight = 0
            setSelector(android.R.color.transparent)
            setBackgroundColor(Theme.current.surfaceBg)
        }
        taskAdapter = TaskListAdapter(this)
        taskList.adapter = taskAdapter
        taskList.setOnItemClickListener { _, _, pos, _ -> if (pos in tasks.indices) openTaskEditor(tasks[pos]) }
        tFrame.addView(taskList, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        taskEmpty = emptyLabel("No tasks yet", "Tap + to create one")
        tFrame.addView(taskEmpty, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        contentFrame.addView(taskRoot, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // VARS
        val (vRoot, vFrame) = tabScaffold("Variables")
        varRoot = vRoot
        varList = ListView(this).apply {
            divider = null
            dividerHeight = 0
            setSelector(android.R.color.transparent)
            setBackgroundColor(Theme.current.surfaceBg)
        }
        varAdapter = VarListAdapter(this)
        varList.adapter = varAdapter
        varList.setOnItemClickListener { _, _, pos, _ -> if (pos in userVars.indices) varDialog(userVars[pos]) }
        vFrame.addView(varList, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        varEmpty = emptyLabel("No variables yet", "Tap + to add one")
        vFrame.addView(varEmpty, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        contentFrame.addView(varRoot, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // LOG
        val (lRoot, lFrame) = tabScaffold("Log")
        logRoot = lRoot
        logList = ListView(this).apply {
            divider = null
            dividerHeight = 0
            setSelector(android.R.color.transparent)
            setBackgroundColor(Theme.current.surfaceBg)
        }
        logAdapter = LogListAdapter(this)
        logList.adapter = logAdapter
        lFrame.addView(logList, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        logEmpty = emptyLabel("No events logged yet", "Actions, events and errors appear here")
        lFrame.addView(logEmpty, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        contentFrame.addView(logRoot, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // SETTINGS
        buildSettings()
    }

    private fun buildFabs() {
        val t = Theme.current
        fabAdd = ImageView(this).apply {
            setImageResource(R.drawable.ic_add)
            setColorFilter(t.headerText)
            background = Maniflow.rounded(this@MainActivity, t.accentPrimary, 999)
            elevation = dp(6f).toFloat()
            setPadding(dp(16f), dp(16f), dp(16f), dp(16f))
            contentDescription = "Add"
            setOnClickListener { onFabAdd() }
        }
        val lpAdd = FrameLayout.LayoutParams(dp(56f), dp(56f))
        lpAdd.gravity = Gravity.BOTTOM or Gravity.END
        lpAdd.setMargins(0, 0, dp(16f), dp(20f))
        fabAdd.layoutParams = lpAdd
        contentFrame.addView(fabAdd)

        fabAi = ImageView(this).apply {
            setImageResource(R.drawable.ic_ai)
            setColorFilter(t.headerText)
            background = Maniflow.rounded(this@MainActivity, t.headerBg, 999)
            elevation = dp(6f).toFloat()
            setPadding(dp(12f), dp(12f), dp(12f), dp(12f))
            contentDescription = "AI theme studio"
            setOnClickListener { openThemeStudio() }
        }
        val lpAi = FrameLayout.LayoutParams(dp(48f), dp(48f))
        lpAi.gravity = Gravity.BOTTOM or Gravity.END
        lpAi.setMargins(0, 0, dp(20f), dp(84f))
        fabAi.layoutParams = lpAi
        contentFrame.addView(fabAi)
    }

    internal fun openThemeStudio() {
        startActivity(Intent(this, ThemeStudioActivity::class.java))
    }

    private fun refreshEmptyViews() {
        homeEmpty.visibility = if (currentTab == TAB_HOME && profiles.isEmpty()) View.VISIBLE else View.GONE
        taskEmpty.visibility = if (currentTab == TAB_TASKS && tasks.isEmpty()) View.VISIBLE else View.GONE
        varEmpty.visibility = if (currentTab == TAB_VARS && userVars.isEmpty()) View.VISIBLE else View.GONE
        logEmpty.visibility = if (currentTab == TAB_LOG && logs.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun onFabAdd() {
        when (currentTab) {
            TAB_HOME -> profileDialog(null)
            TAB_TASKS -> openTaskEditor(null)
            TAB_VARS -> varDialog(null)
        }
    }

    internal fun toggleExpand(id: String) {
        if (!expandedIds.add(id)) expandedIds.remove(id)
        flowAdapter.notifyDataSetChanged()
    }

    internal fun toggleExpand(pos: Int) {
        if (pos in profiles.indices) toggleExpand(profiles[pos].id)
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

        if (::homeHeaderStatus.isInitialized) {
            homeHeaderStatus.text = if (running) "Running" else "Service stopped"
            homeHeadline.text = if (profiles.isEmpty()) "No profiles yet - tap + to begin"
            else "${profiles.size} flows are managing your day"
            Maniflow.restylePill(homePillService, "SERVICE", running)
            Maniflow.restylePill(homePillRoot, "ROOT", if (!rootChecked) null else rootOk)
            statFlows.text = profiles.count { it.enabled }.toString()
            statTasks.text = tasks.size.toString()
            statBattery.text = battText
        }

        flowAdapter.notifyDataSetChanged()
        taskAdapter.notifyDataSetChanged()
        varAdapter.notifyDataSetChanged()
        logAdapter.notifyDataSetChanged()
        refreshEmptyViews()
        refreshSettings()
    }

    private fun refreshSettings() {
        if (!::svcSwitch.isInitialized) return
        svcSwitch.setChecked(running, animate = false)
        svcSwitchRow.text = if (running) "listening for events" else "stopped - profiles idle"
        setStatusPill(rootStatusTv, "ROOT", when {
            !rootChecked -> null
            rootOk -> true
            else -> false
        })
        val shiz = com.eventsh.app.engine.ShizukuClient.available
        setStatusPill(shizukuStatusTv, "SHIZUKU", shiz)
        val usageNeed = Permissions.Need("usage", "Usage access", "", Permissions.Kind.SPECIAL, settingsAction = android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS)
        setStatusPill(usageStatusTv, "USAGE", usageNeed.granted(this))
        val notifNeed = Permissions.Need("notif_listener", "Notification access", "", Permissions.Kind.SPECIAL, settingsAction = android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        setStatusPill(notifStatusTv, "NOTIF", notifNeed.granted(this))
        val overlayOk = com.eventsh.app.engine.Flash.canOverlay(this)
        setStatusPill(overlayStatusTv, "OVERLAY", overlayOk)
        val am = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        val exactOk = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        setStatusPill(exactStatusTv, "EXACT", exactOk)
        val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val battOk = pm.isIgnoringBatteryOptimizations(packageName)
        setStatusPill(battOptStatusTv, "BATTERY", battOk)
        val locOk = checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        setStatusPill(locStatusTv, "LOCATION", locOk)
        if (::aboutText.isInitialized) {
            aboutText.text = "Maniflow v0.1.0\n$ramText   $cpuText"
            rootFrameFindStat("about.profiles")?.text = profiles.count { it.enabled }.toString()
            rootFrameFindStat("about.tasks")?.text = tasks.size.toString()
            rootFrameFindStat("about.batt")?.text = battText
        }
        refreshAiSettings()
    }

    private fun rootFrameFindStat(tag: String): TextView? =
        contentFrame.findViewWithTag<TextView>(tag)

    private fun updateStats() {
        if (!resumed) { cpuRef = 0L; return }
        val mem = Debug.MemoryInfo()
        Debug.getMemoryInfo(mem)
        ramText = "PSS ${mem.totalPss / 1024}MB"
        battText = "${EventHub.batteryNow(this)}%"
        val (_, ramPct) = SysStats.mem()
        val tmp = readProcStat()
        if (cpuRef != 0L && tmp != 0L) cpuText = "CPU ${(tmp - cpuRef).coerceIn(0, 200)}%"
        cpuRef = tmp
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

    // ------------------------------------------------------------ shared helpers
    internal fun editText(hint: String): EditText {
        val t = Theme.current
        return EditText(this).apply {
            this.hint = hint
            setHintTextColor(t.textMuted)
            setTextColor(t.textPrimary)
            textSize = 16f
            background = Maniflow.rounded(this@MainActivity, t.surfaceBg, 10, borderColor = t.borderColor, borderDp = 1f)
            setPadding(dp(10f), dp(9f), dp(10f), dp(9f))
        }
    }

    internal fun checkBox(text: String): CheckBox = CheckBox(this).apply {
        this.text = text
        setTextColor(Theme.current.textPrimary)
        textSize = 15f
        buttonTintList = ColorStateList.valueOf(Theme.current.accentPrimary)
    }

    internal fun sectionLabel(text: String): TextView =
        Maniflow.sectionLabel(this, text)

    internal fun ctxRow(text: String, color: Int, onClick: () -> Unit): TextView {
        val t = Theme.current
        return TextView(this).apply {
            this.text = text
            textSize = 15f
            setTextColor(color)
            setPadding(dp(10f), dp(10f), dp(10f), dp(10f))
            background = Maniflow.rounded(this@MainActivity, t.cardBg, 10, borderColor = t.borderColor, borderDp = 1f)
            setOnClickListener { onClick() }
        }
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
