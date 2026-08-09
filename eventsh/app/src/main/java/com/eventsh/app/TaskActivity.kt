package com.eventsh.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.eventsh.app.engine.Action
import com.eventsh.app.engine.CondSpec
import com.eventsh.app.engine.Dispatcher
import com.eventsh.app.engine.EventLog
import com.eventsh.app.engine.Store
import com.eventsh.app.engine.Task
import com.eventsh.app.ui.C
import com.eventsh.app.ui.UI
import java.util.UUID

/**
 * Full-page Task editor. A task's actions live here instead of a dialog so
 * tasks with many actions remain manageable. Returned to MainActivity via the
 * back button; MainActivity re-reads the store onResume.
 */
class TaskActivity : Activity() {

    private var taskId = ""
    private var existing: Task? = null
    private val actions = mutableListOf<Action>()
    private lateinit var nameEt: EditText
    private lateinit var rtEt: EditText
    private lateinit var enSwitch: Switch
    private lateinit var actBox: LinearLayout

    // live run (green = ok / red = failed) state, in-memory only
    private val runMarks = mutableMapOf<Int, Int>()
    private var runRunning = false
    private lateinit var runBtn: ImageView
    private val runHandler = Handler(Looper.getMainLooper())

    // long-press multi-select state
    private val selected = mutableSetOf<Int>()
    private var selectionMode = false
    private lateinit var selDeleteBtn: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        taskId = intent.getStringExtra("taskId") ?: ""
        existing = if (taskId.isBlank()) null else Store.tasks(this).find { it.id == taskId }
        actions.clear()
        existing?.actions?.forEach { actions.add(it) }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C.bg)
        }
        root.addView(buildTopBar())
        root.addView(buildBody(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(buildBottomBar())

        val frame = FrameLayout(this)
        frame.addView(root)
        runBtn = buildRunFab()
        frame.addView(
            runBtn,
            FrameLayout.LayoutParams(dp(56f), dp(56f), Gravity.BOTTOM or Gravity.END).apply {
                marginEnd = dp(16f)
                bottomMargin = dp(84f)
            }
        )
        setContentView(frame)

        if (Dispatcher.isTaskRunning(runId())) {
            runRunning = true
            setRunButton(true)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        ActionEditor.onActivityResult(requestCode, resultCode, data)
    }

    private fun buildTopBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8f), dp(12f), dp(8f), dp(12f))
        }
        val back = TextView(this).apply {
            text = "‹  BACK"
            textSize = 16f
            setTextColor(C.accent)
            setPadding(dp(6f), dp(4f), dp(12f), dp(4f))
            setOnClickListener { finish() }
        }
        bar.addView(back)
        bar.addView(
            UI.text(this, if (existing == null) "NEW TASK" else existing!!.name, 18f, C.text, bold = true).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(4f)
            }
        )
        selDeleteBtn = TextView(this).apply {
            text = "DELETE"
            textSize = 15f
            boldText()
            setTextColor(C.danger)
            setPadding(dp(8f), dp(6f), dp(8f), dp(6f))
            visibility = View.GONE
            setOnClickListener { deleteSelected() }
        }
        bar.addView(selDeleteBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginEnd = dp(4f)
        })
        return bar
    }

    private fun buildBody(): View {
        val scroll = ScrollView(this).apply { setBackgroundColor(C.bg) }
        val ll = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12f), dp(4f), dp(12f), dp(16f))
        }

        nameEt = ActionEditor.editText(this, "task name")
        nameEt.setText(existing?.name ?: "")
        ll.addView(nameEt)

        val enRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4f), dp(10f), dp(4f), dp(2f))
        }
        enRow.addView(
            UI.text(this, "Task enabled", 15f, C.text).apply {
                setPadding(0, 0, 0, 0)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        enSwitch = Switch(this).apply { isChecked = existing?.enabled ?: true }
        enRow.addView(enSwitch)
        ll.addView(enRow)

        rtEt = ActionEditor.editText(this, "retries on failure (0)")
        rtEt.setText(existing?.retries.toString())
        ll.addView(ActionEditor.sectionLabel(this, "FAILURE"))
        ll.addView(rtEt)

        ll.addView(ActionEditor.sectionLabel(this, "ACTIONS"))
        actBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        refreshActs()
        ll.addView(actBox)

        ll.addView(
            UI.text(this, "Each action can carry its own IF guard - tap it, press + ADD CONDITION, pick a variable from the app or type one, and connect multiple conditions with AND / OR / XOR.", 12f, C.hint).apply {
                setPadding(dp(4f), dp(12f), dp(4f), dp(4f))
            }
        )
        scroll.addView(ll)
        return scroll
    }

    private fun refreshActs() {
        actBox.removeAllViews()
        if (actions.isEmpty()) {
            actBox.addView(
                UI.text(this, "(no actions yet - add one)", 14f, C.hint).apply {
                    setPadding(dp(4f), dp(6f), dp(4f), dp(6f))
                }
            )
        } else {
            for (i in actions.indices) {
                val row = actionRow(i)
                row.tag = i
                actBox.addView(row)
            }
        }
        actBox.addView(ActionEditor.ctxRow(this, "+ ADD ACTION", C.accent) {
            ActionEditor.actionTypePick(this) { type ->
                ActionEditor.actionDialog(
                    this, Action(type),
                    onSave = { na -> actions.add(na); runMarks.clear(); refreshActs() }
                )
            }
        })
        updateSelUi()
    }

    /** Builds one action tile: order number on the left, content, status dot, drag grip. */
    private fun actionRow(i: Int): View {
        val a = actions[i]
        val condLine = a.condTerms()?.let { (t, j) -> CondSpec.summary(t, j) }
        val labelPrefix = if (a.label.isBlank()) "" else "{${a.label}}  "
        val text = "$labelPrefix${a.typeLabel()}  ${a.summary()}" +
            (if (condLine.isNullOrBlank()) "" else "   [IF $condLine]")
        val isSel = selected.contains(i)
        val border = when {
            isSel -> C.primary
            runMarks[i] == ST_OK -> C.ok
            runMarks[i] == ST_FAIL -> C.danger
            runMarks[i] == ST_RUN -> C.accent
            else -> C.border
        }
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = UI.rounded(if (isSel) C.primarySoft else C.card, 10f, border, if (isSel) 2f else 1f)
            setPadding(dp(10f), dp(10f), dp(10f), dp(10f))
            isClickable = true
            isLongClickable = true
            setOnClickListener {
                if (selectionMode) {
                    toggleSelect(i)
                } else {
                    ActionEditor.actionDialog(
                        this@TaskActivity, a,
                        onSave = { na -> actions[i] = na; runMarks.clear(); refreshActs() },
                        onRemove = { actions.removeAt(i); runMarks.clear(); refreshActs() }
                    )
                }
            }
            setOnLongClickListener {
                if (!selectionMode) selectionMode = true
                toggleSelect(i)
                true
            }
        }
        wrap.addView(
            UI.text(this, "${i + 1}.", 15f, C.accent, bold = true),
            LinearLayout.LayoutParams(dp(34f), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(6f)
            }
        )
        wrap.addView(
            UI.text(this, text, 15f, C.text),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        wrap.addView(statusDot(i), LinearLayout.LayoutParams(dp(12f), dp(12f)).apply {
            marginEnd = dp(8f)
        })
        wrap.addView(UI.text(this, "≡", 18f, C.hint))
        return wrap
    }

    /** Colored circle reflecting the last manual-run result of action [i]. */
    private fun statusDot(i: Int): TextView {
        val st = runMarks[i]
        return UI.text(this, "●", 13f, when (st) {
            ST_OK -> C.ok
            ST_FAIL -> C.danger
            ST_RUN -> C.accent
            else -> C.disabled
        }).apply {
            contentDescription = when (st) {
                ST_OK -> "ran ok"
                ST_FAIL -> "failed"
                ST_RUN -> "running"
                else -> null
            }
        }
    }

    /** Round play / stop FAB docked above the bottom save/delete bar. */
    private fun buildRunFab(): ImageView = ImageView(this).apply {
        background = ovalBg(C.primary)
        setColorFilter(C.onPrimary)
        setPadding(dp(18f), dp(18f), dp(18f), dp(18f))
        elevation = dp(6f).toFloat()
        contentDescription = "Run task"
        setOnClickListener { onRunTap() }
        setImageResource(R.drawable.ic_play)
    }

    private fun onRunTap() {
        if (runRunning) {
            Dispatcher.stopTask(runId())
            EventLog.push("[ui] task stop requested")
            setRunButton(false)
            return
        }
        if (actions.isEmpty()) {
            EventLog.push("[ui] no actions to run yet")
            return
        }
        runMarks.clear()
        val task = currentRunTask()
        EventLog.push("[ui] running ${task.name} (${task.actions.size} actions)")
        val started = Dispatcher.runTaskNow(this, task) { idx, ok, _ ->
            runHandler.post {
                if (idx < 0) {
                    runRunning = false
                    setRunButton(false)
                } else {
                    runMarks[idx] = if (ok) ST_OK else ST_FAIL
                }
                refreshActs()
            }
        }
        if (started) {
            runRunning = true
            setRunButton(true)
        }
        refreshActs()
    }

    /** Stable id for the RUN button: the saved id, or a temp one for unsaved tasks. */
    private fun runId(): String = taskId.ifBlank { "tk_tmp_" + actions.hashCode() }

    /** Snapshot of the current editor state to hand to the runner. */
    private fun currentRunTask(): Task {
        val id = runId()
        return (existing ?: Task(id = id, name = "TASK")).copy(
            name = nameEt.text.toString().trim().ifBlank { "TASK" },
            retries = (rtEt.text.toString().toIntOrNull() ?: 0).coerceIn(0, 10),
            enabled = enSwitch.isChecked,
            actions = actions.toList()
        )
    }

    private fun setRunButton(running: Boolean) {
        runBtn.setImageResource(if (running) R.drawable.ic_stop else R.drawable.ic_play)
        runBtn.background = ovalBg(if (running) C.danger else C.primary)
    }

    private fun toggleSelect(i: Int) {
        if (!selected.add(i)) selected.remove(i)
        if (selected.isEmpty()) selectionMode = false
        refreshActs()
    }

    private fun deleteSelected() {
        if (selected.isEmpty()) return
        for (idx in selected.sortedDescending()) {
            if (idx in actions.indices) actions.removeAt(idx)
        }
        selected.clear()
        selectionMode = false
        runMarks.clear()
        refreshActs()
    }

    private fun updateSelUi() {
        selDeleteBtn.text = if (selectionMode) "DELETE (${selected.size})" else "DELETE"
        selDeleteBtn.visibility = if (selectionMode && selected.isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun ovalBg(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun buildBottomBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(dp(12f), dp(6f), dp(12f), dp(12f))
        }
        if (existing != null) {
            bar.addView(
                materialButton("DELETE", C.danger) { confirmDelete() },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(8f)
                }
            )
        }
        bar.addView(
            materialButton("SAVE", C.primary) { save() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        return bar
    }

    private fun materialButton(label: String, color: Int, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 16f
            boldText()
            setTextColor(C.onPrimary.takeIf { color == C.primary } ?: C.text)
            gravity = Gravity.CENTER
            setPadding(dp(10f), dp(12f), dp(10f), dp(12f))
            background = UI.rounded(if (color == C.primary) C.primary else C.card, 12f, C.border, 1f)
            setOnClickListener { onClick() }
        }

    private fun TextView.boldText() {
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    private fun save() {
        val base = existing ?: Task(id = "tk_" + UUID.randomUUID().toString().take(8), name = "TASK")
        val newT = base.copy(
            name = nameEt.text.toString().trim().ifBlank { "TASK" },
            retries = (rtEt.text.toString().toIntOrNull() ?: 0).coerceIn(0, 10),
            enabled = enSwitch.isChecked,
            actions = actions.toList()
        )
        val cur = Store.tasks(this).toMutableList()
        val i = cur.indexOfFirst { it.id == base.id }
        if (i >= 0) cur[i] = newT else cur.add(newT)
        Store.saveTasks(this, cur)
        finish()
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle("Delete task")
            .setMessage("delete '${existing?.name ?: "this task"}'?\nProfiles linked to it will be unlinked.")
            .setPositiveButton("DELETE") { _, _ ->
                val cur = Store.tasks(this).toMutableList()
                cur.removeAll { it.id == taskId }
                Store.saveTasks(this, cur)
                val ps = Store.profiles(this).map { if (it.taskId == taskId) it.copy(taskId = "") else it }
                Store.saveProfiles(this, ps)
                finish()
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun dp(v: Float): Int = ActionEditor.dp(this, v)
}

// Live-run status codes used by TaskActivity.runMarks.
private const val ST_RUN = 1
private const val ST_OK = 2
private const val ST_FAIL = 3
