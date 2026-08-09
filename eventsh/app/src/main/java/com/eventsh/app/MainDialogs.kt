package com.eventsh.app

import android.app.AlertDialog
import android.widget.LinearLayout
import android.widget.TextView
import com.eventsh.app.engine.Action
import com.eventsh.app.engine.Actions
import com.eventsh.app.engine.EventLog
import com.eventsh.app.engine.Permissions
import com.eventsh.app.engine.Profile
import com.eventsh.app.engine.Scheduler
import com.eventsh.app.engine.Store
import com.eventsh.app.engine.Task
import com.eventsh.app.engine.UserVars
import com.eventsh.app.ui.C
import com.eventsh.app.ui.UI
import java.util.UUID

/**
 * MainActivity DIALOGS - the Timer dialog, Variable dialog and the
 * "permissions needed" dialog.
 *
 * These are Kotlin extension functions on MainActivity.
 */
fun MainActivity.timerDialog() {
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
fun MainActivity.varDialog(existing: MainActivity.VarEntry?) {
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
fun MainActivity.showPermissionsDialog(missing: List<Permissions.Need>) {
        val act = this
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
                setOnClickListener { need.open(act) }
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
