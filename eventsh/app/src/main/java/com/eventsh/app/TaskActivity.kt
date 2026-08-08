package com.eventsh.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.DragEvent
import android.view.DragShadowBuilder
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.eventsh.app.engine.Action
import com.eventsh.app.engine.CondSpec
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
    private var scroll: ScrollView? = null

    // long-press drag-and-drop reorder state
    private var dragIndex = -1
    private var lastDragY = 0f
    private var dragActive = false
    private val dragHandler = Handler(Looper.getMainLooper())
    private val dragScroll = object : Runnable {
        override fun run() {
            if (!dragActive) return
            val sv = scroll ?: return
            val loc = IntArray(2)
            actBox.getLocationOnScreen(loc)
            val gy = loc[1] + lastDragY
            val rect = android.graphics.Rect()
            sv.getGlobalVisibleRect(rect)
            val zone = dp(70f)
            val delta = when {
                gy < rect.top + zone -> -dp(28f)
                gy > rect.bottom - zone -> dp(28f)
                else -> 0
            }
            if (delta != 0) sv.scrollBy(0, delta)
            dragHandler.postDelayed(this, 16L)
        }
    }

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
        setContentView(root)
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
        return bar
    }

    private fun buildBody(): View {
        val scroll = ScrollView(this).apply { setBackgroundColor(C.bg) }
        this.scroll = scroll
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

        ll.addView(ActionEditor.sectionLabel(this, "ACTIONS (tap row = edit; long-press + drag = reorder)"))
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
                    onSave = { na -> actions.add(na); refreshActs() }
                )
            }
        })
        setupDragListener()
    }

    /** Builds one action tile: order number on the left, content, drag grip. */
    private fun actionRow(i: Int): View {
        val a = actions[i]
        val condLine = a.condTerms()?.let { (t, j) -> CondSpec.summary(t, j) }
        val labelPrefix = if (a.label.isBlank()) "" else "{${a.label}}  "
        val text = "$labelPrefix${a.typeLabel()}  ${a.summary()}" +
            (if (condLine.isNullOrBlank()) "" else "   [IF $condLine]")
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = UI.rounded(C.card, 10f, C.border, 1f)
            setPadding(dp(10f), dp(10f), dp(10f), dp(10f))
            isClickable = true
            isLongClickable = true
            setOnClickListener {
                ActionEditor.actionDialog(
                    this@TaskActivity, a,
                    onSave = { na -> actions[i] = na; refreshActs() },
                    onRemove = { actions.removeAt(i); refreshActs() }
                )
            }
            setOnLongClickListener { v ->
                dragIndex = i
                dragActive = true
                lastDragY = 0f
                val ok = v.startDragAndDrop(
                    ClipData.newPlainText("reorder", ""),
                    DragShadowBuilder(v),
                    i,
                    0
                )
                if (!ok) {
                    dragActive = false
                    dragIndex = -1
                }
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
        wrap.addView(UI.text(this, "≡", 18f, C.hint))
        return wrap
    }

    /** Rebuilds rows in place as the dragged tile crosses boundaries. */
    private fun setupDragListener() {
        actBox.setOnDragListener { _, e ->
            when (e.action) {
                DragEvent.ACTION_DRAG_STARTED -> {
                    dragIndex = (e.localState as? Int) ?: -1
                    true
                }
                DragEvent.ACTION_DRAG_LOCATION -> {
                    lastDragY = e.y
                    if (!dragActive) {
                        dragActive = true
                        dragHandler.post(dragScroll)
                    }
                    val t = dropTargetFor(e.y)
                    if (t != dragIndex && dragIndex in actions.indices) {
                        val newIdx = if (t > dragIndex) t - 1 else t
                        reorder(dragIndex, t)
                        dragIndex = newIdx
                        refreshActs()
                    }
                    true
                }
                DragEvent.ACTION_DRAG_DROP -> true
                DragEvent.ACTION_DRAG_ENDED -> {
                    dragActive = false
                    dragHandler.removeCallbacks(dragScroll)
                    dragIndex = -1
                    true
                }
                else -> true
            }
        }
    }

    /** Insertion index for a drop at [y] (actBox-local): before the middle of a tile. */
    private fun dropTargetFor(y: Float): Int {
        var acc = 0f
        for (i in 0 until actBox.childCount) {
            val c = actBox.getChildAt(i)
            val idx = c.tag as? Int ?: continue
            if (idx !in actions.indices) continue
            val h = c.height
            if (y < acc + h / 2f) return idx
            acc += h
        }
        return actions.size
    }

    /** Moves the action at [from] to the insertion index [to] (0..size). */
    private fun reorder(from: Int, to: Int) {
        if (to < 0 || to > actions.size || to == from) return
        val moved = actions.removeAt(from)
        actions.add(if (to > from) to - 1 else to, moved)
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
