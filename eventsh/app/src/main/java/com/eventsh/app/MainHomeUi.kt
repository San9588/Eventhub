package com.eventsh.app

import android.content.Intent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.eventsh.app.engine.AppCtx
import com.eventsh.app.engine.LocationCtx
import com.eventsh.app.engine.Profile
import com.eventsh.app.engine.Task
import com.eventsh.app.ui.Maniflow
import com.eventsh.app.ui.Theme

/**
 * MainActivity HOME TAB - the redesigned dashboard: curved hero header,
 * floating chat card, snapshot stats and the flows list with toggles.
 */
fun MainActivity.buildHome(): View {
    val t = Theme.current
    homeList = ListView(this).apply {
        divider = null
        dividerHeight = 0
        setSelector(android.R.color.transparent)
        setBackgroundColor(t.surfaceBg)
        clipToPadding = false
        setPadding(0, 0, 0, dp(96f))
    }
    homeList.addHeaderView(buildHomeHero(), null, false)
    flowAdapter = FlowListAdapter(this)
    homeList.adapter = flowAdapter
    homeList.setOnItemClickListener { _, _, pos, _ ->
        val item = homeList.getItemAtPosition(pos)
        if (item is Profile) toggleExpand(item.id)
    }
    return homeList
}

private fun MainActivity.buildHomeHero(): View {
    val t = Theme.current
    val col = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(t.surfaceBg)
        setPadding(dp(t.spacingUnit.toFloat()), 0, dp(t.spacingUnit.toFloat()), 0)
    }

    val header = Maniflow.header(
        this, "Maniflow",
        status = if (running) "Running" else "Service stopped",
        headline = flowHeadline(),
        statusPills = listOf(
            "SERVICE" to running,
            "ROOT" to if (!rootChecked) null else rootOk
        ),
        actionIcon = R.drawable.ic_settings,
        actionContentDescription = "Settings",
        onAction = { selectTab(TAB_SETTINGS) }
    )
    homeHeaderStatus = header.findViewWithTag("maniflow.header.status") as TextView
    homeHeadline = header.findViewWithTag("maniflow.header.headline") as TextView
    homePillService = header.findViewWithTag("maniflow.header.pill.0") as TextView
    homePillRoot = header.findViewWithTag("maniflow.header.pill.1") as TextView
    col.addView(header)

    col.addView(buildChatCard(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = -dp(12f)
    })

    col.addView(Maniflow.sectionLabel(this, "Aaj ka snapshot", topMargin = 20))
    val statRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
    }
    statRow.addView(
        Maniflow.statCard(this, R.drawable.ic_bolt, t.statGreen, "0", "Active flows", valueTag = "stat.flows"),
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    )
    statRow.addView(
        Maniflow.statCard(this, R.drawable.ic_list, t.statOrange, "0", "Total tasks", valueTag = "stat.tasks"),
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8f) }
    )
    statRow.addView(
        Maniflow.statCard(this, R.drawable.ic_log, t.statPink, "--%", "Battery", valueTag = "stat.battery"),
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8f) }
    )
    statFlows = statRow.findViewWithTag("stat.flows") as TextView
    statTasks = statRow.findViewWithTag("stat.tasks") as TextView
    statBattery = statRow.findViewWithTag("stat.battery") as TextView
    col.addView(statRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = dp(6f)
    })

    col.addView(Maniflow.sectionLabel(this, "Tumhare profiles", topMargin = 22))
    homeEmpty = Maniflow.text(this, "No profiles yet - tap + to begin", 14f, t.textMuted).apply {
        setPadding(dp(2f), dp(6f), dp(2f), dp(10f))
    }
    col.addView(homeEmpty)
    return col
}

private fun MainActivity.buildChatCard(): View {
    val t = Theme.current
    val ctx = this
    val inner = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    inner.addView(ImageView(ctx).apply {
        setImageResource(R.drawable.ic_ai)
        setColorFilter(t.accentPrimary)
    }, LinearLayout.LayoutParams(dp(22f), dp(22f)))
    inner.addView(
        Maniflow.text(ctx, "Maniflow se bolo, wo bana dega...", 14f, t.textMuted).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        },
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(12f)
        }
    )
    inner.addView(ImageView(ctx).apply {
        setImageResource(R.drawable.ic_send)
        setColorFilter(t.headerText)
        setPadding(dp(10f), dp(10f), dp(10f), dp(10f))
        background = Maniflow.rounded(ctx, t.accentPrimary, 999)
        contentDescription = "Send"
        setOnClickListener { startActivity(Intent(ctx, ThemeStudioActivity::class.java)) }
    }, LinearLayout.LayoutParams(dp(38f), dp(38f)).apply {
        marginStart = dp(8f)
    })
    return Maniflow.card(ctx, inner)
}

private fun MainActivity.flowHeadline(): String =
    if (profiles.isEmpty()) "No profiles yet - tap + to begin"
    else "${profiles.size} flows tumhare din ko dekh rahe hain"

private fun flowTint(pos: Int): Int {
    val tints = listOf(
        Theme.current.flowTintGreen,
        Theme.current.flowTintOrange,
        Theme.current.flowTintPink,
        Theme.current.flowTintBlue,
        Theme.current.flowTintPurple
    )
    return tints[pos % tints.size]
}

/** Flow icon picked from the profile's trigger type. */
private fun flowIcon(p: Profile): Int = when {
    p.isDailyTimer || p.isOneShotTimer -> R.drawable.ic_notify
    p.timeCtx != null -> R.drawable.ic_log
    p.contexts.any { it is AppCtx } -> R.drawable.ic_send
    p.contexts.any { it is LocationCtx } -> R.drawable.ic_terminal
    else -> R.drawable.ic_bolt
}

internal class FlowListAdapter(private val act: MainActivity) : BaseAdapter() {
    override fun getCount() = act.profiles.size
    override fun getItem(pos: Int) = act.profiles[pos]
    override fun getItemId(pos: Int) = pos.toLong()

    override fun getView(pos: Int, convertView: View?, parent: ViewGroup): View {
        val p = act.profiles[pos]
        val enabled = p.enabled
        val tint = flowTint(pos)
        val task = act.tasks.find { it.id == p.taskId }
        val sub = p.contextLine().ifBlank {
            if (p.isDailyTimer) "daily ${p.daily}"
            else if (p.isOneShotTimer) "one-shot timer"
            else "no trigger set"
        }
        val toggle = Maniflow.toggle(act, enabled) { act.toggleProfile(p) }
        val row = Maniflow.listRow(
            act, flowIcon(p), tint, p.name,
            subtitle = sub,
            trailing = toggle,
            onClick = { act.toggleExpand(p.id) },
            showDivider = false,
            dimmed = !enabled
        )

        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        col.addView(row)
        if (p.id in act.expandedIds) {
            col.addView(act.flowDetail(p, task, tint, enabled))
        }
        col.addView(Maniflow.divider(act))
        col.layoutParams = AbsListView.LayoutParams(
            AbsListView.LayoutParams.MATCH_PARENT,
            AbsListView.LayoutParams.WRAP_CONTENT
        )
        return col
    }
}

/** Expanded block under a flow row: triggers, linked task, actions and actions. */
internal fun MainActivity.flowDetail(p: Profile, task: Task?, tint: Int, enabled: Boolean): View {
    val t = Theme.current
    val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    content.addView(Maniflow.sectionLabel(this, "Triggers"))
    if (p.contexts.isEmpty()) {
        content.addView(
            Maniflow.text(this, "none", 13f, t.textMuted).apply {
                setPadding(dp(2f), dp(2f), dp(2f), dp(8f))
            }
        )
    } else {
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (c in p.contexts) {
            wrap.addView(ctxChip(c), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(4f)
            })
        }
        content.addView(wrap)
    }

    content.addView(Maniflow.sectionLabel(this, "Linked task", topMargin = 10))
    content.addView(
        Maniflow.listRow(
            this, R.drawable.ic_list, t.flowTintBlue,
            task?.name ?: "(no task linked)",
            subtitle = task?.let { "${it.actions.size} action(s)" },
            showDivider = false
        )
    )

    val acts = task?.let { actionLines(it) } ?: emptyList()
    if (acts.isNotEmpty()) {
        content.addView(Maniflow.sectionLabel(this, "Actions", topMargin = 10))
        for ((ic, txt) in acts) {
            content.addView(Maniflow.listRow(this, ic, tint, txt, showDivider = false))
        }
    }

    val btnRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END
        setPadding(0, dp(6f), 0, 0)
    }
    btnRow.addView(Maniflow.button(this, "TEST", false) { testProfile(p) })
    btnRow.addView(Maniflow.button(this, "EDIT", true) { profileDialog(p) },
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(8f)
        })
    btnRow.addView(Maniflow.button(this, "DELETE", false) { deleteProfile(p) },
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(8f)
        })
    content.addView(btnRow)
    return Maniflow.card(this, content)
}
