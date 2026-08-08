package com.eventsh.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
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

        ll.addView(ActionEditor.sectionLabel(this, "ACTIONS (run in order, tap to edit)"))
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
                val a = actions[i]
                val idx = i
                val condLine = a.condTerms()?.let { (t, j) -> CondSpec.summary(t, j) }
                val text = "${a.label()}  ${a.summary()}" +
                    (if (condLine.isNullOrBlank()) "" else "   [IF $condLine]")
                actBox.addView(ActionEditor.ctxRow(this, text, C.text) {
                    ActionEditor.actionDialog(
                        this, a,
                        onSave = { na -> actions[idx] = na; refreshActs() },
                        onRemove = { actions.removeAt(idx); refreshActs() }
                    )
                })
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
