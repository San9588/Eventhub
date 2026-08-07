package com.eventsh.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import com.eventsh.app.engine.Rule

class TerminalView(context: Context) : View(context) {

    // --- data from MainActivity ---
    var rules: List<Rule> = emptyList()
    var screen: Int = 0                       // 0 rules, 1 log, 2 cfg
    var logs: List<String> = emptyList()
    var rootOk: Boolean = false
    var rootChecked: Boolean = false
    var running: Boolean = false
    var ramText: String = "PSS 0MB"
    var cpuText: String = "CPU 0.0%"
    var battText: String = "--%"
    var timeText: String = "--:--"
    var ramPctText: String = "RAM 0%"
    var diskText: String = "DSK 0MB"
    var armedCount: Int = 0

    var onToggleRule: ((Rule) -> Unit)? = null
    var onEditRule: ((Rule) -> Unit)? = null
    var onAddRule: (() -> Unit)? = null
    var onAddTimer: (() -> Unit)? = null
    var onNav: ((Int) -> Unit)? = null
    var onServiceToggle: (() -> Unit)? = null
    var onRootCheck: (() -> Unit)? = null
    var onAddVar: (() -> Unit)? = null
    var onEditVar: ((VarRow) -> Unit)? = null

    data class VarRow(val name: String, val value: String, val disk: Boolean)

    var userVars: List<VarRow> = emptyList()
    private var varScroll = 0f

    private val navZones = ArrayList<Pair<String, Float>>()

    private var scrollY = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
    }

    private val pp: Float
        get() = (resources.displayMetrics.density * 2f).toInt().coerceAtLeast(3).toFloat()

    private val rowsStart = 44f
    private val rowH = 20f

    override fun onDraw(canvas: Canvas) {
        val u = pp.toFloat()
        val W = width / u
        val H = height / u
        canvas.drawColor(P.BG)
        when (screen) {
            0 -> drawRules(canvas, u, W, H)
            1 -> drawLog(canvas, u, W, H)
            2 -> drawVar(canvas, u, W, H)
            3 -> drawCfg(canvas, u, W, H)
        }
        drawNav(canvas, u, W, H)
    }

    // ------------------------------------------------------------- helpers
    private fun drawText(c: Canvas, text: String, x: Float, y: Float, scale: Int, color: Int, bg: Int? = null) {
        val u = pp.toFloat()
        textPaint.textSize = 7f * scale * u
        textPaint.color = color
        if (bg != null) {
            paint.color = bg
            c.drawRect(x * u, y * u, (x + textWidth(text, scale)) * u, (y + 9f * scale) * u, paint)
        }
        c.drawText(text, x * u, (y + 7.5f * scale) * u, textPaint)
    }

    private fun textWidth(text: String, scale: Int): Float {
        val u = pp.toFloat()
        textPaint.textSize = 7f * scale * u
        return textPaint.measureText(text) / u
    }

    private fun drawBox(c: Canvas, x: Float, y: Float, w: Float, h: Float, fill: Int, outline: Int) {
        paint.color = fill
        c.drawRect(x * pp, y * pp, (x + w) * pp, (y + h) * pp, paint)
        paint.color = outline
        paint.style = Paint.Style.STROKE
        c.drawRect(x * pp + 0.5f, y * pp + 0.5f, (x + w) * pp - 0.5f, (y + h) * pp - 0.5f, paint)
        paint.style = Paint.Style.FILL
    }

    private fun drawDot(c: Canvas, x: Float, y: Float, color: Int, s: Int = 3) {
        paint.color = color
        c.drawRect(x * pp, y * pp, (x + s) * pp, (y + s) * pp, paint)
    }

    private fun drawToggle(c: Canvas, x: Float, y: Float, on: Boolean) {
        if (on) {
            paint.color = 0xFF091E11.toInt()
            c.drawRect(x * pp, y * pp, (x + 13) * pp, (y + 8) * pp, paint)
            paint.color = P.GREEN2
            c.drawRect((x + 1) * pp, (y + 1) * pp, (x + 9) * pp, (y + 7) * pp, paint)
            paint.color = P.GREEN
            c.drawRect((x + 9) * pp, (y + 2) * pp, (x + 12) * pp, (y + 6) * pp, paint)
        } else {
            paint.color = 0xFF091209.toInt()
            c.drawRect(x * pp, y * pp, (x + 13) * pp, (y + 8) * pp, paint)
            paint.color = P.OFF
            c.drawRect((x + 1) * pp, (y + 1) * pp, (x + 4) * pp, (y + 7) * pp, paint)
            paint.color = P.DIM
            c.drawRect((x + 5) * pp, (y + 3) * pp, (x + 11) * pp, (y + 4) * pp, paint)
        }
    }

    private fun statusBar(c: Canvas, u: Float, W: Float) {
        drawText(c, timeText, 2f, 2f, 1, P.TXT)
        drawText(c, battText, W - 30, 3f, 1, P.TXT)
        drawBox(c, W - 15, 3f, 11f, 5f, P.BG, P.DIM)
        paint.color = P.DIM
        c.drawRect((W - 4) * pp, 4f * pp, (W - 3) * pp, 7f * pp, paint)
        paint.color = P.GREEN
        c.drawRect((W - 13) * pp, 5f * pp, (W - 8) * pp, 6f * pp, paint)
    }

    // ------------------------------------------------------------- RULES
    private fun drawRules(c: Canvas, u: Float, W: Float, H: Float) {
        statusBar(c, u, W)
        // logo
        val lw = textWidth("EVENTSH", 2)
        drawText(c, "EVENTSH", (W - lw) / 2, 15f, 2, P.GREEN)
        val tw = textWidth("EVENT ENGINE", 1)
        drawText(c, "EVENT ENGINE", (W - tw) / 2, 31f, 1, P.TXT)
        // service chip
        drawBox(c, 4f, 40f, W - 8, 9f, P.BG2, P.BORDER)
        drawDot(c, 7f, 43f, if (running) P.GREEN else P.OFF)
        drawText(c, if (running) "SERVICE ACTIVE" else "SERVICE STOPPED", 11f, 41f, 1, if (running) P.GREEN else P.OFF)
        val rootTxt = if (rootChecked) (if (rootOk) "SU:ON" else "SU:OFF") else "SU:?"
        val rw = textWidth(rootTxt, 1)
        drawText(c, rootTxt, W - 6 - rw, 41f, 1, if (rootOk) P.AMBER else P.DIM)

        // list header
        drawText(c, "EVENT", 10f, 53f, 1, P.DIM)
        drawText(c, "ARM", W - 16, 53f, 1, P.DIM)
        paint.color = P.DIMROW
        c.drawRect(4 * pp, 61 * pp, (W - 4) * pp, 62 * pp, paint)

        // rows (scrollable, clipped)
        val navTop = H - 26f
        val listTop = 63f
        val listBottom = navTop - 12f
        val listH = listBottom - listTop
        val totalH = rules.size * rowH
        val maxScroll = (totalH - listH).coerceAtLeast(0f)
        scrollY = scrollY.coerceIn(0f, maxScroll)

        val save = c.save()
        c.clipRect(0f, listTop * pp, width.toFloat(), listBottom * pp)
        rowEditRects.clear()
        var i = 0
        for (r in rules) {
            val y = listTop + i * rowH - scrollY
            if (y > listBottom) break
            if (y + rowH < listTop) { i++; continue }
            // selected (first) highlight
            if (i == 0) drawBox(c, 2f, y, W - 4, rowH, P.BG2, P.BORDER)
            drawDot(c, 5f, y + 3, if (r.enabled) P.GREEN else P.OFF)
            drawText(c, r.label, 10f, y + 2, 1, if (r.enabled) P.GREEN else P.OFF)
            drawToggle(c, W - 16, y + 1, r.enabled)
            if (r.taskName.isNotBlank()) {
                drawText(c, "> ${r.taskName}", 10f, y + 11, 1, P.TXT)
                if (r.cooldownSec > 0) {
                    val cd = "cd ${r.cooldownSec}s"
                    drawText(c, cd, W - 10 - textWidth(cd, 1), y + 11, 1, P.AMBER)
                }
            }
            // edit button (E)
            drawBox(c, W - 8, y + 11, 7f, 8f, P.BG2, P.BORDER)
            drawText(c, "E", W - 7, y + 12, 1, P.GREEN)
            rowEditRects.add(Box(W - 8, y + 11, 7f, 8f))
            i++
        }
        c.restoreToCount(save)

        // add rule / add timer buttons
        val addRule = "[+RULE]"
        drawBox(c, 4f, navTop - 10, textWidth(addRule, 1) + 2, 8f, P.BG2, P.BORDER)
        drawText(c, addRule, 5f, navTop - 8, 1, P.GREEN)
        lastAddRuleRect = Box(4f, navTop - 10, textWidth(addRule, 1) + 2, 8f)
        val addTimer = "[+TIMER]"
        drawBox(c, W - 8 - (textWidth(addTimer, 1) + 2), navTop - 10, textWidth(addTimer, 1) + 2, 8f, P.BG2, P.BORDER)
        drawText(c, addTimer, W - 7 - textWidth(addTimer, 1), navTop - 8, 1, P.AMBER)
        lastAddTimerRect = Box(W - 8 - (textWidth(addTimer, 1) + 2), navTop - 10, textWidth(addTimer, 1) + 2, 8f)

        // footer stats
        paint.color = P.DIMROW
        c.drawRect(4 * pp, navTop * pp, (W - 4) * pp, (navTop + 1) * pp, paint)
        drawText(c, "$ramText $cpuText", 4f, navTop + 2, 1, P.TXT)
        val armedTxt = "${armedCount}/${rules.size} ARM"
        drawText(c, armedTxt, W - textWidth(armedTxt, 1) - 2, navTop + 2, 1, P.AMBER)
        if (rules.isEmpty()) drawText(c, "no rules", (W - textWidth("no rules", 1)) / 2, navTop - 8, 1, P.OFF)
    }

    private val rowEditRects = ArrayList<Box>()
    private var lastAddRuleRect: Box? = null
    private var lastAddTimerRect: Box? = null

    // ------------------------------------------------------------- LOG
    private fun drawLog(c: Canvas, u: Float, W: Float, H: Float) {
        statusBar(c, u, W)
        val tw = textWidth("EVENT LOG", 1)
        drawText(c, "EVENT LOG", (W - tw) / 2, 18f, 1, P.GREEN)
        paint.color = P.DIMROW
        c.drawRect(4 * pp, 28 * pp, (W - 4) * pp, 29 * pp, paint)
        val navTop = H - 26f
        val save = c.save()
        c.clipRect(0f, 31 * pp, width.toFloat(), navTop * pp)
        var y = 33f
        for (line in logs) {
            drawText(c, line, 6f, y, 1, P.TXT)
            y += 9
            if (y > navTop) break
        }
        if (logs.isEmpty()) drawText(c, "no events yet", (W - textWidth("no events yet", 1)) / 2, 40f, 1, P.OFF)
        c.restoreToCount(save)
    }

    // ------------------------------------------------------------- CFG
    private fun drawCfg(c: Canvas, u: Float, W: Float, H: Float) {
        statusBar(c, u, W)
        val tw = textWidth("CONFIG", 1)
        drawText(c, "CONFIG", (W - tw) / 2, 18f, 1, P.GREEN)
        paint.color = P.DIMROW
        c.drawRect(4 * pp, 28 * pp, (W - 4) * pp, 29 * pp, paint)

        var y = 36f
        fun line(label: String, value: String, vcolor: Int) {
            drawText(c, label, 8f, y, 1, P.TXT)
            drawText(c, value, W - 8 - textWidth(value, 1), y, 1, vcolor)
            y += 9
        }
        line("ROOT", if (rootChecked) (if (rootOk) "ON" else "OFF") else "?",
            if (rootOk) P.AMBER else P.DIM)
        line("SERVICE", if (running) "ON" else "OFF", if (running) P.GREEN else P.RED)
        line("BRIDGE", "TERMX.TASKER", P.TXT)
        line("LISTENERS", "${rules.count { it.enabled }}", P.GREEN)
        line("RAM", ramPctText, P.GREEN)
        line("DISK", diskText, P.GREEN)
        y += 4

        val rootLabel = "[ ROOT CHECK ]"
        drawBox(c, 8f, y - 1, textWidth(rootLabel, 1) + 2, 8f, P.BG2, P.BORDER)
        drawText(c, rootLabel, 9f, y, 1, if (rootChecked) P.DIM else P.GREEN)
        lastRootRect = Box(8f, y - 1, textWidth(rootLabel, 1) + 2, 8f)

        val svcLabel = if (running) "[ STOP SERVICE ]" else "[ START SERVICE ]"
        val svx = W - 8 - textWidth(svcLabel, 1)
        drawBox(c, svx, y - 1, textWidth(svcLabel, 1) + 2, 8f, P.BG2, P.BORDER)
        drawText(c, svcLabel, svx + 1, y, 1, if (running) P.RED else P.GREEN)
        lastServiceRect = Box(svx, y - 1, textWidth(svcLabel, 1) + 2, 8f)
        y += 14

        drawText(c, "USAGE", 8f, y, 1, P.DIM); y += 9
        drawText(c, "termux scripts", 8f, y, 1, P.TXT); y += 9
        drawText(c, "> ln -s script", 8f, y, 1, P.OFF); y += 9
        drawText(c, "> ~/.termux/tasker/", 8f, y, 1, P.OFF); y += 9
        drawText(c, "shell trigger:", 8f, y, 1, P.TXT); y += 9
        drawText(c, "> am broadcast -a", 8f, y, 1, P.OFF); y += 9
        drawText(c, "> com.eventsh.SHELL_EVENT", 8f, y, 1, P.OFF); y += 9
        drawText(c, "timer:", 8f, y, 1, P.TXT); y += 9
        drawText(c, "> am broadcast -a", 8f, y, 1, P.OFF); y += 9
        drawText(c, "> com.eventsh.SET_TIMER", 8f, y, 1, P.OFF); y += 9
        drawText(c, "> --es at 1730000000", 8f, y, 1, P.OFF); y += 9
        drawText(c, "> --es daily 07:30", 8f, y, 1, P.OFF); y += 9
        drawText(c, "cancel:", 8f, y, 1, P.TXT); y += 9
        drawText(c, "> am broadcast -a", 8f, y, 1, P.OFF); y += 9
        drawText(c, "> com.eventsh.CANCEL_TIMER", 8f, y, 1, P.OFF); y += 9
        drawText(c, "> --es id <timerid>", 8f, y, 1, P.OFF); y += 9
    }

    private var lastServiceRect: Box? = null
    private var lastRootRect: Box? = null
    private data class Box(val x: Float, val y: Float, val w: Float, val h: Float)

    // ------------------------------------------------------------- VAR
    private fun drawVar(c: Canvas, u: Float, W: Float, H: Float) {
        statusBar(c, u, W)
        val tw = textWidth("USER VARS", 1)
        drawText(c, "USER VARS", (W - tw) / 2, 18f, 1, P.GREEN)
        paint.color = P.DIMROW
        c.drawRect(4 * pp, 28 * pp, (W - 4) * pp, 29 * pp, paint)
        drawText(c, "lower=RAM upper=DSK", (W - textWidth("lower=RAM upper=DSK", 1)) / 2, 33f, 1, P.TXT)

        val navTop = H - 26f
        val listTop = 44f
        val listH = navTop - listTop
        val rowHv = 9f
        val totalH = userVars.size * rowHv
        val maxScroll = (totalH - listH).coerceAtLeast(0f)
        varScroll = varScroll.coerceIn(0f, maxScroll)

        val save = c.save()
        c.clipRect(0f, listTop * pp, width.toFloat(), navTop * pp)
        var i = 0
        for (v in userVars) {
            val y = listTop + i * rowHv - varScroll
            if (y > navTop) break
            if (y + rowHv < listTop) { i++; continue }
            val color = if (v.disk) P.AMBER else P.GREEN
            drawText(c, v.name, 6f, y, 1, color)
            drawText(c, "=", 6f + textWidth(v.name, 1) + 2, y, 1, P.OFF)
            var vx = 6f + textWidth(v.name, 1) + 7
            val maxVal = W - 6 - textWidth(if (v.disk) "DSK" else "RAM", 1) - 4
            val show = v.value
            if (vx + textWidth(show, 1) > maxVal) {
                var clipped = show
                while (clipped.isNotEmpty() && vx + textWidth(clipped, 1) > maxVal) clipped = clipped.dropLast(1)
                drawText(c, clipped, vx, y, 1, P.TXT)
            } else {
                drawText(c, show, vx, y, 1, P.TXT)
            }
            drawText(c, if (v.disk) "DSK" else "RAM", W - 4 - textWidth(if (v.disk) "DSK" else "RAM", 1), y, 1, color)
            i++
        }
        if (userVars.isEmpty()) drawText(c, "no user vars", (W - textWidth("no user vars", 1)) / 2, listTop + 8, 1, P.OFF)
        c.restoreToCount(save)

        val addLabel = "[ + ADD VAR ]"
        val aw = textWidth(addLabel, 1) + 2
        drawBox(c, W - 8 - aw, navTop + 2, aw, 8f, P.BG2, P.BORDER)
        drawText(c, addLabel, W - 7 - textWidth(addLabel, 1), navTop + 4, 1, P.GREEN)
        lastAddRect = Box(W - 8 - aw, navTop + 2, aw, 8f)
        varRowsStart = listTop
    }

    private var lastAddRect: Box? = null
    private var varRowsStart = 0f

    // ------------------------------------------------------------- NAV
    private fun drawNav(c: Canvas, u: Float, W: Float, H: Float) {
        val ny = H - 13f
        paint.color = P.DIMROW
        c.drawRect(0f, (ny - 2) * pp, W * pp, (ny - 1) * pp, paint)
        val labels = listOf("RLS", "LOG", "VAR", "CFG")
        val gap = 2f
        var total = 0f
        for (l in labels) total += textWidth(l, 1)
        total += gap * (labels.size - 1)
        navZones.clear()
        var x = (W - total) / 2
        for (i in labels.indices) {
            navZones.add(labels[i] to x)
            drawText(c, labels[i], x, ny, 1, if (screen == i) P.GREEN else P.OFF)
            x += textWidth(labels[i], 1) + gap
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_DOWN) return true
        val u = pp.toFloat()
        val W = width / u
        val H = height / u
        val x = event.x / u
        val y = event.y / u

        val navTop = H - 16f
        if (y > navTop) {
            for (i in navZones.indices) {
                val (label, zx) = navZones[i]
                if (x >= zx && x <= zx + textWidth(label, 1)) {
                    onNav?.invoke(i)
                    return true
                }
            }
            return true
        }

        when (screen) {
            0 -> {
                if (y < 63f) return true
                val ar = lastAddRuleRect
                if (ar != null && x >= ar.x && x <= ar.x + ar.w && y >= ar.y && y <= ar.y + ar.h) {
                    onAddRule?.invoke()
                    return true
                }
                val at = lastAddTimerRect
                if (at != null && x >= at.x && x <= at.x + at.w && y >= at.y && y <= at.y + at.h) {
                    onAddTimer?.invoke()
                    return true
                }
                if (y > navTop - 10f) return true
                val idx = ((y - 63f + scrollY) / rowH).toInt()
                if (idx in rules.indices) {
                    val eb = rowEditRects.getOrNull(idx)
                    if (eb != null && x >= eb.x && x <= eb.x + eb.w && y >= eb.y && y <= eb.y + eb.h) {
                        onEditRule?.invoke(rules[idx])
                    } else {
                        onToggleRule?.invoke(rules[idx])
                    }
                }
            }
            2 -> {
                val ar = lastAddRect
                if (ar != null && x >= ar.x && x <= ar.x + ar.w && y >= ar.y && y <= ar.y + ar.h) {
                    onAddVar?.invoke()
                    return true
                }
                if (y > varRowsStart && y < navTop) {
                    val idx = ((y - varRowsStart + varScroll) / 9f).toInt()
                    if (idx in userVars.indices) onEditVar?.invoke(userVars[idx])
                }
            }
            3 -> {
                val r = lastServiceRect
                if (r != null && x >= r.x && x <= r.x + r.w && y >= r.y && y <= r.y + r.h) {
                    onServiceToggle?.invoke()
                    return true
                }
                val rr = lastRootRect
                if (rr != null && x >= rr.x && x <= rr.x + rr.w && y >= rr.y && y <= rr.y + rr.h) {
                    onRootCheck?.invoke()
                }
            }
        }
        return true
    }
}
