package com.eventsh.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.eventsh.app.engine.Action
import com.eventsh.app.engine.Actions
import com.eventsh.app.engine.CondSpec
import com.eventsh.app.engine.CondTerm
import com.eventsh.app.engine.EventLog
import com.eventsh.app.engine.Store
import com.eventsh.app.engine.UserVars
import com.eventsh.app.ui.Maniflow
import com.eventsh.app.ui.Theme

/**
 * Shared programmatic UI for editing Task actions. Used by both MainActivity
 * and TaskActivity so a task's action list behaves identically everywhere.
 * The Set Alarm and HTTP dialogs live in ActionEditorDialogs.kt.
 */
object ActionEditor {

    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    }

    fun dp(a: Activity, v: Float): Int = Maniflow.dpf(a, v)

    fun editText(a: Activity, hint: String): EditText = EditText(a).apply {
        this.hint = hint
        setHintTextColor(Theme.current.textMuted)
        setTextColor(Theme.current.textPrimary)
        textSize = 16f
        background = Maniflow.rounded(a, Theme.current.cardBg, 10, Theme.current.borderColor, 1f)
        setPadding(dp(a, 10f), dp(a, 9f), dp(a, 10f), dp(a, 9f))
    }

    fun checkBox(a: Activity, text: String): CheckBox = CheckBox(a).apply {
        this.text = text
        setTextColor(Theme.current.textPrimary)
        textSize = 15f
    }

    fun sectionLabel(a: Activity, text: String): TextView =
        Maniflow.text(a, text.uppercase(java.util.Locale.US), 12f, Theme.current.accentPrimary, bold = true).apply {
            letterSpacing = 0.1f
            setPadding(dp(a, 4f), dp(a, 14f), dp(a, 4f), dp(a, 6f))
        }

    fun ctxRow(a: Activity, text: String, color: Int, onClick: () -> Unit): TextView =
        TextView(a).apply {
            this.text = text
            textSize = 15f
            setTextColor(color)
            setPadding(dp(a, 10f), dp(a, 10f), dp(a, 10f), dp(a, 10f))
            background = Maniflow.rounded(a, Theme.current.cardBg, 10, Theme.current.borderColor, 1f)
            setOnClickListener { onClick() }
        }

    fun actionFieldHints(type: String): Triple<String?, String?, String?> = when (type) {
        Actions.SCRIPT -> Triple("termux task name", null, null)
        Actions.SHELL -> Triple("shell command (sh -c ...)", null, null)
        Actions.INTENT -> Triple("broadcast action (com.pkg.ACTION)", "extras  key:value (per line | or ;)", "package target (optional)")
        Actions.NOTIFY -> Triple("notify text (%VAR% ok)", null, null)
        Actions.ROOT -> Triple("root command", null, null)
        Actions.FLASH -> Triple("text to flash (%VAR% ok)", "duration seconds (0 = short ~2s)", null)
        Actions.SPEAK -> Triple("text to speak (%VAR% ok)", "pitch 0.5-2 (default 1)", "speech rate 0.5-2 (default 1)")
        Actions.HTTP -> Triple("URL (%VAR% ok)", null, null)
        Actions.VAR_SET -> Triple("variable name", "value to set (math ok, %VAR% ok)", null)
        Actions.VAR_SPLIT -> Triple("variable name", "splitter (default ,)", null)
        Actions.VAR_JOIN -> Triple("variable base name (%A1, %A2...)", "joiner (default ,)", "max parts (optional)")
        Actions.VAR_QUERY -> Triple("variable to query", "store result in variable", "default if unset")
        Actions.ARRAY_SET -> Triple("array name (e.g. arr)", "values: a,b,c | 1..5 | %otherarr", null)
        Actions.ARRAY_PUSH -> Triple("array name", "element(s) to add (a,b,c)", null)
        Actions.ARRAY_PROCESS -> Triple("array name", "op: reverse | sort | sort desc | unique | upper | lower | trim", null)
        Actions.ARRAY_POP -> Triple("array name", "index to pop (blank = last)", "store popped value in variable")
        Actions.ARRAY_CLEAR -> Triple("array name", null, null)
        Actions.IF -> Triple("condition: %var = x | %var > 5 | %var ~ *foo*", null, null)
        Actions.FOR -> Triple("values: 1..5 | a,b,c | %arr", "loop variable (default %loop)", null)
        Actions.WAIT -> Triple("seconds to wait", null, null)
        Actions.WAIT_UNTIL -> Triple("condition: %var = x | %var > 5", "timeout seconds (default 30)", null)
        Actions.GOTO -> Triple("action number or label", null, null)
        Actions.CANCEL_ALARM -> Triple("alarm label to cancel (blank = all)", null, null)
        Actions.ALARM_VOLUME -> Triple("alarm volume 0-15", null, null)
        Actions.TASK_RUN, Actions.TASK_STOP, Actions.TASK_ENABLE, Actions.TASK_DISABLE ->
            Triple("task name or id", null, null)
        Actions.PROFILE_ENABLE, Actions.PROFILE_DISABLE, Actions.PROFILE_DELETE ->
            Triple("profile name or id", null, null)
        else -> Triple(null, null, null)
    }

    fun actionTypePick(a: Activity, onPick: (String) -> Unit) {
        val t = Theme.current
        val defs = Actions.CATALOG
        val search = editText(a, "search actions... (e.g. var, wifi, for)").apply {
            setTextColor(t.textPrimary)
            setHintTextColor(t.textMuted)
        }
        val lv = ListView(a).apply {
            divider = null
            dividerHeight = 0
            setSelector(android.R.color.transparent)
        }
        var filtered = defs.toList()
        val adapter = object : BaseAdapter() {
            override fun getCount() = filtered.size
            override fun getItem(pos: Int) = filtered[pos]
            override fun getItemId(pos: Int) = pos.toLong()
            override fun getView(pos: Int, convertView: View?, parent: ViewGroup): View {
                val d = filtered[pos]
                val row = LinearLayout(a).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(a, 14f), dp(a, 8f), dp(a, 14f), dp(a, 8f))
                    setBackgroundColor(t.surfaceBg)
                }
                row.addView(Maniflow.text(a, d.label, 15f, t.textPrimary))
                row.addView(Maniflow.text(a, d.category, 11f, t.textMuted))
                return row
            }
        }
        lv.adapter = adapter
        fun applyQuery(q: String) {
            filtered = if (q.isBlank()) defs.toList()
            else defs.filter {
                it.label.contains(q, true) || it.type.contains(q, true) || it.category.contains(q, true)
            }
            adapter.notifyDataSetChanged()
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                applyQuery(s?.toString() ?: "")
            }
        })
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(a, 12f), dp(a, 8f), dp(a, 12f), dp(a, 4f))
            addView(search)
            addView(lv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(a, 360f)).apply {
                topMargin = dp(a, 6f)
            })
        }
        var pickerDialog: AlertDialog? = null
        pickerDialog = AlertDialog.Builder(a)
            .setTitle("ADD ACTION (${defs.size})")
            .setView(box)
            .setNegativeButton("CANCEL", null)
            .show()
        lv.setOnItemClickListener { _, _, pos, _ ->
            if (pos in filtered.indices) {
                onPick(filtered[pos].type)
                pickerDialog?.dismiss()
            }
        }
        applyQuery("")
    }

    /**
     * Builds the per-action "IF" section. Maintains its own term + connector
     * state and pushes the encoded condition to [onChange] on every edit.
     */
    fun condBuilder(a: Activity, initial: String, onChange: (String) -> Unit): View {
        val t = Theme.current
        var terms: MutableList<CondTerm> = mutableListOf()
        var joins: MutableList<String> = mutableListOf()
        CondSpec.parse(initial)?.let { (t, j) ->
            terms = t.toMutableList()
            joins = j.toMutableList()
        }
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }

        fun emit() = onChange(CondSpec.encode(terms, joins))

        fun rebuild() {
            box.removeAllViews()
            if (terms.isEmpty()) {
                box.addView(
                    Maniflow.text(a, "(no condition - action always runs)", 13f, t.textMuted).apply {
                        setPadding(dp(a, 4f), dp(a, 6f), dp(a, 4f), dp(a, 6f))
                    }
                )
            } else {
                terms.forEachIndexed { i, t ->
                    if (i > 0) {
                        val idx = i - 1
                        box.addView(
                            TextView(a).apply {
                                text = "  ${joins.getOrNull(idx)?.uppercase() ?: "AND"}  "
                                textSize = 12f
                                setTextColor(Theme.current.flowTintOrange)
                                setPadding(dp(a, 2f), dp(a, 4f), dp(a, 2f), dp(a, 4f))
                                setOnClickListener {
                                    connectorPick(a) { op ->
                                        joins[idx] = op
                                        emit()
                                        rebuild()
                                    }
                                }
                            }
                        )
                    }
                    val tIdx = i
                    box.addView(ctxRow(a, CondSpec.summary(listOf(t), emptyList()), Theme.current.textPrimary) {
                        condTermDialog(
                            a, t,
                            onSave = { nt ->
                                terms[tIdx] = nt
                                emit()
                                rebuild()
                            },
                            onRemove = {
                                terms.removeAt(tIdx)
                                val ji = (tIdx - 1).coerceAtLeast(0)
                                if (ji < joins.size) joins.removeAt(ji)
                                while (joins.size >= terms.size && joins.isNotEmpty()) joins.removeAt(joins.lastIndex)
                                emit()
                                rebuild()
                            }
                        )
                    })
                }
            }
            box.addView(ctxRow(a, "+ ADD CONDITION", Theme.current.accentPrimary) {
                val addTerm = { t: CondTerm ->
                    terms.add(t)
                    emit()
                    rebuild()
                }
                if (terms.isEmpty()) {
                    condTermDialog(a, CondTerm(""), onSave = addTerm, null)
                } else {
                    connectorPick(a) { op ->
                        joins.add(op)
                        condTermDialog(a, CondTerm(""), onSave = addTerm, null)
                    }
                }
            })
        }
        rebuild()
        return box
    }

    private fun connectorPick(a: Activity, onPick: (String) -> Unit) {
        val ops = arrayOf("AND", "OR", "XOR")
        AlertDialog.Builder(a)
            .setTitle("CONNECT CONDITION WITH")
            .setItems(ops) { _, which -> onPick(ops[which].lowercase()) }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun operatorPick(a: Activity, onPick: (String) -> Unit) {
        val ops = arrayOf("=", "!=", ">", ">=", "<", "<=", "~", "!~")
        AlertDialog.Builder(a)
            .setTitle("OPERATOR")
            .setItems(ops) { _, which -> onPick(ops[which]) }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    /** Picker over every variable currently set in the app (plus common built-ins). */
    fun varPick(a: Activity, current: String, onPick: (String) -> Unit) {
        val user = UserVars.entries(a).map { it.first }
        val builtins = listOf(
            "EVENT", "SUMMARY", "TIME", "DATE", "BATTERY", "RAM", "RAM_PCT",
            "DISK_FREE", "WIFI", "SCREEN", "AIRPLANE", "NET", "ROOT"
        )
        val names = (user + builtins).distinct().sorted()
        val labels = names.map { "%$it" }
        val cur = current.trim().removePrefix("%")
        val curIdx = names.indexOfFirst { it.equals(cur, true) }
        AlertDialog.Builder(a)
            .setTitle("APP VARIABLES")
            .setSingleChoiceItems(labels.toTypedArray(), curIdx) { _, which ->
                onPick(names[which])
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    /** Picker over every task set in the app; taps a task name into the field. */
    fun taskPick(a: Activity, current: String, onPick: (String) -> Unit) {
        val tasks = Store.tasks(a)
        val names = tasks.map { it.name }
        val cur = current.trim()
        val curIdx = names.indexOfFirst { it == cur || it.startsWith(cur) }
        AlertDialog.Builder(a)
            .setTitle("APP TASKS")
            .setSingleChoiceItems(names.toTypedArray(), curIdx) { _, which ->
                onPick(names[which])
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    /** Picker over every profile set in the app; taps a profile name into the field. */
    fun profilePick(a: Activity, current: String, onPick: (String) -> Unit) {
        val profiles = Store.profiles(a)
        val names = profiles.map { it.name }
        val cur = current.trim()
        val curIdx = names.indexOfFirst { it == cur || it.startsWith(cur) }
        AlertDialog.Builder(a)
            .setTitle("APP PROFILES")
            .setSingleChoiceItems(names.toTypedArray(), curIdx) { _, which ->
                onPick(names[which])
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    fun condTermDialog(
        a: Activity,
        existing: CondTerm,
        onSave: (CondTerm) -> Unit,
        onRemove: (() -> Unit)?
    ) {
        val t = Theme.current
        var op = existing.op
        val varEt = editText(a, "variable (san  or  %san)").apply {
            setText(existing.variable.removePrefix("%"))
        }
        val valEt = editText(a, "value  (%VAR% ok)").apply {
            setText(existing.value)
        }
        val opTv = TextView(a).apply {
            textSize = 15f
            setTextColor(t.textPrimary)
            text = "operator: $op"
            setPadding(dp(a, 10f), dp(a, 10f), dp(a, 10f), dp(a, 10f))
            background = Maniflow.rounded(a, t.cardBg, 10, t.borderColor, 1f)
            setOnClickListener {
                operatorPick(a) { newOp ->
                    op = newOp
                    text = "operator: $op"
                }
            }
        }
        val varsBtn = ctxRow(a, "PICK VARIABLE FROM APP", t.accentPrimary) {
            varPick(a, varEt.text.toString()) { name ->
                varEt.setText(name)
            }
        }
        val ll = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            addView(varsBtn)
            addView(sectionLabel(a, "VARIABLE"))
            addView(varEt)
            addView(sectionLabel(a, "OPERATOR"))
            addView(opTv)
            addView(sectionLabel(a, "VALUE"))
            addView(valEt)
        }
        val d = AlertDialog.Builder(a)
            .setTitle("IF CONDITION")
            .setView(ll)
            .setPositiveButton("OK") { _, _ ->
                val v = varEt.text.toString().trim().removePrefix("%")
                if (v.isBlank()) {
                    EventLog.push("[ui] enter a variable name")
                    return@setPositiveButton
                }
                onSave(CondTerm(v, op, valEt.text.toString()))
            }
            .setNegativeButton("CANCEL", null)
        if (onRemove != null) d.setNeutralButton("REMOVE") { _, _ -> onRemove() }
        d.show()
    }

    fun actionDialog(
        a: Activity,
        existing: Action,
        onSave: (Action) -> Unit,
        onRemove: (() -> Unit)? = null
    ) {
        val type = existing.type

        if (type == Actions.SET_ALARM) {
            ActionEditorDialogs.alarmDialog(a, existing, onSave, onRemove)
            return
        }

        if (type == Actions.HTTP) {
            ActionEditorDialogs.httpDialog(a, existing, onSave, onRemove)
            return
        }

        val t = Theme.current
        val (vh, eh, e2h) = actionFieldHints(type)

        val ll = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        if (Actions.noParams(type)) {
            ll.addView(
                Maniflow.text(a, "No parameters needed.\nRuns when the task reaches this step.", 14f, t.textMuted).apply {
                    setPadding(dp(a, 8f), dp(a, 8f), dp(a, 8f), dp(a, 8f))
                }
            )
        }

        var valueEt: EditText? = null
        var extraEt: EditText? = null
        var extra2Et: EditText? = null
        var appendCb: CheckBox? = null
        var appendSepEt: EditText? = null
        var suCb: CheckBox? = null

        val labelEt = editText(a, "label (optional, Goto can jump to it)").apply {
            setText(existing.label)
        }
        ll.addView(sectionLabel(a, "LABEL"))
        ll.addView(labelEt)

        if (vh != null) {
            valueEt = editText(a, vh).apply { setText(existing.value) }
            ll.addView(valueEt)
        }
        if (type == Actions.VAR_SET || type == Actions.ARRAY_SET) {
            val (wasAppend, wasSep) = Actions.appendCfg(existing.extra2)
            appendCb = checkBox(a, "append to existing value").apply { isChecked = wasAppend }
            ll.addView(appendCb)
            appendSepEt = editText(a, "").apply { setText(wasSep) }
            ll.addView(appendSepEt)
            appendCb.setOnCheckedChangeListener { _, isChecked ->
                appendSepEt?.visibility = if (isChecked) View.VISIBLE else View.GONE
            }
            appendSepEt.visibility = if (appendCb.isChecked) View.VISIBLE else View.GONE
        }
        if (eh != null) {
            extraEt = editText(a, eh).apply { setText(existing.extra) }
            ll.addView(extraEt)
        }
        if (e2h != null) {
            extra2Et = editText(a, e2h).apply { setText(existing.extra2) }
            ll.addView(extra2Et)
        }
        when (type) {
            Actions.TASK_RUN, Actions.TASK_STOP, Actions.TASK_ENABLE, Actions.TASK_DISABLE -> {
                ll.addView(ctxRow(a, "PICK TASK FROM APP", t.accentPrimary) {
                    taskPick(a, valueEt?.text?.toString() ?: "") { name ->
                        valueEt?.setText(name)
                    }
                })
            }
            Actions.PROFILE_ENABLE, Actions.PROFILE_DISABLE, Actions.PROFILE_DELETE -> {
                ll.addView(ctxRow(a, "PICK PROFILE FROM APP", t.accentPrimary) {
                    profilePick(a, valueEt?.text?.toString() ?: "") { name ->
                        valueEt?.setText(name)
                    }
                })
            }
            Actions.ARRAY_PROCESS -> {
                ll.addView(ctxRow(a, "PICK PROCESS OP", t.accentPrimary) {
                    val ops = arrayOf("reverse", "sort", "sort desc", "unique", "upper", "lower", "trim")
                    AlertDialog.Builder(a)
                        .setTitle("ARRAY PROCESS")
                        .setItems(ops) { _, which -> extraEt?.setText(ops[which]) }
                        .setNegativeButton("CANCEL", null)
                        .show()
                })
            }
        }
        if (Actions.needsPrivilege(type) && type != Actions.SET_ALARM) {
            suCb = checkBox(a, "Run with su (root)")
                .apply { isChecked = existing.extra2 == "su" }
            ll.addView(suCb)
            ll.addView(
                Maniflow.text(
                    a,
                    "The standard API for this action is restricted on newer Android versions. " +
                        "Tick to run it with su. Otherwise Shizuku is used when granted, or you get a " +
                        "notification telling you what to enable.",
                    12f, t.textMuted
                ).apply { setPadding(dp(a, 2f), dp(a, 2f), dp(a, 2f), dp(a, 8f)) }
            )
        }

        var condStr = existing.cond
        if (!Actions.isFlow(type)) {
            ll.addView(sectionLabel(a, "IF"))
            ll.addView(condBuilder(a, existing.cond) { condStr = it })
        }

        val d = AlertDialog.Builder(a)
            .setTitle("ACTION  ${existing.typeLabel()}")
            .setView(ll)
            .setPositiveButton("OK") { _, _ ->
                val extra2 = when {
                    type == Actions.VAR_SET || type == Actions.ARRAY_SET ->
                        if (appendCb?.isChecked == true) Actions.appendEncode(appendSepEt?.text?.toString() ?: "") else ""
                    suCb != null -> if (suCb!!.isChecked) "su" else ""
                    else -> extra2Et?.text?.toString() ?: ""
                }
                onSave(
                    Action(
                        type,
                        valueEt?.text?.toString() ?: "",
                        extraEt?.text?.toString() ?: "",
                        extra2,
                        condStr,
                        labelEt.text.toString()
                    )
                )
            }
            .setNegativeButton("CANCEL", null)
        if (onRemove != null) d.setNeutralButton("REMOVE") { _, _ -> onRemove() }
        d.show()
    }
}
