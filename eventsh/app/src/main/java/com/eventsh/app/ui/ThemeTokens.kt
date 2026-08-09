package com.eventsh.app.ui

/**
 * Central UI tokens for the whole Maniflow app.
 *
 * Every screen reads colors / radii / spacing from [Theme.current], so a future
 * runtime-loaded theme only has to swap these values once and the whole app
 * restyles without touching a single screen.
 */
data class ThemeTokens(
    val headerBg: Int = 0xFF003D35.toInt(),
    val headerText: Int = 0xFFF5F5F3.toInt(),
    val accentPrimary: Int = 0xFF00B686.toInt(),
    val surfaceBg: Int = 0xFF292A28.toInt(),
    val cardBg: Int = 0xFF292A28.toInt(),
    val borderColor: Int = 0xFF3B3C39.toInt(),
    val textPrimary: Int = 0xFFF3F2EF.toInt(),
    val textMuted: Int = 0xFF999894.toInt(),
    val danger: Int = 0xFFFF5C5C.toInt(),
    val ok: Int = 0xFF00B686.toInt(),
    val disabled: Int = 0xFF777773.toInt(),
    val primarySoft: Int = 0xFF164C42.toInt(),
    val toggleOffBg: Int = 0xFF676864.toInt(),
    val radiusHeader: Int = 44,
    val radiusCard: Int = 24,
    val radiusBadge: Int = 14,
    val radiusToggle: Int = 18,
    val shadowElevation: Int = 0,
    val fontFamily: String = "Default",
    val spacingUnit: Int = 16,
    val statOrange: Int = 0xFFF97316.toInt(),
    val statPink: Int = 0xFFEC4899.toInt(),
    val statGreen: Int = 0xFF22C55E.toInt(),
    val flowTintOrange: Int = 0xFFFFB020.toInt(),
    val flowTintPink: Int = 0xFFFF6B8A.toInt(),
    val flowTintGreen: Int = 0xFF2EC274.toInt(),
    val flowTintBlue: Int = 0xFF3B9DFF.toInt(),
    val flowTintPurple: Int = 0xFFA78BFA.toInt()
)

/**
 * Mutable holder for the current token set. Screens read [Theme.current] at
 * build time; a runtime theme loader can call [Theme.apply] to restyle the app.
 */
object Theme {
    @Volatile
    var current: ThemeTokens = ThemeTokens()
        private set

    fun apply(tokens: ThemeTokens) {
        current = tokens
    }
}
