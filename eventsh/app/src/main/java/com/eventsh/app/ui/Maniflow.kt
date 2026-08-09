package com.eventsh.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.eventsh.app.R

/** Apply an alpha channel (0-255) to an ARGB color int. */
fun Int.withAlpha(alpha: Int): Int = (this and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

/**
 * Reusable Maniflow UI components. Every screen builds its chrome from these,
 * so the whole app stays visually consistent. Colors / radii / spacing come
 * from [Theme.current] - never hardcode styling in a screen.
 */
object Maniflow {

    fun dp(c: Context, v: Int): Int = (v * c.resources.displayMetrics.density).toInt()
    fun dpf(c: Context, v: Float): Int = maxOf(1, (v * c.resources.displayMetrics.density).toInt())

    fun rounded(
        c: Context,
        color: Int,
        radiusDp: Int,
        borderColor: Int? = null,
        borderDp: Float = 1f,
        radii: FloatArray? = null
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        if (radii != null) setCornerRadii(radii) else cornerRadius = radiusDp.toFloat()
        if (borderColor != null) setStroke(dpf(c, borderDp), borderColor)
    }

    fun text(
        c: Context,
        label: String,
        sizeSp: Float,
        color: Int,
        bold: Boolean = false
    ): TextView = TextView(c).apply {
        text = label
        textSize = sizeSp
        setTextColor(color)
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    /**
     * Curved dark page header.
     * [status] is the small muted line, [headline] the large bold line below it.
     */
    fun header(
        c: Context,
        title: String,
        status: String? = null,
        headline: String? = null,
        onBack: (() -> Unit)? = null,
        actionIcon: Int? = null,
        actionContentDescription: String? = null,
        onAction: (() -> Unit)? = null
    ): View {
        val t = Theme.current
        val bottom = dp(c, t.radiusHeader).toFloat()
        val wrap = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(t.headerBg)
            background = rounded(
                c, t.headerBg, 0,
                radii = floatArrayOf(0f, 0f, 0f, 0f, bottom * 0.55f, bottom * 0.55f, bottom, bottom)
            )
            setPadding(dp(c, t.spacingUnit), dp(c, 14), dp(c, t.spacingUnit), dp(c, 20))
        }

        val titleRow = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        if (onBack != null) {
            val back = ImageView(c).apply {
                setImageResource(R.drawable.ic_back)
                setColorFilter(t.headerText)
                setPadding(dp(c, 6), dp(c, 6), dp(c, 6), dp(c, 6))
                contentDescription = "Back"
                setOnClickListener { onBack() }
            }
            titleRow.addView(back, LinearLayout.LayoutParams(dp(c, 36), dp(c, 36)))
        }
        titleRow.addView(
            text(c, title, 22f, t.headerText, bold = true).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = if (onBack != null) dp(c, 6) else 0
            }
        )
        if (actionIcon != null) {
            val btn = ImageView(c).apply {
                setImageResource(actionIcon)
                setColorFilter(t.headerText)
                setPadding(dp(c, 10), dp(c, 10), dp(c, 10), dp(c, 10))
                background = rounded(c, t.headerText.withAlpha(38), 999)
                contentDescription = actionContentDescription
                setOnClickListener { onAction?.invoke() }
            }
            titleRow.addView(btn, LinearLayout.LayoutParams(dp(c, 42), dp(c, 42)))
        }
        wrap.addView(titleRow)

        if (status != null) {
            val tv = text(c, status, 13f, t.headerText.withAlpha(153))
            tv.tag = "maniflow.header.status"
            wrap.addView(
                tv,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(c, 12)
                }
            )
        }
        if (headline != null) {
            val tv = text(c, headline, 20f, t.headerText, bold = true).apply {
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            tv.tag = "maniflow.header.headline"
            wrap.addView(
                tv,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(c, 4)
                }
            )
        }
        return wrap
    }

    /** White rounded elevated card used for settings groups, detail blocks, forms. */
    fun card(c: Context, content: View): View {
        val t = Theme.current
        return LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(c, t.spacingUnit), dp(c, t.spacingUnit), dp(c, t.spacingUnit), dp(c, t.spacingUnit))
            background = rounded(c, t.cardBg, t.radiusCard)
            elevation = dpf(c, t.shadowElevation.toFloat())
            addView(content)
        }
    }

    /** Custom switch: green ON track / gray outlined OFF track. */
    fun toggle(c: Context, checked: Boolean, onToggle: (Boolean) -> Unit): ManiflowToggle =
        ManiflowToggle(c, checked, onToggle)

    /** Hairline divider in the theme border color. */
    fun divider(c: Context): View = View(c).apply {
        setBackgroundColor(Theme.current.borderColor)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpf(c, 0.5f))
    }

    /** Uppercase muted section heading. */
    fun sectionLabel(c: Context, text: String, topMargin: Int = 0): TextView =
        text(c, text.uppercase(java.util.Locale.US), 12f, Theme.current.textMuted, bold = true).apply {
            letterSpacing = 0.1f
            setPadding(dp(c, 2), 0, dp(c, 2), dp(c, 6))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(c, topMargin)
            }
        }

    /**
     * Rounded-square icon badge. [dimmed] tints the icon + badge muted.
     */
    fun badge(c: Context, icon: Int, tint: Int, sizeDp: Int = 40, dimmed: Boolean = false): View {
        val t = Theme.current
        val iconColor = if (dimmed) t.textMuted else tint
        val bgColor = if (dimmed) t.textMuted.withAlpha(12) else tint.withAlpha(22)
        val s = dp(c, sizeDp)
        return FrameLayout(c).apply {
            background = rounded(c, bgColor, t.radiusBadge)
            val iv = ImageView(c).apply {
                setImageResource(icon)
                setColorFilter(iconColor)
            }
            val inner = sizeDp - 20
            addView(iv, FrameLayout.LayoutParams(dp(c, inner), dp(c, inner), Gravity.CENTER))
            layoutParams = LinearLayout.LayoutParams(s, s)
        }
    }

    /**
     * Icon-badge + title + optional trailing view, with a hairline divider at
     * the bottom (unless [showDivider] is false).
     */
    fun listRow(
        c: Context,
        icon: Int,
        tint: Int,
        title: String,
        subtitle: String? = null,
        trailing: View? = null,
        onClick: (() -> Unit)? = null,
        showDivider: Boolean = true,
        dimmed: Boolean = false
    ): View {
        val t = Theme.current
        val row = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(c, 2), dp(c, 10), dp(c, 2), dp(c, 10))
            if (onClick != null) {
                isClickable = true
                isFocusable = true
                setOnClickListener { onClick() }
            }
        }
        row.addView(badge(c, icon, tint, dimmed = dimmed))

        val titleColor = if (dimmed) t.textMuted else t.textPrimary
        val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        col.addView(
            text(c, title, 16f, titleColor, bold = true).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
        )
        if (subtitle != null) {
            col.addView(
                text(c, subtitle, 12f, t.textMuted).apply {
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
            )
        }
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(c, 12)
        })
        if (trailing != null) {
            row.addView(trailing, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(c, 8)
            })
        }

        val wrap = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        wrap.addView(row)
        if (showDivider) wrap.addView(divider(c))
        return wrap
    }

    /** Rounded button: filled [primary] style or outlined secondary style. */
    fun button(c: Context, label: String, primary: Boolean, onClick: () -> Unit): TextView {
        val t = Theme.current
        return TextView(c).apply {
            text = label
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(if (primary) 0xFFFFFFFF.toInt() else t.textPrimary)
            setPadding(dp(c, 14), dp(c, 10), dp(c, 14), dp(c, 10))
            background = rounded(
                c,
                if (primary) t.accentPrimary else t.surfaceBg,
                t.radiusCard,
                borderColor = if (primary) null else t.borderColor,
                borderDp = 1f
            )
            elevation = if (primary) dpf(c, (t.shadowElevation / 2).toFloat()) else 0f
            setOnClickListener { onClick() }
        }
    }

    /** Small stat tile: tinted icon + bold value + muted label. */
    fun statCard(
        c: Context,
        icon: Int,
        tint: Int,
        value: String,
        label: String,
        valueTag: String? = null
    ): View {
        val t = Theme.current
        return LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(c, 8), dp(c, 12), dp(c, 8), dp(c, 12))
            background = rounded(c, t.cardBg, t.radiusCard)
            elevation = dpf(c, (t.shadowElevation / 2).toFloat())
            addView(ImageView(c).apply {
                setImageResource(icon)
                setColorFilter(tint)
            }, LinearLayout.LayoutParams(dp(c, 20), dp(c, 20)))
            addView(
                text(c, value, 18f, t.textPrimary, bold = true).apply {
                    gravity = Gravity.CENTER
                    if (valueTag != null) tag = valueTag
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(c, 6)
                }
            )
            addView(
                text(c, label, 11f, t.textMuted).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
        }
    }
}

/**
 * Custom animated switch used for every on/off control.
 * Green track + white thumb when ON, outlined gray track + gray thumb when OFF.
 */
class ManiflowToggle(
    c: Context,
    initial: Boolean = false,
    private val onToggle: ((Boolean) -> Unit)? = null
) : FrameLayout(c) {

    private val trackView = View(c)
    private val thumbView = View(c)
    private var animator: ValueAnimator? = null
    private var checked = initial

    init {
        val w = Maniflow.dp(c, 48)
        val h = Maniflow.dp(c, 28)
        trackView.layoutParams = FrameLayout.LayoutParams(w, h, Gravity.CENTER)
        trackView.elevation = Maniflow.dpf(c, 1f)
        thumbView.layoutParams = FrameLayout.LayoutParams(
            Maniflow.dp(c, 22),
            Maniflow.dp(c, 22),
            Gravity.CENTER_VERTICAL
        )
        thumbView.elevation = Maniflow.dpf(c, 2f)
        addView(trackView)
        addView(thumbView)
        isClickable = true
        isFocusable = true
        setOnClickListener {
            val next = !checked
            setChecked(next, animate = true)
            onToggle?.invoke(next)
        }
        applyChecked(animate = false)
    }

    fun isChecked(): Boolean = checked

    fun setChecked(value: Boolean, animate: Boolean = true) {
        checked = value
        applyChecked(animate)
    }

    private fun applyChecked(animate: Boolean) {
        val t = Theme.current
        val targetX = Maniflow.dp(context, if (checked) 22 else 4).toFloat()
        if (animate) {
            animator?.cancel()
            animator = ValueAnimator.ofFloat(thumbView.translationX, targetX).apply {
                duration = 180
                addUpdateListener { thumbView.translationX = it.animatedValue as Float }
                start()
            }
        } else {
            thumbView.translationX = targetX
        }
        trackView.background = Maniflow.rounded(
            context,
            if (checked) t.accentPrimary else t.surfaceBg,
            t.radiusToggle,
            borderColor = if (checked) null else t.toggleOffBg,
            borderDp = 1f
        )
        thumbView.background = Maniflow.rounded(
            context,
            if (checked) 0xFFFFFFFF.toInt() else 0xFF98A2B3.toInt(),
            999
        )
    }
}
