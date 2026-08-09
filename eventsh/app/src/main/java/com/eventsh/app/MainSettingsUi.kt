package com.eventsh.app

import android.content.Intent
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.eventsh.app.engine.RootBridge
import com.eventsh.app.engine.Store
import com.eventsh.app.service.EventService
import com.eventsh.app.ui.C
import com.eventsh.app.ui.UI

/**
 * MainActivity SETTINGS TAB - all UI code for the "Settings" tab.
 *
 * NOTE: these are Kotlin extension functions on MainActivity, so they can use
 * the activity's fields (dp, handler, status views, ...) exactly like before.
 */
fun MainActivity.buildSettings() {
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
        val overlayRow = actionRowContent("Display over other apps", "background Flash popups", {
            startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:$packageName")))
        })
        overlayStatusTv = overlayRow.second
        permCard.addView(overlayRow.first, matchWrap())
        val exactRow = actionRowContent("Exact alarms", "let alarms fire at the exact time (Android 12+)", {
            if (Build.VERSION.SDK_INT >= 31) {
                startActivity(Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    android.net.Uri.parse("package:$packageName")))
            }
            handler.postDelayed({ refreshScreen() }, 900)
        })
        exactStatusTv = exactRow.second
        permCard.addView(exactRow.first, matchWrap())
        val battRow = actionRowContent("Ignore battery optimization", "prevent the OS from killing timers/alarms", {
            startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                android.net.Uri.parse("package:$packageName")))
            handler.postDelayed({ refreshScreen() }, 900)
        })
        battOptStatusTv = battRow.second
        permCard.addView(battRow.first, matchWrap())
        val smsRow = actionRowContent("SMS + Phone + Bluetooth", "runtime permissions for events", {
            requestPermissions(arrayOf(
                android.Manifest.permission.RECEIVE_SMS,
                android.Manifest.permission.READ_PHONE_STATE,
                android.Manifest.permission.BLUETOOTH_CONNECT
            ), 20)
        })
        permCard.addView(smsRow.first, matchWrap())
        val locRow = actionRowContent("Location", "geo-fence triggers (ACCESS_FINE_LOCATION)", {
            requestPermissions(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION), 31)
        })
        locStatusTv = locRow.second
        permCard.addView(locRow.first, matchWrap())
        root.addView(permCard, matchWrap())

        // ---- DATA
        root.addView(sectionLabel("DATA"))
        val dataCard = cardContainer()
        dataCard.addView(actionRowContent("Export", "backup profiles + tasks + variables", { exportRules() }).first, matchWrap())
        dataCard.addView(actionRowContent("Import", "restore profiles + tasks + variables from backup", { importRules() }).first, matchWrap())
        root.addView(dataCard, matchWrap())

        // ---- ABOUT
        root.addView(sectionLabel("ABOUT"))
        val aboutCard = cardContainer()
        aboutText = UI.text(this, "", 13f, C.textSec)
        aboutCard.addView(aboutText, matchWrap())
        root.addView(aboutCard, matchWrap())
        root.addView(UI.vsep(this, dp(80f)))
    }

fun MainActivity.matchWrap(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

fun MainActivity.cardContainer(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(4f), dp(4f), dp(4f), dp(4f))
        background = UI.rounded(C.surface, 14f)
    }

fun MainActivity.switchRow(
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

fun MainActivity.actionRowContent(
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
