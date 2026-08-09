package com.eventsh.app

import android.app.AlertDialog
import android.content.Intent
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.eventsh.app.engine.AppCtx
import com.eventsh.app.engine.Ctx
import com.eventsh.app.engine.Dispatcher
import com.eventsh.app.engine.EventCtx
import com.eventsh.app.engine.EventHub
import com.eventsh.app.engine.EventLog
import com.eventsh.app.engine.LocationCtx
import com.eventsh.app.engine.Permissions
import com.eventsh.app.engine.Profile
import com.eventsh.app.engine.Scheduler
import com.eventsh.app.engine.Store
import com.eventsh.app.engine.Task
import com.eventsh.app.engine.TimeCtx
import com.eventsh.app.engine.UserVars
import com.eventsh.app.engine.VarCtx
import com.eventsh.app.ui.C
import com.eventsh.app.ui.UI
import java.io.File
import java.util.UUID

/**
 * MainActivity PROFILE + TASK UI - profile enable/disable/delete/test,
 * backup export/import, the profile editor dialog and the task editor launcher.
 *
 * These are Kotlin extension functions on MainActivity.
 */
fun MainActivity.toggleProfile(p: Profile) {
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

fun MainActivity.deleteProfile(p: Profile) {
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

fun MainActivity.testProfile(p: Profile) {
        val ev = p.eventActions.firstOrNull() ?: "test"
        EventLog.push("[test] firing '${p.name}' on $ev")
        Dispatcher.fire(this, p, ev, mapOf("summary" to "manual test"))
    }

fun MainActivity.exportRules() {
        try {
            val json = com.eventsh.app.engine.Backup.export(this)
            val pretty = json.toString(2)
            val outDir = getExternalFilesDir(null) ?: filesDir
            val f = File(outDir, "eventsh_backup.json")
            f.writeText(pretty)
            val np = Store.profiles(this).size
            val nt = Store.tasks(this).size
            val nv = UserVars.diskEntries(this).size
            EventLog.push("[bak] exported $np profile(s) + $nt task(s) + $nv var(s)")
            AlertDialog.Builder(this)
                .setTitle("BACKUP EXPORTED")
                .setMessage("$np profiles, $nt tasks, $nv variables\n\n${f.absolutePath}\n\n'SAVE TO...' keeps a copy anywhere (Downloads, Drive, another app) for restore on any device.")
                .setPositiveButton("SAVE TO...") { _, _ -> saveBackupAs(pretty) }
                .setNegativeButton("OK", null)
                .show()
        } catch (e: Exception) {
            EventLog.push("[bak] export FAILED: ${e.message?.take(100) ?: "error"}")
        }
        refreshScreen()
    }

fun MainActivity.saveBackupAs(content: String) {
        pendingBackupText = content
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "eventsh_backup.json")
        }
        try {
            startActivityForResult(intent, REQ_CREATE_BACKUP)
        } catch (e: Exception) {
            EventLog.push("[bak] save-as unavailable: ${e.message?.take(80) ?: "error"}")
        }
    }

fun MainActivity.importRules() {
        val outDir = getExternalFilesDir(null) ?: filesDir
        val f = File(outDir, "eventsh_backup.json")
        if (f.exists()) {
            try {
                confirmImport(f.readText())
            } catch (e: Exception) {
                EventLog.push("[bak] read backup failed: ${e.message?.take(80) ?: "error"}")
            }
        } else {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain"))
            }
            try {
                startActivityForResult(intent, REQ_OPEN_BACKUP)
            } catch (e: Exception) {
                EventLog.push("[bak] no file picker available: ${e.message?.take(80) ?: "error"}")
            }
        }
    }

fun MainActivity.confirmImport(raw: String) {
        val o = com.eventsh.app.engine.Backup.parse(raw)
        if (o == null) {
            EventLog.push("[bak] import FAILED: not an eventsh backup file")
            refreshScreen()
            return
        }
        if (com.eventsh.app.engine.Backup.isNewer(o)) {
            EventLog.push("[bak] warning: backup version ${o.optInt("version")} is newer than this app")
        }
        AlertDialog.Builder(this)
            .setTitle("IMPORT BACKUP")
            .setMessage("Replace current profiles/tasks/variables, or merge the backup into them?")
            .setPositiveButton("REPLACE") { _, _ -> doImport(o, com.eventsh.app.engine.Backup.Mode.REPLACE) }
            .setNeutralButton("MERGE") { _, _ -> doImport(o, com.eventsh.app.engine.Backup.Mode.MERGE) }
            .setNegativeButton("CANCEL", null)
            .show()
    }

fun MainActivity.doImport(o: org.json.JSONObject, mode: com.eventsh.app.engine.Backup.Mode) {
        try {
            com.eventsh.app.engine.Backup.apply(this, o, mode)
            if (isServiceRunning()) EventHub.resync(this)
            val np = Store.profiles(this).size
            val nt = Store.tasks(this).size
            val nv = UserVars.diskEntries(this).size
            EventLog.push("[bak] imported ($mode): $np profile(s), $nt task(s), $nv var(s)")
        } catch (e: Exception) {
            EventLog.push("[bak] import FAILED: ${e.message?.take(100) ?: "error"}")
        }
        refreshScreen()
    }
fun MainActivity.profileDialog(existing: Profile?) {
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

        // ---- contexts editor (Event / Time / Day / Variable / App) ----
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
                val locCtx = contexts.filterIsInstance<LocationCtx>().firstOrNull()
                val isTimer = existing != null && (existing.isOneShotTimer || existing.isDailyTimer)
                if (eventCtx == null && timeCtx == null && appCtx == null && varCtx == null &&
                    locCtx == null && !isTimer
                ) {
                    EventLog.push("[ui] add an EVENT, TIME, APP, VARIABLE or LOCATION trigger")
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

fun MainActivity.taskNameOr(tid: String): String =
        if (tid.isBlank()) "(tap to link a task)" else (tasks.find { it.id == tid }?.name ?: "(unlinked task)")

fun MainActivity.taskPickDialog(current: String, onPick: (String) -> Unit) {
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
    /** Opens the full-page Task editor; null starts a fresh task. */
fun MainActivity.openTaskEditor(t: Task?) {
        startActivity(Intent(this, TaskActivity::class.java).apply {
            if (t != null) putExtra("taskId", t.id)
        })
    }
