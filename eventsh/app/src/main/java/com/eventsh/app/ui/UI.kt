package com.eventsh.app.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Small programmatic view helpers for the Material UI.
 * Keeps MainActivity focused on wiring + editors.
 */
object UI {
    fun dp(c: Context, v: Float): Int =
        (v * c.resources.displayMetrics.density).toInt()

    /** Ripple-free rounded drawable (used for chips / cards). */
    fun rounded(color: Int, radiusDp: Float, borderColor: Int? = null, borderDp: Float = 1f): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = radiusDp
            if (borderColor != null) setStroke((borderDp * 2).toInt(), borderColor)
        }

    fun text(
        context: Context,
        label: String,
        sizeSp: Float = 14f,
        color: Int,
        bold: Boolean = false
    ): TextView = TextView(context).apply {
        text = label
        textSize = sizeSp
        setTextColor(color)
        typeface = android.graphics.Typeface.DEFAULT_BOLD.takeIf { bold } ?: android.graphics.Typeface.DEFAULT
    }

    /** Spacer view that stretches inside a linear layout. */
    fun spacer(context: Context): View = View(context)

    fun vsep(context: Context, h: Int): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h)
    }

    fun hsep(context: Context, w: Int): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    fun marginLp(
        w: Int, h: Int, l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0
    ): FrameLayout.LayoutParams = FrameLayout.LayoutParams(w, h).apply {
        setMargins(l, t, r, b)
    }
}
