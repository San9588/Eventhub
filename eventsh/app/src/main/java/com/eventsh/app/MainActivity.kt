package com.eventsh.app

import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.eventsh.app.engine.Action
import com.eventsh.app.engine.Actions
import com.eventsh.app.engine.AppCtx
import com.eventsh.app.engine.CondSpec
import com.eventsh.app.engine.Ctx
import com.eventsh.app.engine.DayCtx
import com.eventsh.app.engine.Dispatcher
import com.eventsh.app.engine.EventCtx
import com.eventsh.app.engine.EventCatalog
import com.eventsh.app.engine.EventHub
import com.eventsh.app.engine.EventLog
import com.eventsh.app.engine.Permissions
import com.eventsh.app.engine.Profile
import com.eventsh.app.engine.RootBridge
import com.eventsh.app.engine.Scheduler
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
import java.util.UUID

class MainActivity : Activity() {

    // ------------------------------------------------------------ tabs
    private val TAB_NAMES = arrayOf("Profiles", "Tasks", "Vars", "Log", "Settings")
    private val TAB_PROFILES = 0
    private val TAB_TASKS = 1
    private val TAB_VARS = 2
    private val TAB_LOG = 3
    private val TAB_SETTINGS = 4

    private val handler = Handler(Looper.getMainLooper())
    private val refreshRunnable = Runnable { refreshScreen() }
    private var cpuRef = 0L
    private var permDialog: AlertDialog? = null
    private val permRows = mutableListOf<Pair<Permissions.Need, TextView>>()
    private var resumed = false
    private var currentTab = 0
    private var suppressSwitch = false
    private val expandedIds = HashSet<String>()

    private val FILE_EVENTS = setOf(
        "file_modified", "file_opened", "file_closed",
        "file_deleted", "file_moved", "file_attr"
    )
    private val REQ_FILE_PICK = 7002
    @Volatile private var pendingFilePick: ((String?) -> Unit)? = null

    // data
    private var profiles: List<Profile> = emptyList()
    private var tasks: List<Task> = emptyList()
    private var logs: List<String> = emptyList()
    private var running = false
    private var rootOk = false
    private var rootChecked = false
    private var userVars: List<VarEntry> = emptyList()
    private var ramText = "PSS 0MB"
    private var cpuText = "CPU 0.0%"
    private var battText = "--%"

    // view refs
    private lateinit var contentFrame: FrameLayout
    private lateinit var tabIndicators: List<View>
    private lateinit var profileList: ListView
    private lateinit var taskList: ListView
    private lateinit var varList: ListView
    private lateinit var logList: ListView
    private lateinit var settingsScroll: ScrollView
    private lateinit var profileEmpty: TextView
    private lateinit var taskEmpty: TextView
    private lateinit var varEmpty: TextView
    private lateinit var logEmpty: TextView
    private lateinit var fabAdd: View
    private lateinit var fabAi: View
    private lateinit var svcChip: TextView
    private lateinit var rootChip: TextView
    private lateinit var statusChip: TextView
    private lateinit var aboutText: TextView
    private lateinit var svcSwitch: Switch
    private lateinit var svcSwitchRow: TextView
    private lateinit var autoSwitch: Switch
    private lateinit var rootStatusTv: TextView
    private lateinit var shizukuStatusTv: TextView
    private lateinit var usageStatusTv: TextView
    private lateinit var notifStatusTv: TextView

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

    private fun refreshPermissions() {
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
    private fun dp(v: Float): Int = UI.dp(this, v)

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
                Actions.NOTIFY -> R.drawable.ic_notify
                Actions.VAR_SET, Actions.VAR_SPLIT, Actions.VAR_JOIN, Actions.VAR_QUERY -> R.drawable.ic_var
                Actions.IF, Actions.ELSE, Actions.END_IF, Actions.FOR, Actions.END_FOR -> R.drawable.ic_list
                else -> R.drawable.ic_settings
            }
            val cond = a.condTerms()?.let { (t, j) -> CondSpec.summary(t, j) }
            out += icon to ("${a.label()}  ${a.summary()}" +
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
            sw.setOnCheckedChangeListener { _, _ -> toggleProfile(p) }
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
                btnRow.addView(materialButton("TEST", C.accent, { testProfile(p) }))
                btnRow.addView(materialButton("EDIT", C.primary, { profileDialog(p) }), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = dp(8f)
                })
                btnRow.addView(materialButton("DELETE", C.danger, { deleteProfile(p) }), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
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
            header.addView(iconButton(R.drawable.ic_edit, C.textSec, { openTaskEditor(t) }))
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
            card.setOnClickListener { openTaskEditor(t) }
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

    // ------------------------------------------------------------ settings tab
    private fun buildSettings() {
        val scroll = ScrollView(this).apply { setBackgroundColor(C.bg) }
        settingsScroll = scroll
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6f), dp(6f), dp(6f), dp(6f))
        }
        scroll.addView(root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        contentFrame.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // ---- ENGINE
        root.addView(sectionLabel("ENGINE"))
        val svcCard = cardContainer()
        svcSwitch = Switch(this)
        svcSwitchRow = UI.text(this, "listening for events", 13f, C.textSec)
        val svcRow = switchRow("Background service", svcSwitch, svcSwitchRow, {
            if (isServiceRunning()) {
                stopService(Intent(this, EventService::class.java))
            } else {
                startServiceCompat()
            }
            handler.postDelayed({ refreshScreen() }, 400)
        })
        svcCard.addView(svcRow, matchWrap())
        autoSwitch = Switch(this)
        autoSwitch.isChecked = Store.autostart(this)
        autoSwitch.setOnCheckedChangeListener { _, checked -> Store.setAutostart(this, checked) }
        val autoRow = switchRow("Start on boot", autoSwitch, UI.text(this, "restart engine after reboot", 13f, C.textSec), null)
        svcCard.addView(autoRow, matchWrap())
        root.addView(svcCard, matchWrap())

        // ---- PERMISSIONS
        root.addView(sectionLabel("PERMISSIONS"))
        val permCard = cardContainer()
        val rootRow = actionRowContent("Root", "check su binary availability", {
            RootBridge.checkAsync()
            handler.postDelayed({ refreshScreen() }, 900)
        })
        rootStatusTv = rootRow.second
        permCard.addView(rootRow.first, matchWrap())
        val shizukuRow = actionRowContent("Shizuku", "run restricted actions without root (Android 13+)", {
            com.eventsh.app.engine.ShizukuClient.requestPermission(this)
            handler.postDelayed({ refreshScreen() }, 900)
        })
        shizukuStatusTv = shizukuRow.second
        permCard.addView(shizukuRow.first, matchWrap())
        val usageRow = actionRowContent("Usage access", "detect foreground app (app triggers)", {
            startActivity(Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS))
        })
        usageStatusTv = usageRow.second
        permCard.addView(usageRow.first, matchWrap())
        val notifRow = actionRowContent("Notification access", "read posted notifications (notify_post)", {
            startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })
        notifStatusTv = notifRow.second
        permCard.addView(notifRow.first, matchWrap())
        val smsRow = actionRowContent("SMS + Phone + Bluetooth", "runtime permissions for events", {
            requestPermissions(arrayOf(
                android.Manifest.permission.RECEIVE_SMS,
                android.Manifest.permission.READ_PHONE_STATE,
                android.Manifest.permission.BLUETOOTH_CONNECT
            ), 20)
        })
        permCard.addView(smsRow.first, matchWrap())
        root.addView(permCard, matchWrap())

        // ---- DATA
        root.addView(sectionLabel("DATA"))
        val dataCard = cardContainer()
        dataCard.addView(actionRowContent("Export", "backup profiles + tasks to eventsh_backup.json", { exportRules() }).first, matchWrap())
        dataCard.addView(actionRowContent("Import", "restore profiles + tasks from backup file", { importRules() }).first, matchWrap())
        root.addView(dataCard, matchWrap())

        // ---- ABOUT
        root.addView(sectionLabel("ABOUT"))
        val aboutCard = cardContainer()
        aboutText = UI.text(this, "", 13f, C.textSec)
        aboutCard.addView(aboutText, matchWrap())
        root.addView(aboutCard, matchWrap())
        root.addView(UI.vsep(this, dp(80f)))
    }

    private fun matchWrap(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun cardContainer(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(4f), dp(4f), dp(4f), dp(4f))
        background = UI.rounded(C.surface, 14f)
    }

    private fun switchRow(
        label: String,
        sw: Switch,
        subtitle: TextView,
        onChange: (() -> Unit)?
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f), dp(8f), dp(8f), dp(8f))
        }
        val textCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        textCol.addView(UI.text(this, label, 15f, C.text))
        textCol.addView(subtitle)
        row.addView(textCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        sw.setOnCheckedChangeListener { _, _ -> if (!suppressSwitch) onChange?.invoke() }
        row.addView(sw)
        return row
    }

    private fun actionRowContent(
        label: String,
        subtitle: String,
        onClick: () -> Unit
    ): Pair<View, TextView> {
        val status = UI.text(this, "", 13f, C.textSec)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f), dp(8f), dp(8f), dp(8f))
            background = UI.rounded(C.card, 10f, C.border, 1f)
            isClickable = true
            setOnClickListener { onClick() }
        }
        val textCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        textCol.addView(UI.text(this, label, 15f, C.text))
        textCol.addView(UI.text(this, subtitle, 12f, C.textSec))
        row.addView(textCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(status)
        return row to status
    }

    // ------------------------------------------------------------ refresh / stats
    private fun refreshScreen() {
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

    // ------------------------------------------------------------ profile actions
    private fun toggleProfile(p: Profile) {
        val cur = Store.profiles(this).toMutableList()
        val i = cur.indexOfFirst { it.id == p.id }
        if (i >= 0) {
            cur[i] = p.copy(enabled = !p.enabled)
            Store.saveProfiles(this, cur)
            if (cur[i].enabled && (cur[i].isOneShotTimer || cur[i].isDailyTimer || cur[i].timeCtx != null)) {
                Scheduler.schedule(this, cur[i])
            } else if (!cur[i].enabled) {
                Scheduler.cancel(this, cur[i])
            }
            if (running) EventHub.resync(this)
            refreshScreen()
        }
    }

    private fun deleteProfile(p: Profile) {
        AlertDialog.Builder(this)
            .setTitle("Delete profile")
            .setMessage("delete '${p.name}'?")
            .setPositiveButton("DELETE") { _, _ ->
                Scheduler.cancel(this, p)
                val cur = Store.profiles(this).toMutableList()
                cur.removeAll { it.id == p.id }
                Store.saveProfiles(this, cur)
                if (isServiceRunning()) EventHub.resync(this)
                refreshScreen()
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun testProfile(p: Profile) {
        val ev = p.eventActions.firstOrNull() ?: "test"
        EventLog.push("[test] firing '${p.name}' on $ev")
        Dispatcher.fire(this, p, ev, mapOf("summary" to "manual test"))
    }

    private fun exportRules() {
        try {
            val outDir = getExternalFilesDir(null) ?: filesDir
            val f = File(outDir, "eventsh_backup.json")
            val out = org.json.JSONObject()
                .put("profiles", org.json.JSONArray().apply { profiles.forEach { put(it.toJson()) } })
                .put("tasks", org.json.JSONArray().apply { tasks.forEach { put(it.toJson()) } })
            f.writeText(out.toString())
            EventLog.push("[bak] exported ${profiles.size} profile(s) + ${tasks.size} task(s) -> ${f.absolutePath}")
        } catch (e: Exception) {
            EventLog.push("[bak] export FAILED: ${e.message?.take(100) ?: "error"}")
        }
        refreshScreen()
    }

    private fun importRules() {
        try {
            val outDir = getExternalFilesDir(null) ?: filesDir
            val f = File(outDir, "eventsh_backup.json")
            if (!f.exists()) {
                EventLog.push("[bak] no backup file at ${f.absolutePath}")
                refreshScreen()
                return
            }
            val out = org.json.JSONObject(f.readText())
            val profiles = ArrayList<Profile>()
            val pArr = out.optJSONArray("profiles")
            if (pArr != null) for (i in 0 until pArr.length()) profiles.add(Profile.fromJson(pArr.getJSONObject(i)))
            val tasks = ArrayList<Task>()
            val tArr = out.optJSONArray("tasks")
            if (tArr != null) for (i in 0 until tArr.length()) tasks.add(Task.fromJson(tArr.getJSONObject(i)))
            Store.saveProfiles(this, profiles)
            Store.saveTasks(this, tasks)
            Scheduler.rescheduleAll(this)
            if (isServiceRunning()) EventHub.resync(this)
            EventLog.push("[bak] imported ${profiles.size} profile(s) + ${tasks.size} task(s)")
            refreshScreen()
        } catch (e: Exception) {
            EventLog.push("[bak] import FAILED: ${e.message?.take(100) ?: "error"}")
        }
        refreshScreen()
    }

    // ------------------------------------------------------------ profile editor
    private fun profileDialog(existing: Profile?) {
        val contexts = (existing?.contexts?.toMutableList() ?: mutableListOf<Ctx>())
        if (contexts.none { it is EventCtx } && existing != null && existing.eventContext != null) {
            contexts.add(0, existing.eventContext!!)
        }
        var taskId = existing?.taskId ?: ""

        val nameEt = editText("profile name")
        if (existing != null) nameEt.setText(existing.name)
        val prioEt = editText("priority (5)")
        val cdEt = editText("cooldown seconds (0)")
        if (existing != null) {
            prioEt.setText(existing.priority.toString())
            cdEt.setText(existing.cooldownSec.toString())
        }

        val taskTv = TextView(this).apply {
            textSize = 15f
            setPadding(dp(10f), dp(10f), dp(10f), dp(10f))
            background = UI.rounded(C.card, 10f, C.border, 1f)
            setOnClickListener {
                taskPickDialog(taskId) { tid ->
                    taskId = tid
                    text = taskNameOr(tid)
                }
            }
        }
        taskTv.text = taskNameOr(taskId)

        // ---- contexts editor (Tasker-style: Event / Time / Day / Variable / App) ----
        val ctxBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun refreshCtx() {
            ctxBox.removeAllViews()
            if (contexts.isEmpty()) {
                ctxBox.addView(UI.text(this, "(no triggers yet - add one)", 14f, C.hint).apply {
                    setPadding(dp(4f), dp(8f), dp(4f), dp(8f))
                })
            } else {
                for (i in contexts.indices) {
                    val c = contexts[i]
                    val idx = i
                    ctxBox.addView(ctxRow(c.summary(), C.text) {
                        editContext(contexts, idx) { refreshCtx() }
                    })
                }
            }
            ctxBox.addView(ctxRow("+ ADD TRIGGER", C.accent) {
                addContext(contexts) { refreshCtx() }
            })
        }
        refreshCtx()

        val ll = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(nameEt)
            addView(sectionLabel("TRIGGERS (all must match)"))
            addView(ctxBox)
            addView(sectionLabel("LINKED TASK"))
            addView(taskTv)
            addView(sectionLabel("ADVANCED"))
            addView(prioEt)
            addView(cdEt)
        }
        val scroll = ScrollView(this).apply { addView(ll) }

        val d = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "NEW PROFILE" else "EDIT PROFILE")
            .setMessage("trigger contexts + linked task")
            .setView(scroll)
            .setPositiveButton("SAVE") { _, _ ->
                val eventCtx = contexts.filterIsInstance<EventCtx>().firstOrNull()
                val timeCtx = contexts.filterIsInstance<TimeCtx>().firstOrNull()
                val appCtx = contexts.filterIsInstance<AppCtx>().firstOrNull()
                val varCtx = contexts.filterIsInstance<VarCtx>().firstOrNull()
                val isTimer = existing != null && (existing.isOneShotTimer || existing.isDailyTimer)
                if (eventCtx == null && timeCtx == null && appCtx == null && varCtx == null && !isTimer) {
                    EventLog.push("[ui] add an EVENT, TIME, APP or VARIABLE trigger")
                    return@setPositiveButton
                }
                val base = existing ?: Profile(
                    id = "p_" + UUID.randomUUID().toString().take(8),
                    name = "PROFILE"
                )
                val newP = base.copy(
                    name = nameEt.text.toString().trim().ifBlank { "PROFILE" },
                    contexts = contexts,
                    taskId = taskId,
                    enabled = true,
                    priority = (prioEt.text.toString().toIntOrNull() ?: 5).coerceIn(1, 10),
                    cooldownSec = cdEt.text.toString().toLongOrNull() ?: 0L
                )
                val cur = Store.profiles(this).toMutableList()
                val i = cur.indexOfFirst { it.id == base.id }
                if (i >= 0) cur[i] = newP else cur.add(newP)
                Store.saveProfiles(this, cur)
                when {
                    newP.isOneShotTimer || newP.isDailyTimer -> Scheduler.schedule(this, newP)
                    newP.timeCtx != null -> Scheduler.scheduleCtx(this, newP)
                }
                if (isServiceRunning()) EventHub.resync(this)
                refreshScreen()
                val missing = Permissions.requiredFor(newP, Store.tasks(this)).filter { !it.granted(this) }
                if (missing.isNotEmpty()) {
                    EventLog.push("[perm] ${missing.joinToString(", ") { it.label }}")
                    showPermissionsDialog(missing)
                }
            }
            .setNegativeButton("CANCEL", null)
        if (existing != null) {
            d.setNeutralButton("DELETE") { _, _ -> deleteProfile(existing) }
        }
        d.show()
    }

    private fun taskNameOr(tid: String): String =
        if (tid.isBlank()) "(tap to link a task)" else (tasks.find { it.id == tid }?.name ?: "(unlinked task)")

    private fun taskPickDialog(current: String, onPick: (String) -> Unit) {
        val names = ArrayList<String>().apply { add("(no task)") }
        tasks.forEach { names.add(it.name) }
        val curIdx = if (current.isBlank()) 0 else {
            val t = tasks.indexOfFirst { it.id == current }
            if (t >= 0) t + 1 else 0
        }
        AlertDialog.Builder(this)
            .setTitle("LINK TASK")
            .setSingleChoiceItems(names.toTypedArray(), curIdx) { _, which ->
                onPick(if (which == 0) "" else tasks[which - 1].id)
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    // ------------------------------------------------------------ task editor
    /** Opens the full-page Task editor; null starts a fresh task. */
    private fun openTaskEditor(t: Task?) {
        startActivity(Intent(this, TaskActivity::class.java).apply {
            if (t != null) putExtra("taskId", t.id)
        })
    }

    private fun editText(hint: String): EditText = EditText(this).apply {
        this.hint = hint
        setHintTextColor(C.hint)
        setTextColor(C.text)
        textSize = 16f
        background = UI.rounded(C.surface, 10f, C.border, 1f)
        setPadding(dp(10f), dp(9f), dp(10f), dp(9f))
    }

    private fun checkBox(text: String): CheckBox = CheckBox(this).apply {
        this.text = text
        setTextColor(C.text)
        textSize = 15f
    }

    private fun sectionLabel(text: String): TextView =
        UI.text(this, text.uppercase(Locale.US), 12f, C.accent, bold = true).apply {
            letterSpacing = 0.1f
            setPadding(dp(4f), dp(14f), dp(4f), dp(6f))
        }

    private fun ctxRow(text: String, color: Int, onClick: () -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 15f
            setTextColor(color)
            setPadding(dp(10f), dp(10f), dp(10f), dp(10f))
            background = UI.rounded(C.card, 10f, C.border, 1f)
            setOnClickListener { onClick() }
        }

    // ------------------------------------------------------------ context editors
    private fun addContext(list: MutableList<Ctx>, refresh: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle("ADD CONTEXT")
            .setItems(arrayOf("EVENT", "TIME", "DAY", "VARIABLE", "APP")) { _, which ->
                when (which) {
                    0 -> eventCtxDialog(null, { list.add(it); refresh() }, null)
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
                        startActivityForResult(Intent(this@MainActivity, FilePickerActivity::class.java), REQ_FILE_PICK)
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

    private fun timeCtxDialog(existing: TimeCtx?, onSave: (TimeCtx) -> Unit, onRemove: (() -> Unit)?) {
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
                TimePickerDialog(this@MainActivity, { _, hh, mm ->
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
                TimePickerDialog(this@MainActivity, { _, hh, mm ->
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

    private fun hm(hhmm: String): Pair<Int, Int> {
        val parts = hhmm.split(":")
        return (parts.getOrNull(0)?.toIntOrNull() ?: 0) to (parts.getOrNull(1)?.toIntOrNull() ?: 0)
    }

    private fun dayCtxDialog(existing: DayCtx?, onSave: (DayCtx) -> Unit, onRemove: (() -> Unit)?) {
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

    private fun appPick(selected: Set<String>, onDone: (List<String>) -> Unit) {
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
                    return UI.text(this@MainActivity, r, 13f, C.accent, bold = true).apply {
                        setPadding(dp(12f), dp(14f), dp(12f), dp(4f))
                        setBackgroundColor(C.surface)
                    }
                }
                val ai = r as ApplicationInfo
                val cb = CheckBox(this@MainActivity).apply {
                    isChecked = checked.contains(ai.packageName)
                    isClickable = false
                    isFocusable = false
                }
                val tv = UI.text(this@MainActivity, label(ai), 15f, C.text)
                return LinearLayout(this@MainActivity).apply {
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

    private fun pickEvent(onPick: (String) -> Unit) {
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
                return TextView(this@MainActivity).apply {
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
            addView(UI.text(this@MainActivity, "custom event action: any broadcast string", 12f, C.hint).apply {
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

    // ------------------------------------------------------------ vars / timer
    private fun timerDialog() {
        val whenEt = editText("07:30 | +600 | epoch-ms")
        val labelEt = editText("label")
        val taskEt = editText("termux task name")
        val shellEt = editText("built-in shell command (sh -c ...)")
        val rootEt = editText("root command")
        val notifyCb = checkBox("show notification")
        val ll = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(whenEt)
            addView(labelEt)
            addView(taskEt)
            addView(shellEt)
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
                val acts = ArrayList<Action>()
                if (shellEt.text.isNotBlank()) acts.add(Action(Actions.SHELL, shellEt.text.toString().trim()))
                if (taskEt.text.isNotBlank()) acts.add(Action(Actions.SCRIPT, taskEt.text.toString().trim()))
                if (rootEt.text.isNotBlank()) acts.add(Action(Actions.ROOT, rootEt.text.toString().trim()))
                if (notifyCb.isChecked) acts.add(Action(Actions.NOTIFY, ""))
                val taskId = if (acts.isEmpty()) "" else {
                    val t = Task(id = "tk_" + UUID.randomUUID().toString().take(8), name = labelEt.text.toString().trim().ifBlank { "TIMER" }, actions = acts)
                    Store.saveTasks(this, Store.tasks(this).toMutableList().apply { add(t) })
                    t.id
                }
                val profile = Profile(
                    id = "t_" + UUID.randomUUID().toString().take(8),
                    name = labelEt.text.toString().trim().ifBlank { "TIMER" },
                    enabled = true,
                    taskId = taskId,
                    atEpoch = atEpoch,
                    daily = daily
                )
                Store.saveProfiles(this, Store.profiles(this).toMutableList().apply { add(profile) })
                Scheduler.schedule(this, profile)
                EventLog.push("[timer] armed ${profile.name} " + if (daily.isNotBlank()) daily else atEpoch.toString())
                refreshScreen()
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun varDialog(existing: VarEntry?) {
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

    // ------------------------------------------------------------ permissions dialog
    private fun showPermissionsDialog(missing: List<Permissions.Need>) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(8f), dp(20f), dp(8f))
        }
        box.addView(UI.text(this, "Tap each one to set it up", 13f, C.textSec))
        permRows.clear()
        missing.forEach { need ->
            val tv = TextView(this).apply {
                text = "[ ${need.label} ]  SET"
                setPadding(0, dp(14f), 0, dp(1f))
                textSize = 16f
                setTextColor(C.primary)
                setOnClickListener { need.open(this@MainActivity) }
            }
            box.addView(tv)
            box.addView(UI.text(this, need.detail, 12f, C.textSec))
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

    // ------------------------------------------------------------ service
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
}
