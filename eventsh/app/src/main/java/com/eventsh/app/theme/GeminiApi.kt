package com.eventsh.app.theme

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Gemini (`generativelanguage.googleapis.com`) theme generation client.
 *
 * Runs on the caller's worker thread (never the UI thread). The API key is sent
 * in the `x-goog-api-key` header and is never logged or exposed in errors.
 */
object GeminiApi {

    private const val MODEL = "gemini-2.0-flash"
    private const val BASE = "https://generativelanguage.googleapis.com/v1beta"

    /**
     * Hardcoded system prompt. Do not move this text anywhere else - the Gemini
     * call depends on it staying in exactly this shape.
     */
    val SYSTEM_PROMPT = """
        Tum ek theme-generator ho "Maniflow" app ke liye. User natural language
        mein theme describe karega, tumhe SIRF neeche diye tokens ke JSON
        format mein values return karni hain — koi extra text ya explanation
        nahi, koi markdown-fence nahi.

        AVAILABLE TOKENS:
        "headerBg": "hex color"
        "headerText": "hex color"
        "accentPrimary": "hex color"
        "surfaceBg": "hex color"
        "cardBg": "hex color"
        "borderColor": "hex color"
        "textPrimary": "hex color"
        "textMuted": "hex color"
        "toggleOffBg": "hex color"
        "radiusHeader": "number 0-50"
        "radiusCard": "number 8-24"
        "radiusBadge": "number 4-16"
        "radiusToggle": "number 6-10"
        "shadowElevation": "number 0-12"
        "fontFamily": "Default | Serif | Monospace"
        "spacingUnit": "number 8-24"

        RULES:
        - headerText aur headerBg mein hamesha readable contrast (WCAG AA)
        - accentPrimary aur cardBg mein bhi contrast maintain karo
        - Agar user sirf ek-do cheez bole, baaki tokens logically-consistent
          family ke colors se bharo, random mat chuno
        - Output SIRF valid JSON, koi aur text nahi

        Output format:
        { "headerBg": "...", "headerText": "...", ...sabhi tokens... }
    """.trimIndent()

    /**
     * Asks the model for a theme and returns the raw JSON text of the theme
     * object (the token JSON, not the full Gemini envelope).
     *
     * @throws Exception on any network / HTTP / parse failure.
     */
    @Throws(Exception::class)
    fun generateTheme(apiKey: String, userPrompt: String): String {
        // Key travels in a header (never in the URL) so it can't leak into logs
        // or exception messages.
        val url = "$BASE/models/$MODEL:generateContent"
        val body = JSONObject().apply {
            put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM_PROMPT)))
            )
            put("contents", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().put(JSONObject().put("text", userPrompt)))
            }))
            // Force guaranteed-JSON output so no extra text can leak through.
            put("generationConfig", JSONObject().put("responseMimeType", "application/json"))
        }.toString()

        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 30000
                readTimeout = 60000
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("x-goog-api-key", apiKey)
                doOutput = true
            }
            conn.outputStream.use { os ->
                os.write(body.toByteArray(Charsets.UTF_8))
                os.flush()
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = readLimited(stream)
            if (code !in 200..299) {
                // Never include the URL or key here - just a stable error.
                throw Exception("Gemini API error (HTTP $code)")
            }
            return extractThemeJson(text)
        } finally {
            try { conn?.disconnect() } catch (e: Exception) {}
        }
    }

    /** Pulls the model's text payload out of the Gemini response envelope. */
    private fun extractThemeJson(response: String): String {
        val root = JSONObject(response)
        val candidates = root.optJSONArray("candidates")
            ?: throw Exception("Gemini: no candidates in response")
        if (candidates.length() == 0) throw Exception("Gemini: empty response")
        val content = candidates.getJSONObject(0).optJSONObject("content")
            ?: throw Exception("Gemini: missing content")
        val parts = content.optJSONArray("parts")
            ?: throw Exception("Gemini: missing parts")
        val text = parts.optJSONObject(0)?.optString("text", "") ?: ""
        if (text.isBlank()) throw Exception("Gemini: blank response")
        return text
    }

    private fun readLimited(s: java.io.InputStream?): String {
        if (s == null) return ""
        val buf = ByteArrayOutputStream()
        val b = ByteArray(8192)
        try {
            while (true) {
                val n = s.read(b)
                if (n < 0) break
                buf.write(b, 0, n)
                if (buf.size() > 512 * 1024) break
            }
        } finally {
            try { s.close() } catch (e: Exception) {}
        }
        return buf.toString("UTF-8")
    }
}
