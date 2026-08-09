package com.eventsh.app

import android.content.Intent
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.eventsh.app.engine.RootBridge
import com.eventsh.app.engine.Store
import com.eventsh.app.service.EventService
import com.eventsh.app.theme.ThemeController
import com.eventsh.app.theme.ThemeHistoryEntry
import com.eventsh.app.theme.ThemeStore
import com.eventsh.app.ui.Maniflow
import com.eventsh.app.ui.ManiflowToggle
import com.eventsh.app.ui.Theme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * MainActivity SETTINGS TAB - all UI code for the "Settings" tab, rebuilt on
 * the shared Maniflow components so it stays consistent with the rest of the app.
 */
fun MainActivity.buildSettings() {
    val t = Theme.current
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(t.surfaceBg)
    }
    root.addView(Maniflow.header(this, "Settings"))

    val scroll = ScrollView(this).apply { setBackgroundColor(t.surfaceBg) }
    settingsScroll = scroll
    val body = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(6f), dp(8f), dp(6f), dp(24f))
    }
    scroll.addView(body, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    contentFrame.addView(root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

    body.addView(Maniflow.sectionLabel(this, "Engine"))
    body.addView(engineCard(), matchWrap())

    body.addView(Maniflow.sectionLabel(this, "Permissions", topMargin = 12))
    body.addView(permissionsCard(), matchWrap())

    body.addView(Maniflow.sectionLabel(this, "Data", topMargin = 12))
    body.addView(dataCard(), matchWrap())

    body.addView(Maniflow.sectionLabel(this, "AI Settings", topMargin = 12))
    body.addView(aiSettingsCard(), matchWrap())

    body.addView(Maniflow.sectionLabel(this, "Recent Themes", topMargin = 12))
    recentThemesBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    body.addView(Maniflow.card(this, recentThemesBox), matchWrap())

    body.addView(Maniflow.sectionLabel(this, "Help", topMargin = 12))
    body.addView(helpCard(), matchWrap())

    body.addView(Maniflow.sectionLabel(this, "About", topMargin = 12))
    body.addView(aboutCard(), matchWrap())
}

fun MainActivity.matchWrap(): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

private fun MainActivity.toggleRow(
    icon: Int,
    tint: Int,
    title: String,
    subtitleTv: TextView,
    toggle: ManiflowToggle,
    showDivider: Boolean = true
): View {
    val t = Theme.current
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        setPadding(dp(2f), dp(10f), dp(2f), dp(10f))
    }
    row.addView(Maniflow.badge(this, icon, tint))
    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    col.addView(Maniflow.text(this, title, 16f, t.textPrimary, bold = true))
    col.addView(subtitleTv)
    row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
        marginStart = dp(12f)
    })
    row.addView(toggle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        marginStart = dp(8f)
    })
    val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    wrap.addView(row)
    if (showDivider) wrap.addView(Maniflow.divider(this))
    return wrap
}

private fun MainActivity.statusRow(
    icon: Int,
    tint: Int,
    title: String,
    subtitle: String,
    onClick: () -> Unit
): Pair<View, TextView> {
    val status = Maniflow.text(this, "", 13f, Theme.current.textMuted).apply {
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    val row = Maniflow.listRow(this, icon, tint, title, subtitle = subtitle, trailing = status, onClick = onClick)
    return row to status
}

private fun MainActivity.engineCard(): View {
    val t = Theme.current
    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    svcSwitch = Maniflow.toggle(this, running) { on ->
        if (on) startServiceCompat() else stopService(Intent(this, EventService::class.java))
        handler.postDelayed({ refreshScreen() }, 400)
    }
    svcSwitchRow = Maniflow.text(this, "listening for events", 12f, t.textMuted)
    col.addView(toggleRow(R.drawable.ic_bolt, t.accentPrimary, "Background service", svcSwitchRow, svcSwitch))

    autoSwitch = Maniflow.toggle(this, Store.autostart(this)) { checked -> Store.setAutostart(this, checked) }
    col.addView(
        toggleRow(
            R.drawable.ic_log, t.flowTintBlue, "Start on boot",
            Maniflow.text(this, "restart engine after reboot", 12f, t.textMuted),
            autoSwitch,
            showDivider = false
        )
    )
    return Maniflow.card(this, col)
}

private fun MainActivity.permissionsCard(): View {
    val t = Theme.current
    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    val rootRow = statusRow(R.drawable.ic_terminal, t.flowTintGreen, "Root", "check su binary availability", {
        RootBridge.checkAsync()
        handler.postDelayed({ refreshScreen() }, 900)
    })
    rootStatusTv = rootRow.second
    col.addView(rootRow.first)

    val shizukuRow = statusRow(R.drawable.ic_settings, t.flowTintBlue, "Shizuku", "run restricted actions without root (Android 13+)", {
        com.eventsh.app.engine.ShizukuClient.requestPermission(this)
        handler.postDelayed({ refreshScreen() }, 900)
    })
    shizukuStatusTv = shizukuRow.second
    col.addView(shizukuRow.first)

    val usageRow = statusRow(R.drawable.ic_log, t.flowTintOrange, "Usage access", "detect foreground app (app triggers)", {
        startActivity(Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS))
    })
    usageStatusTv = usageRow.second
    col.addView(usageRow.first)

    val notifRow = statusRow(R.drawable.ic_notify, t.statPink, "Notification access", "read posted notifications (notify_post)", {
        startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    })
    notifStatusTv = notifRow.second
    col.addView(notifRow.first)

    val overlayRow = statusRow(R.drawable.ic_ai, t.flowTintPurple, "Display over other apps", "background Flash popups", {
        startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            android.net.Uri.parse("package:$packageName")))
    })
    overlayStatusTv = overlayRow.second
    col.addView(overlayRow.first)

    val exactRow = statusRow(R.drawable.ic_notify, t.flowTintOrange, "Exact alarms", "let alarms fire at the exact time (Android 12+)", {
        if (Build.VERSION.SDK_INT >= 31) {
            startActivity(Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                android.net.Uri.parse("package:$packageName")))
        }
        handler.postDelayed({ refreshScreen() }, 900)
    })
    exactStatusTv = exactRow.second
    col.addView(exactRow.first)

    val battRow = statusRow(R.drawable.ic_log, t.statGreen, "Ignore battery optimization", "prevent the OS from killing timers/alarms", {
        startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            android.net.Uri.parse("package:$packageName")))
        handler.postDelayed({ refreshScreen() }, 900)
    })
    battOptStatusTv = battRow.second
    col.addView(battRow.first)

    val smsRow = statusRow(R.drawable.ic_send, t.flowTintBlue, "SMS + Phone + Bluetooth", "runtime permissions for events", {
        requestPermissions(arrayOf(
            android.Manifest.permission.RECEIVE_SMS,
            android.Manifest.permission.READ_PHONE_STATE,
            android.Manifest.permission.BLUETOOTH_CONNECT
        ), 20)
    })
    col.addView(smsRow.first)

    val locRow = statusRow(R.drawable.ic_bolt, t.flowTintGreen, "Location", "geo-fence triggers (ACCESS_FINE_LOCATION)", {
        requestPermissions(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION), 31)
    })
    locStatusTv = locRow.second
    col.addView(locRow.first)
    return Maniflow.card(this, col)
}

private fun MainActivity.dataCard(): View {
    val t = Theme.current
    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    col.addView(statusRow(R.drawable.ic_send, t.flowTintBlue, "Export", "backup profiles + tasks + variables", { exportRules() }).first)
    col.addView(statusRow(R.drawable.ic_terminal, t.flowTintOrange, "Import", "restore profiles + tasks + variables from backup", { importRules() }).first)
    return Maniflow.card(this, col)
}

private fun MainActivity.helpCard(): View {
    val t = Theme.current
    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    col.addView(statusRow(R.drawable.ic_ai, t.statPink, "Help", "every action documented inside the app", { Help.show(this) }).first)
    return Maniflow.card(this, col)
}

private fun MainActivity.aboutCard(): View {
    val t = Theme.current
    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    aboutText = Maniflow.text(this, "", 13f, t.textMuted)
    col.addView(aboutText)
    return Maniflow.card(this, col)
}

// ============================================================================
//  AI SETTINGS + RECENT THEMES
// ============================================================================

private fun MainActivity.aiSettingsCard(): View {
    val t = Theme.current
    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    aiKeyField = EditText(this).apply {
        hint = "Gemini API key (AIza...)"
        inputType = android.text.InputType.TYPE_CLASS_TEXT or
            android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        setHintTextColor(t.textMuted)
        setTextColor(t.textPrimary)
        textSize = 15f
        background = Maniflow.rounded(this@MainActivity, t.surfaceBg, 10, borderColor = t.borderColor, borderDp = 1f)
        setPadding(dp(10f), dp(9f), dp(10f), dp(9f))
    }
    col.addView(aiKeyField)

    aiKeyStatusTv = Maniflow.text(this, "", 12f, t.textMuted)
    col.addView(aiKeyStatusTv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = dp(6f)
    })

    val saveRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END
    }
    saveRow.addView(Maniflow.button(this, "SAVE", true) { saveGeminiKey() })
    col.addView(saveRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = dp(8f)
    })

    col.addView(Maniflow.divider(this), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = dp(12f)
    })

    col.addView(Maniflow.listRow(
        this, R.drawable.ic_ai, t.accentPrimary, "Theme Studio",
        subtitle = "AI se apni theme banao",
        onClick = { startActivity(Intent(this@MainActivity, ThemeStudioActivity::class.java)) },
        showDivider = false
    ))

    col.addView(
        Maniflow.button(this, "Default theme pe wapas jao", false) {
            ThemeController.resetToDefault(this@MainActivity)
            rebuildUi()
            Toast.makeText(this@MainActivity, "Default theme wapas aa gaya", Toast.LENGTH_SHORT).show()
        },
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12f)
        }
    )
    return Maniflow.card(this, col)
}

private fun MainActivity.saveGeminiKey() {
    val key = aiKeyField.text.toString().trim()
    if (key.isBlank()) {
        aiKeyStatusTv.text = if (ThemeStore.hasApiKey(this)) "Key pehle se saved hai" else "Key empty nahi ho sakti"
        return
    }
    ThemeStore.saveApiKey(this, key)
    aiKeyField.text.clear()
    Toast.makeText(this, "Gemini API key saved", Toast.LENGTH_SHORT).show()
    refreshAiSettings()
}

/** Re-populates the AI status line + the recent-themes list (called on refresh). */
internal fun MainActivity.refreshAiSettings() {
    if (!::aiKeyStatusTv.isInitialized || !::recentThemesBox.isInitialized) return
    val t = Theme.current
    aiKeyStatusTv.text = if (ThemeStore.hasApiKey(this)) "API key saved" else "API key add nahi hui"
    recentThemesBox.removeAllViews()
    val entries = ThemeStore.history(this)
    if (entries.isEmpty()) {
        recentThemesBox.addView(
            Maniflow.text(this, "Abhi koi recent theme nahi hai", 13f, t.textMuted).apply {
                setPadding(dp(2f), dp(4f), dp(2f), dp(4f))
            }
        )
    } else {
        entries.forEachIndexed { idx, entry ->
            recentThemesBox.addView(recentThemeRow(entry, idx))
        }
    }
}

private fun MainActivity.recentThemeRow(entry: ThemeHistoryEntry, idx: Int): View {
    val t = Theme.current
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(2f), dp(10f), dp(2f), dp(10f))
        isClickable = true
        isFocusable = true
        setOnClickListener {
            ThemeController.apply(this@MainActivity, entry.tokens)
            rebuildUi()
            Toast.makeText(this@MainActivity, "Theme wapas lag gaya", Toast.LENGTH_SHORT).show()
        }
    }

    val swatch = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    listOf(entry.tokens.headerBg, entry.tokens.accentPrimary, entry.tokens.cardBg).forEach { c ->
        swatch.addView(
            View(this).apply { background = Maniflow.rounded(this@MainActivity, c, 6) },
            LinearLayout.LayoutParams(dp(24f), dp(24f)).apply { marginEnd = dp(6f) }
        )
    }
    row.addView(swatch)

    val time = SimpleDateFormat("dd MMM HH:mm", Locale.US).format(Date(entry.at))
    row.addView(
        Maniflow.text(this, "Theme ${idx + 1}  •  $time", 14f, t.textPrimary, bold = true),
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(10f)
        }
    )

    val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    wrap.addView(row)
    wrap.addView(Maniflow.divider(this))
    return wrap
}
