package com.eventsh.app.theme

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.eventsh.app.ui.ThemeTokens
import org.json.JSONArray
import org.json.JSONObject

/** One saved theme in the recent-themes history. */
data class ThemeHistoryEntry(val tokens: ThemeTokens, val at: Long)

/**
 * JSON codec for [ThemeTokens]. Uses the exact same schema as the Gemini
 * SYSTEM_PROMPT (hex colors, numeric radii + a fontFamily string), so a stored
 * theme can be round-tripped through [ThemeValidator.validate] safely.
 */
object ThemeCodec {
    fun toJson(t: ThemeTokens): JSONObject = JSONObject().apply {
        put("headerBg", hex(t.headerBg))
        put("headerText", hex(t.headerText))
        put("accentPrimary", hex(t.accentPrimary))
        put("surfaceBg", hex(t.surfaceBg))
        put("cardBg", hex(t.cardBg))
        put("borderColor", hex(t.borderColor))
        put("textPrimary", hex(t.textPrimary))
        put("textMuted", hex(t.textMuted))
        put("toggleOffBg", hex(t.toggleOffBg))
        put("radiusHeader", t.radiusHeader)
        put("radiusCard", t.radiusCard)
        put("radiusBadge", t.radiusBadge)
        put("radiusToggle", t.radiusToggle)
        put("shadowElevation", t.shadowElevation)
        put("fontFamily", t.fontFamily)
        put("spacingUnit", t.spacingUnit)
    }

    /** Rebuilds tokens from a stored JSON; null on any corruption. */
    fun fromJson(raw: String): ThemeTokens? = try {
        ThemeValidator.validate(raw, ThemeTokens())
    } catch (e: Exception) {
        null
    }

    private fun hex(c: Int): String = String.format("#%06X", c and 0xFFFFFF)
}

/**
 * Secure persistence for the Gemini API key and the runtime theme state.
 *
 * Everything lives in an [EncryptedSharedPreferences] file - the API key is
 * sensitive and must never sit in a plain SharedPreferences.
 */
object ThemeStore {
    private const val PREFS = "maniflow_secure"
    private const val KEY_API_KEY = "gemini_api_key"
    private const val KEY_APPLIED = "applied_theme"
    private const val KEY_HISTORY = "theme_history"

    // Best-effort prefs handle: a keystore failure must never crash the app,
    // so every accessor treats a null handle as "empty / no-op".
    private fun prefs(ctx: Context): SharedPreferences? = try {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        EncryptedSharedPreferences.create(
            ctx,
            PREFS,
            masterKeyAlias,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        null
    }

    // ------------------------------------------------------------ api key
    fun apiKey(ctx: Context): String? = try {
        prefs(ctx)?.getString(KEY_API_KEY, null)?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        null
    }

    fun hasApiKey(ctx: Context): Boolean = !apiKey(ctx).isNullOrBlank()

    fun saveApiKey(ctx: Context, key: String) {
        try {
            prefs(ctx)?.edit()?.putString(KEY_API_KEY, key.trim())?.apply()
        } catch (e: Exception) {
        }
    }

    // ------------------------------------------------------------ applied theme
    fun appliedJson(ctx: Context): String? = try {
        prefs(ctx)?.getString(KEY_APPLIED, null)
    } catch (e: Exception) {
        null
    }

    fun saveApplied(ctx: Context, tokens: ThemeTokens) {
        try {
            prefs(ctx)?.edit()?.putString(KEY_APPLIED, ThemeCodec.toJson(tokens).toString())?.apply()
        } catch (e: Exception) {
        }
    }

    fun clearApplied(ctx: Context) {
        try {
            prefs(ctx)?.edit()?.remove(KEY_APPLIED)?.apply()
        } catch (e: Exception) {
        }
    }

    // ------------------------------------------------------------ history
    fun history(ctx: Context): List<ThemeHistoryEntry> = try {
        val p = prefs(ctx) ?: return emptyList()
        val raw = p.getString(KEY_HISTORY, null) ?: return emptyList()
        val arr = JSONArray(raw)
        val out = ArrayList<ThemeHistoryEntry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val tokens = ThemeCodec.fromJson(o.optString("tokens", ""))
            if (tokens != null) out.add(ThemeHistoryEntry(tokens, o.optLong("at", 0L)))
        }
        out
    } catch (e: Exception) {
        emptyList()
    }

    fun saveHistory(ctx: Context, entries: List<ThemeHistoryEntry>) {
        try {
            val arr = JSONArray()
            entries.forEach {
                arr.put(JSONObject().apply {
                    put("tokens", ThemeCodec.toJson(it.tokens).toString())
                    put("at", it.at)
                })
            }
            prefs(ctx)?.edit()?.putString(KEY_HISTORY, arr.toString())?.apply()
        } catch (e: Exception) {
        }
    }
}
