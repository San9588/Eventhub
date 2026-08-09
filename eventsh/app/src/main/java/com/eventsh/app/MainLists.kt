package com.eventsh.app

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.eventsh.app.engine.AppCtx
import com.eventsh.app.engine.Ctx
import com.eventsh.app.engine.DayCtx
import com.eventsh.app.engine.EventCtx
import com.eventsh.app.engine.LocationCtx
import com.eventsh.app.engine.Task
import com.eventsh.app.engine.TimeCtx
import com.eventsh.app.engine.VarCtx
import com.eventsh.app.ui.Maniflow
import com.eventsh.app.ui.Theme
import com.eventsh.app.ui.withAlpha

/**
 * MainActivity LIST ADAPTERS + shared row helpers - the Tasks / Vars / Log
 * list adapters plus the small views reused by every list screen.
 */

internal fun MainActivity.emptyLabel(msg: String): TextView = TextView(this).apply {
    text = msg
    textSize = 14f
    gravity = Gravity.CENTER
    setTextColor(Theme.current.textMuted)
    setLineSpacing(dp(4f).toFloat(), 1f)
}

internal fun MainActivity.cardWrap(card: View): View {
    val t = Theme.current
    val wrap = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(6f), dp(4f), dp(6f), dp(4f))
        setBackgroundColor(t.surfaceBg)
        addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    wrap.layoutParams = AbsListView.LayoutParams(
        AbsListView.LayoutParams.MATCH_PARENT,
        AbsListView.LayoutParams.WRAP_CONTENT
    )
    return wrap
}

/** Small tinted context chip shown inside expanded flow detail. */
internal fun MainActivity.ctxChip(c: Ctx): View {
    val act = this
    val t = Theme.current
    val (tag, color) = when (c) {
        is EventCtx -> "EV" to t.accentPrimary
        is TimeCtx -> "TM" to t.flowTintBlue
        is DayCtx -> "DY" to t.flowTintOrange
        is VarCtx -> "VA" to t.flowTintOrange
        is AppCtx -> "AP" to t.flowTintBlue
        is LocationCtx -> "LC" to t.flowTintOrange
        else -> "??" to t.textMuted
    }
    return TextView(this).apply {
        text = "[$tag] ${c.summary()}"
        textSize = 12f
        setTextColor(color)
        setPadding(dp(8f), dp(5f), dp(8f), dp(5f))
        background = Maniflow.rounded(act, t.accentPrimary.withAlpha(22), 8)
    }
}

internal fun MainActivity.actionLines(task: Task): List<Pair<Int, String>> {
    val out = ArrayList<Pair<Int, String>>()
    for (a in task.actions) {
        val icon = when (a.type) {
            com.eventsh.app.engine.Actions.SCRIPT, com.eventsh.app.engine.Actions.SHELL, com.eventsh.app.engine.Actions.ROOT -> R.drawable.ic_terminal
            com.eventsh.app.engine.Actions.INTENT -> R.drawable.ic_send
            com.eventsh.app.engine.Actions.NOTIFY, com.eventsh.app.engine.Actions.FLASH -> R.drawable.ic_notify
            com.eventsh.app.engine.Actions.VAR_SET, com.eventsh.app.engine.Actions.VAR_SPLIT, com.eventsh.app.engine.Actions.VAR_JOIN, com.eventsh.app.engine.Actions.VAR_QUERY,
            com.eventsh.app.engine.Actions.ARRAY_SET, com.eventsh.app.engine.Actions.ARRAY_PUSH, com.eventsh.app.engine.Actions.ARRAY_PROCESS, com.eventsh.app.engine.Actions.ARRAY_POP, com.eventsh.app.engine.Actions.ARRAY_CLEAR -> R.drawable.ic_var
            com.eventsh.app.engine.Actions.IF, com.eventsh.app.engine.Actions.ELSE, com.eventsh.app.engine.Actions.END_IF, com.eventsh.app.engine.Actions.FOR, com.eventsh.app.engine.Actions.END_FOR -> R.drawable.ic_list
            else -> R.drawable.ic_settings
        }
        val cond = a.condTerms()?.let { (terms, joins) -> com.eventsh.app.engine.CondSpec.summary(terms, joins) }
        val labelPrefix = if (a.label.isBlank()) "" else "{${a.label}}  "
        out += icon to ("$labelPrefix${a.typeLabel()}  ${a.summary()}" +
            (if (cond.isNullOrBlank()) "" else "   [IF $cond]"))
    }
    return out
}

/** One action line used inside task cards / flow detail blocks. */
internal fun MainActivity.actionRow(icon: Int, text: String, color: Int): View {
    val t = Theme.current
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(4f), dp(5f), dp(4f), dp(5f))
    }
    val iv = ImageView(this).apply {
        setImageResource(icon)
        setColorFilter(color)
    }
    row.addView(iv, LinearLayout.LayoutParams(dp(18f), dp(18f)))
    row.addView(
        Maniflow.text(this, text, 13f, t.textPrimary).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        },
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(10f)
        }
    )
    return row
}

/** Small pill button used inside flow detail blocks. */
internal fun MainActivity.miniButton(label: String, color: Int, onClick: () -> Unit): TextView {
    val act = this
    return TextView(act).apply {
        text = label
        textSize = 12f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setTextColor(color)
        setPadding(dp(10f), dp(5f), dp(10f), dp(5f))
        background = Maniflow.rounded(act, Theme.current.cardBg, 8, borderColor = color, borderDp = 1f)
        setOnClickListener { onClick() }
    }
}

internal class TaskListAdapter(private val act: MainActivity) : BaseAdapter() {
    override fun getCount() = act.tasks.size
    override fun getItem(pos: Int) = act.tasks[pos]
    override fun getItemId(pos: Int) = pos.toLong()

    override fun getView(pos: Int, convertView: View?, parent: ViewGroup): View {
        val t = act.tasks[pos]
        val enabled = t.enabled
        val usedBy = act.profiles.count { it.taskId == t.id }
        val tint = if (enabled) Theme.current.flowTintGreen else Theme.current.textMuted

        val edit = ImageView(act).apply {
            setImageResource(R.drawable.ic_edit)
            setColorFilter(Theme.current.textMuted)
            contentDescription = "Edit task"
            setOnClickListener { act.openTaskEditor(t) }
        }
        val content = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        content.addView(
            Maniflow.listRow(
                act, R.drawable.ic_list, tint, t.name,
                subtitle = "${t.actions.size} action(s)  ·  retry ${t.retries}  ·  used by $usedBy profile(s)" +
                    (if (enabled) "" else "  ·  DISABLED"),
                trailing = edit,
                showDivider = false,
                dimmed = !enabled
            )
        )
        val acts = act.actionLines(t)
        if (acts.isNotEmpty()) {
            for ((ic, txt) in acts) {
                content.addView(
                    Maniflow.listRow(
                        act, ic, if (enabled) Theme.current.flowTintGreen else Theme.current.textMuted,
                        txt, showDivider = false
                    )
                )
            }
        }
        return act.cardWrap(Maniflow.card(act, content))
    }
}

internal class VarListAdapter(private val act: MainActivity) : BaseAdapter() {
    override fun getCount() = act.userVars.size
    override fun getItem(pos: Int) = act.userVars[pos]
    override fun getItemId(pos: Int) = pos.toLong()

    override fun getView(pos: Int, convertView: View?, parent: ViewGroup): View {
        val v = act.userVars[pos]
        val tint = if (v.disk) Theme.current.flowTintOrange else Theme.current.flowTintBlue
        val row = Maniflow.listRow(
            act, R.drawable.ic_var, tint, v.name,
            subtitle = v.value.ifBlank { "(empty)" },
            showDivider = true
        )
        val wrap = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(act.dp(4f), 0, act.dp(4f), 0)
            addView(row)
        }
        wrap.layoutParams = AbsListView.LayoutParams(
            AbsListView.LayoutParams.MATCH_PARENT,
            AbsListView.LayoutParams.WRAP_CONTENT
        )
        return wrap
    }
}

internal class LogListAdapter(private val act: MainActivity) : BaseAdapter() {
    override fun getCount() = act.logs.size
    override fun getItem(pos: Int) = act.logs[pos]
    override fun getItemId(pos: Int) = pos.toLong()

    override fun getView(pos: Int, convertView: View?, parent: ViewGroup): View {
        val t = Theme.current
        val line = act.logs[pos]
        val color = when {
            line.contains("FAILED") || line.contains("failed") -> t.statPink
            line.contains("[perm]") -> t.flowTintOrange
            line.startsWith("[") && line.contains("]") -> t.textPrimary
            else -> t.textMuted
        }
        val row = Maniflow.listRow(
            act, R.drawable.ic_log, color, line,
            showDivider = false
        )
        return act.cardWrap(row)
    }
}
