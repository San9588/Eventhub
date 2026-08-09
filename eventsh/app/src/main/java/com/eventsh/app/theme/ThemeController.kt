package com.eventsh.app.theme

import android.content.Context
import com.eventsh.app.ui.Theme
import com.eventsh.app.ui.ThemeTokens

/**
 * Orchestrates runtime theme changes: applies tokens to [Theme], persists them
 * securely, keeps the last-5 history and bumps a generation counter so any open
 * screen knows it has to rebuild. Also owns the emergency-reset broadcast that
 * the Quick Settings tile fires.
 */
object ThemeController {

    const val ACTION_THEME_RESET = "com.eventsh.app.action.THEME_RESET"
    const val MAX_HISTORY = 5

    /** Bumped on every apply / undo / reset; screens compare this to rebuild. */
    @Volatile
    var generation = 0

    /** Tokens that were in effect before the last [apply] - used by UNDO. */
    @Volatile
    var previous: ThemeTokens? = null

    /** Loads the persisted theme (if any) so a restart keeps the same look. */
    fun restoreFromDisk(ctx: Context) {
        ThemeStore.appliedJson(ctx)?.let { raw ->
            ThemeCodec.fromJson(raw)?.let { Theme.apply(it) }
        }
    }

    /** Applies [tokens] after validation passed, persists it + records history. */
    fun apply(ctx: Context, tokens: ThemeTokens) {
        previous = Theme.current
        Theme.apply(tokens)
        ThemeStore.saveApplied(ctx, tokens)
        addHistory(ctx, tokens)
        generation++
    }

    /** Restores the tokens that were active before the last [apply]. */
    fun undo(ctx: Context) {
        val prev = previous ?: return
        previous = Theme.current
        Theme.apply(prev)
        ThemeStore.saveApplied(ctx, prev)
        addHistory(ctx, prev)
        generation++
    }

    /** Always returns to the original hardcoded defaults, however deep the chain. */
    fun resetToDefault(ctx: Context) {
        Theme.apply(ThemeTokens())
        ThemeStore.clearApplied(ctx)
        previous = null
        generation++
    }

    private fun addHistory(ctx: Context, tokens: ThemeTokens) {
        val list = ThemeStore.history(ctx).toMutableList()
        list.removeAll { it.tokens == tokens }
        list.add(0, ThemeHistoryEntry(tokens, System.currentTimeMillis()))
        while (list.size > MAX_HISTORY) list.removeAt(list.size - 1)
        ThemeStore.saveHistory(ctx, list)
    }
}
