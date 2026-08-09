package com.eventsh.app

import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
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
import com.eventsh.app.ui.C
import com.eventsh.app.ui.UI

/**
 * Shared programmatic UI for editing Task actions. Used by both MainActivity
 * and TaskActivity so a task's action list behaves identically everywhere.
 */
object ActionEditor {

    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    }

    fun dp(a: Activity, v: Float): Int = UI.dp(a, v)

    fun editText(a: Activity, hint: String): EditText = EditText(a).apply {
        this.hint = hint
        setHintTextColor(C.hint)
        setTextColor(C.text)
        textSize = 16f
        background = UI.rounded(C.surface, 10f, C.border, 1f)
        setPadding(dp(a, 10f), dp(a, 9f), dp(a, 10f), dp(a, 9f))
    }

    fun checkBox(a: Activity, text: String): CheckBox = CheckBox(a).apply {
        this.text = text
        setTextColor(C.text)
        textSize = 15f
    }

    fun sectionLabel(a: Activity, text: String): TextView =
        UI.text(a, text.uppercase(java.util.Locale.US), 12f, C.accent, bold = true).apply {
            letterSpacing = 0.1f
            setPadding(dp(a, 4f), dp(a, 14f), dp(a, 4f), dp(a, 6f))
        }

    fun ctxRow(a: Activity, text: String, color: Int, onClick: () -> Unit): TextView =
        TextView(a).apply {
            this.text = text
            textSize = 15f
            setTextColor(color)
            setPadding(dp(a, 10f), dp(a, 10f), dp(a, 10f), dp(a, 10f))
            background = UI.rounded(C.card, 10f, C.border, 1f)
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
        val defs = Actions.CATALOG
        val search = editText(a, "search actions... (e.g. var, wifi, for)").apply {
            setTextColor(C.text)
            setHintTextColor(C.hint)
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
                    setBackgroundColor(C.bg)
                }
                row.addView(UI.text(a, d.label, 15f, C.text))
                row.addView(UI.text(a, d.category, 11f, C.hint))
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
                    UI.text(a, "(no condition - action always runs)", 13f, C.hint).apply {
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
                                setTextColor(C.warning)
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
                    box.addView(ctxRow(a, CondSpec.summary(listOf(t), emptyList()), C.text) {
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
            box.addView(ctxRow(a, "+ ADD CONDITION", C.accent) {
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
        var op = existing.op
        val varEt = editText(a, "variable (san  or  %san)").apply {
            setText(existing.variable.removePrefix("%"))
        }
        val valEt = editText(a, "value  (%VAR% ok)").apply {
            setText(existing.value)
        }
        val opTv = TextView(a).apply {
            textSize = 15f
            setTextColor(C.text)
            text = "operator: $op"
            setPadding(dp(a, 10f), dp(a, 10f), dp(a, 10f), dp(a, 10f))
            background = UI.rounded(C.surface, 10f, C.border, 1f)
            setOnClickListener {
                operatorPick(a) { newOp ->
                    op = newOp
                    text = "operator: $op"
                }
            }
        }
        val varsBtn = ctxRow(a, "PICK VARIABLE FROM APP", C.accent) {
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
            alarmDialog(a, existing, onSave, onRemove)
            return
        }

        if (type == Actions.HTTP) {
            httpDialog(a, existing, onSave, onRemove)
            return
        }

        val (vh, eh, e2h) = actionFieldHints(type)

        val ll = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        if (Actions.noParams(type)) {
            ll.addView(
                UI.text(a, "No parameters needed.\nRuns when the task reaches this step.", 14f, C.textSec).apply {
                    setPadding(dp(a, 8f), dp(a, 8f), dp(a, 8f), dp(a, 8f))
                }
            )
        }

        var valueEt: EditText? = null
        var extraEt: EditText? = null
        var extra2Et: EditText? = null
        var appendCb: CheckBox? = null
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
        if (type == Actions.VAR_SET) {
            appendCb = checkBox(a, "append to existing value").apply { isChecked = existing.extra2 == "append" }
            ll.addView(appendCb)
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
                ll.addView(ctxRow(a, "PICK TASK FROM APP", C.accent) {
                    taskPick(a, valueEt?.text?.toString() ?: "") { name ->
                        valueEt?.setText(name)
                    }
                })
            }
            Actions.PROFILE_ENABLE, Actions.PROFILE_DISABLE, Actions.PROFILE_DELETE -> {
                ll.addView(ctxRow(a, "PICK PROFILE FROM APP", C.accent) {
                    profilePick(a, valueEt?.text?.toString() ?: "") { name ->
                        valueEt?.setText(name)
                    }
                })
            }
            Actions.ARRAY_PROCESS -> {
                ll.addView(ctxRow(a, "PICK PROCESS OP", C.accent) {
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
                UI.text(
                    a,
                    "The standard API for this action is restricted on newer Android versions. " +
                        "Tick to run it with su. Otherwise Shizuku is used when granted, or you get a " +
                        "notification telling you what to enable.",
                    12f, C.hint
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
                    type == Actions.VAR_SET -> if (appendCb?.isChecked == true) "append" else ""
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

    /** Custom editor for the Set Alarm action: time, snooze, vibration, label, sound. */
    private fun alarmDialog(
        a: Activity,
        existing: Action,
        onSave: (Action) -> Unit,
        onRemove: (() -> Unit)?
    ) {
        var hour = existing.value.split(":").getOrNull(0)?.toIntOrNull() ?: 7
        var minute = existing.value.split(":").getOrNull(1)?.toIntOrNull() ?: 0
        var cfg = Actions.alarmCfg(existing.extra2)

        val labelEt = editText(a, "alarm label").apply { setText(existing.extra) }
        val vibrateCb = checkBox(a, "vibration on").apply { isChecked = cfg.vibrate }
        val suCb = checkBox(a, "Run with su").apply { isChecked = cfg.useSu }

        lateinit var timeTv: TextView
        timeTv = TextView(a).apply {
            textSize = 18f
            text = String.format(java.util.Locale.US, "%02d:%02d", hour, minute)
            setTextColor(C.primary)
            setPadding(dp(a, 10f), dp(a, 12f), dp(a, 10f), dp(a, 12f))
            background = UI.rounded(C.surface, 10f, C.border, 1f)
            setOnClickListener {
                TimePickerDialog(a, { _, h, m ->
                    hour = h
                    minute = m
                    timeTv.text = String.format(java.util.Locale.US, "%02d:%02d", hour, minute)
                }, hour, minute, true).show()
            }
        }

        val ll = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            addView(sectionLabel(a, "TIME"))
            addView(timeTv)
            addView(sectionLabel(a, "ALARM"))
            addView(labelEt)
            addView(vibrateCb)
            addView(suCb)
        }

        val d = AlertDialog.Builder(a)
            .setTitle("ACTION  ${existing.typeLabel()}")
            .setMessage("Sets the alarm in the system clock app (no root, no UI). Tick 'Run with su' to set it via root instead.")
            .setView(ll)
            .setPositiveButton("OK") { _, _ ->
                val label = labelEt.text.toString().trim()
                val newCfg = cfg.copy(
                    vibrate = vibrateCb.isChecked,
                    useSu = suCb.isChecked
                )
                onSave(
                    Action(
                        Actions.SET_ALARM,
                        String.format(java.util.Locale.US, "%02d:%02d", hour, minute),
                        label,
                        Actions.alarmEncode(newCfg),
                        existing.cond,
                        existing.label
                    )
                )
            }
            .setNegativeButton("CANCEL", null)
        if (onRemove != null) d.setNeutralButton("REMOVE") { _, _ -> onRemove() }
        d.show()
    }

    /** Custom editor for the HTTP Request action (Tasker "HTTP Request" style). */
    private fun httpDialog(
        a: Activity,
        existing: Action,
        onSave: (Action) -> Unit,
        onRemove: (() -> Unit)?
    ) {
        var cfg = Actions.httpCfg(existing.extra2)

        val urlEt = editText(a, "URL  (https://..., %VAR% ok)").apply { setText(existing.value) }
        var method = cfg.method.uppercase().ifBlank { "GET" }
        val methodTv = ctxRow(a, "METHOD: $method", C.accent) {
            val ops = arrayOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD")
            AlertDialog.Builder(a)
                .setTitle("HTTP METHOD")
                .setItems(ops) { _, which ->
                    method = ops[which]
                    methodTv.text = "METHOD: $method"
                }
                .setNegativeButton("CANCEL", null)
                .show()
        }
        val headersEt = editText(a, "headers  key:value  (one per line / | / ;)").apply {
            setText(cfg.headers)
            setMinLines(2)
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
        }
        val queryEt = editText(a, "query params  key:value  (%VAR% ok)").apply {
            setText(cfg.query)
            setMinLines(2)
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
        }
        val bodyEt = editText(a, "request body (POST/PUT/PATCH, %VAR% ok)").apply {
            setText(cfg.body)
            setMinLines(2)
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
        }
        val ctypeEt = editText(a, "content-type (optional)").apply { setText(cfg.contentType) }
        val timeoutEt = editText(a, "timeout seconds (default 15)").apply {
            setText(cfg.timeoutSec.toString())
        }
        val resultEt = editText(a, "result variable (default %http_result)").apply {
            setText(cfg.resultVar)
        }
        val saveEt = editText(a, "save body to file path (optional, /sdcard/... or relative)").apply {
            setText(cfg.saveFile)
        }
        val redirectCb = checkBox(a, "follow redirects").apply { isChecked = cfg.followRedirects }

        val ll = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            addView(urlEt)
            addView(methodTv)
            addView(sectionLabel(a, "HEADERS"))
            addView(headersEt)
            addView(sectionLabel(a, "QUERY PARAMS"))
            addView(queryEt)
            addView(sectionLabel(a, "BODY (used by POST/PUT/PATCH)"))
            addView(bodyEt)
            addView(ctypeEt)
            addView(timeoutEt)
            addView(resultEt)
            addView(saveEt)
            addView(redirectCb)
        }
        val scroll = android.widget.ScrollView(a).apply { addView(ll) }

        val d = AlertDialog.Builder(a)
            .setTitle("ACTION  ${existing.typeLabel()}")
            .setView(scroll)
            .setPositiveButton("OK") { _, _ ->
                val newCfg = Actions.HttpCfg(
                    method = method,
                    headers = headersEt.text.toString(),
                    contentType = ctypeEt.text.toString().trim(),
                    body = bodyEt.text.toString(),
                    query = queryEt.text.toString(),
                    timeoutSec = (timeoutEt.text.toString().toIntOrNull() ?: 15).coerceIn(1, 120),
                    resultVar = resultEt.text.toString().trim().removePrefix("%")
                        .ifBlank { "http_result" },
                    saveFile = saveEt.text.toString().trim(),
                    followRedirects = redirectCb.isChecked
                )
                onSave(
                    Action(
                        Actions.HTTP,
                        urlEt.text.toString().trim(),
                        method,
                        Actions.httpEncode(newCfg),
                        existing.cond,
                        existing.label
                    )
                )
            }
            .setNegativeButton("CANCEL", null)
        if (onRemove != null) d.setNeutralButton("REMOVE") { _, _ -> onRemove() }
        d.show()
    }
}
