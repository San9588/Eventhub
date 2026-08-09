package com.eventsh.app.engine

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * HTTP / webhook action engine (Tasker "HTTP Request" style).
 *
 * - URL, method, headers, query params, body and the result-variable name all
 *   support %VAR% substitution ([Vars.resolve]).
 * - The response body lands in the configured result variable (default
 *   %http_result%); the status code is always stored in %http_code%.
 * - An optional "save to file" path writes the body to disk (absolute path,
 *   or a relative path under the app's external files dir).
 * - Runs on the Dispatcher worker thread, so blocking network I/O never
 *   touches the UI thread. Timeouts and a 2 MiB response cap keep memory and
 *   battery usage bounded.
 */
object HttpApi {

    data class Result(val body: String, val code: Int)

    private const val MAX_BYTES = 2 * 1024 * 1024
    private const val TAG = Dispatcher.TAG

    /**
     * Executes the request described by [cfg] against [urlRaw], resolving every
     * field with %VAR% first. Throws on connection errors so the caller's
     * retry/backoff can decide whether to try again.
     */
    @Throws(Exception::class)
    fun execute(ctx: Context, urlRaw: String, cfg: Actions.HttpCfg, vars: Map<String, String>): Result {
        val method = Vars.resolve(cfg.method, vars).uppercase().ifBlank { "GET" }
        var url = Vars.resolve(urlRaw, vars)
        val headers = Vars.resolve(cfg.headers, vars)
        val body = Vars.resolve(cfg.body, vars)
        val query = Vars.resolve(cfg.query, vars)
        val contentType = Vars.resolve(cfg.contentType, vars)
        val saveFile = Vars.resolve(cfg.saveFile, vars)
        val timeoutMs = cfg.timeoutSec.coerceIn(1, 120) * 1000

        // append query params to the URL (both GET and body-carrying methods)
        val qs = encodeQuery(parsePairs(query))
        if (qs.isNotEmpty()) {
            url += if (url.contains("?")) "&$qs" else "?$qs"
        }

        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = cfg.followRedirects
                requestMethod = method
                useCaches = false
            }
            for ((k, v) in parsePairs(headers)) {
                if (k.isNotBlank()) conn.setRequestProperty(k, v)
            }
            if (contentType.isNotBlank()) conn.setRequestProperty("Content-Type", contentType)

            if (method in setOf("POST", "PUT", "PATCH", "DELETE") && body.isNotBlank()) {
                conn.doOutput = true
                conn.outputStream.use { os ->
                    os.write(body.toByteArray(Charsets.UTF_8))
                    os.flush()
                }
            }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = if (stream == null) "" else readLimited(stream, MAX_BYTES)

            if (saveFile.isNotBlank()) {
                val written = writeToFile(ctx, saveFile, text)
                if (written) EventLog.push("[http] saved ${text.length} chars to $saveFile")
                else EventLog.push("[http] FAILED to save response to $saveFile")
            }
            return Result(text, code)
        } finally {
            try { conn?.disconnect() } catch (e: Exception) {}
        }
    }

    /** Parses "key:value" or "key=value" pairs, newline / `|` / `;` separated. */
    fun parsePairs(spec: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        for (raw in spec.split('\n', '|', ';')) {
            val line = raw.trim()
            if (line.isBlank()) continue
            val cIdx = line.indexOf(':')
            val eIdx = line.indexOf('=')
            val idx = if (cIdx > 0 && (eIdx < 0 || cIdx < eIdx)) cIdx
            else if (eIdx > 0) eIdx
            else continue
            out += line.substring(0, idx).trim() to line.substring(idx + 1).trim()
        }
        return out
    }

    private fun encodeQuery(pairs: List<Pair<String, String>>): String =
        pairs.filter { it.first.isNotBlank() }
            .joinToString("&") { (k, v) ->
                "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
            }

    /** Writes [text] to [path]; relative paths land under the external files dir. */
    private fun writeToFile(ctx: Context, path: String, text: String): Boolean = try {
        val f = File(path)
        val target = if (f.isAbsolute) f
        else File((ctx.getExternalFilesDir(null) ?: ctx.filesDir), path)
        target.parentFile?.mkdirs()
        target.writeText(text, Charsets.UTF_8)
        true
    } catch (e: Exception) {
        android.util.Log.w(TAG, "http save file failed", e)
        false
    }

    /** Reads [s] capping at [cap] bytes (UTF-8). */
    private fun readLimited(s: java.io.InputStream, cap: Int): String {
        val buf = ByteArrayOutputStream()
        val b = ByteArray(8192)
        var total = 0
        try {
            while (true) {
                val n = s.read(b)
                if (n < 0) break
                if (total + n > cap) {
                    buf.write(b, 0, cap - total)
                    break
                }
                buf.write(b, 0, n)
                total += n
            }
        } finally {
            try { s.close() } catch (e: Exception) {}
        }
        return buf.toString("UTF-8")
    }
}
