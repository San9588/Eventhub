package com.eventsh.app

import com.eventsh.app.ui.showThemed

import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.eventsh.app.engine.Action
import com.eventsh.app.engine.Actions
import com.eventsh.app.ui.Maniflow
import com.eventsh.app.ui.Theme

/**
 * ActionEditor's two specialized dialogs - Set Alarm and HTTP Request.
 * Split out of ActionEditor.kt so that file stays under the size limit.
 */
object ActionEditorDialogs {

    /** Custom editor for the Set Alarm action: time, snooze, vibration, label, sound. */
    fun alarmDialog(
        a: Activity,
        existing: Action,
        onSave: (Action) -> Unit,
        onRemove: (() -> Unit)?
    ) {
        val t = Theme.current
        var hour = existing.value.split(":").getOrNull(0)?.toIntOrNull() ?: 7
        var minute = existing.value.split(":").getOrNull(1)?.toIntOrNull() ?: 0
        var cfg = Actions.alarmCfg(existing.extra2)

        val labelEt = ActionEditor.editText(a, "alarm label").apply { setText(existing.extra) }
        val vibrateCb = ActionEditor.checkBox(a, "vibration on").apply { isChecked = cfg.vibrate }
        val suCb = ActionEditor.checkBox(a, "Run with su").apply { isChecked = cfg.useSu }

        lateinit var timeTv: TextView
        timeTv = TextView(a).apply {
            textSize = 18f
            text = String.format(java.util.Locale.US, "%02d:%02d", hour, minute)
            setTextColor(t.accentPrimary)
            setPadding(ActionEditor.dp(a, 10f), ActionEditor.dp(a, 12f), ActionEditor.dp(a, 10f), ActionEditor.dp(a, 12f))
            background = Maniflow.rounded(a, t.cardBg, 10, t.borderColor, 1f)
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
            addView(ActionEditor.sectionLabel(a, "TIME"))
            addView(timeTv)
            addView(ActionEditor.sectionLabel(a, "ALARM"))
            addView(labelEt)
            addView(vibrateCb)
            addView(suCb)
        }

        val d = AlertDialog.Builder(a)
            .setTitle("ACTION  ${existing.typeLabel()}")
            .setMessage(
                "Sets the alarm in the system clock app (no root, no UI). " +
                    "On Android 12+ it needs the 'Exact alarms' special permission " +
                    "(Settings tab > Exact alarms) or the clock app refuses it. " +
                    "Tick 'Run with su' to set it via root instead."
            )
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
        com.eventsh.app.ui.Maniflow.showDialog(d.create())
    }

    /** Custom editor for the HTTP Request action. */
    fun httpDialog(
        a: Activity,
        existing: Action,
        onSave: (Action) -> Unit,
        onRemove: (() -> Unit)?
    ) {
        val t = Theme.current
        var cfg = Actions.httpCfg(existing.extra2)

        val urlEt = ActionEditor.editText(a, "URL  (https://..., %VAR% ok)").apply { setText(existing.value) }
        var method = cfg.method.uppercase().ifBlank { "GET" }
        lateinit var methodTv: TextView
        methodTv = ActionEditor.ctxRow(a, "METHOD: $method", t.accentPrimary) {
            val ops = arrayOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD")
            AlertDialog.Builder(a)
                .setTitle("HTTP METHOD")
                .setItems(ops) { _, which ->
                    method = ops[which]
                    methodTv.text = "METHOD: $method"
                }
                .setNegativeButton("CANCEL", null)
                .showThemed()
        }
        val headersEt = ActionEditor.editText(a, "headers  key:value  (one per line / | / ;)").apply {
            setText(cfg.headers)
            setMinLines(2)
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
        }
        val queryEt = ActionEditor.editText(a, "query params  key:value  (%VAR% ok)").apply {
            setText(cfg.query)
            setMinLines(2)
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
        }
        val bodyEt = ActionEditor.editText(a, "request body (POST/PUT/PATCH, %VAR% ok)").apply {
            setText(cfg.body)
            setMinLines(2)
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
        }
        val ctypeEt = ActionEditor.editText(a, "content-type (optional)").apply { setText(cfg.contentType) }
        val timeoutEt = ActionEditor.editText(a, "timeout seconds (default 15)").apply {
            setText(cfg.timeoutSec.toString())
        }
        val resultEt = ActionEditor.editText(a, "result variable (default %http_result)").apply {
            setText(cfg.resultVar)
        }
        val saveEt = ActionEditor.editText(a, "save body to file path (optional, /sdcard/... or relative)").apply {
            setText(cfg.saveFile)
        }
        val redirectCb = ActionEditor.checkBox(a, "follow redirects").apply { isChecked = cfg.followRedirects }

        val ll = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            addView(urlEt)
            addView(methodTv)
            addView(ActionEditor.sectionLabel(a, "HEADERS"))
            addView(headersEt)
            addView(ActionEditor.sectionLabel(a, "QUERY PARAMS"))
            addView(queryEt)
            addView(ActionEditor.sectionLabel(a, "BODY (used by POST/PUT/PATCH)"))
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
        com.eventsh.app.ui.Maniflow.showDialog(d.create())
    }
}
