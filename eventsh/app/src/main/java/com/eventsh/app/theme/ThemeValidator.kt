package com.eventsh.app.theme

import com.eventsh.app.ui.ThemeTokens
import org.json.JSONObject

/**
 * Safety gate for Gemini-generated themes. A theme is only applied after this
 * validator passes; any broken field falls back to the previous theme instead
 * of being applied, so no invalid response can ever make text unreadable.
 */
object ThemeValidator {

    private val HEX = Regex("^#[0-9A-Fa-f]{6}$")
    private val FONTS = setOf("Default", "Serif", "Monospace")
    private const val MIN_CONTRAST = 4.5

    /**
     * Validates [rawJson] against [current] (the pre-apply theme) and returns a
     * safe token set. Throws on unparseable JSON - callers must not apply.
     */
    fun validate(rawJson: String, current: ThemeTokens): ThemeTokens {
        val cleaned = rawJson
            .trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val obj = JSONObject(cleaned)

        val tokens = ThemeTokens(
            headerBg = color(obj, "headerBg", current.headerBg),
            headerText = color(obj, "headerText", current.headerText),
            accentPrimary = color(obj, "accentPrimary", current.accentPrimary),
            surfaceBg = color(obj, "surfaceBg", current.surfaceBg),
            cardBg = color(obj, "cardBg", current.cardBg),
            borderColor = color(obj, "borderColor", current.borderColor),
            textPrimary = color(obj, "textPrimary", current.textPrimary),
            textMuted = color(obj, "textMuted", current.textMuted),
            toggleOffBg = color(obj, "toggleOffBg", current.toggleOffBg),
            radiusHeader = num(obj, "radiusHeader", current.radiusHeader, 0, 50),
            radiusCard = num(obj, "radiusCard", current.radiusCard, 8, 24),
            radiusBadge = num(obj, "radiusBadge", current.radiusBadge, 4, 16),
            radiusToggle = num(obj, "radiusToggle", current.radiusToggle, 6, 10),
            shadowElevation = num(obj, "shadowElevation", current.shadowElevation, 0, 12),
            fontFamily = font(obj, current.fontFamily),
            spacingUnit = num(obj, "spacingUnit", current.spacingUnit, 8, 24),
            danger = current.danger,
            ok = current.ok,
            disabled = current.disabled,
            primarySoft = current.primarySoft,
            statOrange = current.statOrange,
            statPink = current.statPink,
            statGreen = current.statGreen,
            flowTintOrange = current.flowTintOrange,
            flowTintPink = current.flowTintPink,
            flowTintGreen = current.flowTintGreen,
            flowTintBlue = current.flowTintBlue,
            flowTintPurple = current.flowTintPurple
        )
        return enforceContrast(tokens, current)
    }

    private fun color(obj: JSONObject, key: String, fallback: Int): Int {
        val v = obj.optString(key).trim()
        return if (HEX.matches(v)) parseHex(v) else fallback
    }

    private fun parseHex(v: String): Int = (v.substring(1).toLong(16) or 0xFF000000L).toInt()

    /** Numeric tokens are always clamped into their legal range. */
    private fun num(obj: JSONObject, key: String, fallback: Int, min: Int, max: Int): Int {
        if (!obj.has(key)) return fallback
        val v = obj.optInt(key, fallback)
        return v.coerceIn(min, max)
    }

    private fun font(obj: JSONObject, fallback: String): String {
        val v = obj.optString("fontFamily").trim()
        return if (v in FONTS) v else fallback
    }

    /** WCAG 2.x contrast ratio between two ARGB colors (always >= 1). */
    fun contrast(a: Int, b: Int): Double {
        val l1 = luminance(a)
        val l2 = luminance(b)
        val hi = maxOf(l1, l2)
        val lo = minOf(l1, l2)
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun luminance(c: Int): Double {
        fun ch(v: Int): Double {
            val s = v / 255.0
            return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
        }
        val r = ch((c shr 16) and 0xFF)
        val g = ch((c shr 8) and 0xFF)
        val b = ch(c and 0xFF)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    /**
     * Guarantees the critical fg/bg pairs stay readable (WCAG AA, 4.5:1).
     * A pair that fails falls back to the previous theme's values - never the
     * broken new ones.
     */
    private fun enforceContrast(t: ThemeTokens, prev: ThemeTokens): ThemeTokens {
        val header = ensurePair(t.headerBg, prev.headerBg, t.headerText, prev.headerText)
        val accent = ensurePair(t.cardBg, prev.cardBg, t.accentPrimary, prev.accentPrimary)
        val body = ensurePair(t.surfaceBg, prev.surfaceBg, t.textPrimary, prev.textPrimary)
        val muted = ensurePair(t.cardBg, prev.cardBg, t.textMuted, prev.textMuted)
        return t.copy(
            headerBg = header.first,
            headerText = header.second,
            cardBg = accent.first,
            accentPrimary = accent.second,
            surfaceBg = body.first,
            textPrimary = body.second,
            textMuted = muted.second
        )
    }

    private fun ensurePair(bg: Int, prevBg: Int, fg: Int, prevFg: Int): Pair<Int, Int> {
        if (contrast(bg, fg) >= MIN_CONTRAST) return bg to fg
        if (contrast(bg, prevFg) >= MIN_CONTRAST) return bg to prevFg
        if (contrast(prevBg, fg) >= MIN_CONTRAST) return prevBg to fg
        return prevBg to prevFg
    }
}
