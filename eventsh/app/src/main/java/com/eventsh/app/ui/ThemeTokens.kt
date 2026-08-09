package com.eventsh.app.ui

/**
 * Central UI tokens for the whole Maniflow app.
 *
 * Every screen reads colors / radii / spacing from [Theme.current], so a future
 * runtime-loaded theme only has to swap these values once and the whole app
 * restyles without touching a single screen.
 */
data class ThemeTokens(
    val headerBg: Int = 0xFF04342C.toInt(),
    val headerText: Int = 0xFFFFFFFF.toInt(),
    val accentPrimary: Int = 0xFF1D9E75.toInt(),
    val surfaceBg: Int = 0xFFFFFFFF.toInt(),
    val cardBg: Int = 0xFFFFFFFF.toInt(),
    val borderColor: Int = 0xFFE5E7EB.toInt(),
    val textPrimary: Int = 0xFF101828.toInt(),
    val textMuted: Int = 0xFF667085.toInt(),
    val danger: Int = 0xFFFF4D5E.toInt(),
    val ok: Int = 0xFF2ECC71.toInt(),
    val disabled: Int = 0xFF98A2B3.toInt(),
    val primarySoft: Int = 0xFFE3F5EE.toInt(),
    val toggleOffBg: Int = 0xFFD0D5DD.toInt(),
    val radiusHeader: Int = 28,
    val radiusCard: Int = 16,
    val radiusBadge: Int = 9,
    val radiusToggle: Int = 10,
    val shadowElevation: Int = 4,
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
