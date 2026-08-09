package com.eventsh.app

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.eventsh.app.theme.ThemeController
import com.eventsh.app.theme.ThemeStore

/**
 * "Reset Maniflow Theme" Quick Settings tile - the emergency exit. If an AI
 * theme ever makes the app text unreadable, the user can tap this tile from
 * the panel (without opening the app) to force the default theme back and
 * broadcast an intent so an open app instance recomposes immediately.
 */
class ThemeResetTileService : TileService() {

    override fun onStartListening() {
        updateTile()
    }

    override fun onStopListening() {}

    override fun onClick() {
        ThemeController.resetToDefault(this)
        sendBroadcast(Intent(ThemeController.ACTION_THEME_RESET))
        updateTile()
    }

    private fun updateTile() {
        try {
            val tile = qsTile ?: return
            val custom = ThemeStore.appliedJson(this) != null
            tile.label = "Reset Maniflow Theme"
            if (Build.VERSION.SDK_INT >= 29) {
                tile.subtitle = if (custom) "Theme: custom" else "Theme: default"
            }
            tile.state = if (custom) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            tile.updateTile()
        } catch (e: Exception) {
            // Tile state is best-effort; never crash the quick-settings panel.
        }
    }
}
