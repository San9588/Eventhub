package com.eventsh.app.engine

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Lazily-initialised singleton around the built-in Android TextToSpeech engine.
 * The "Speak" action uses it - it needs zero permissions. Initialisation is
 * async (the engine may not be ready on the first call), so [speak] blocks a
 * short, bounded time (max ~3s) once to wait for it, then falls back to a log
 * entry instead of silently doing nothing. The engine is shared across actions
 * and shut down on service stop.
 */
object Tts {
    private val lock = Object()
    private var tts: TextToSpeech? = null
    private var ready = false
    @Volatile private var initFailed = false

    fun speak(ctx: Context, text: String, pitch: Float, rate: Float): Boolean {
        if (text.isBlank()) return true
        val engine = get(ctx) ?: return false
        return try {
            engine.setPitch(pitch.coerceIn(0.5f, 2f))
            engine.setSpeechRate(rate.coerceIn(0.5f, 2f))
            val id = "eventsh_" + System.currentTimeMillis()
            val r = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
            r == TextToSpeech.SUCCESS
        } catch (e: Exception) {
            android.util.Log.w(Dispatcher.TAG, "tts speak failed", e)
            false
        }
    }

    /** Returns the ready engine, waiting up to ~3s for the async init. */
    private fun get(ctx: Context): TextToSpeech? {
        synchronized(lock) {
            var t = tts
            if (t != null && ready) return t
            if (initFailed) return null
            if (t == null) {
                t = TextToSpeech(ctx.applicationContext) { status ->
                    synchronized(lock) {
                        if (status == TextToSpeech.SUCCESS) {
                            tts?.language = Locale.getDefault()
                            ready = true
                        } else {
                            initFailed = true
                        }
                        lock.notifyAll()
                    }
                }
                tts = t
            }
            val end = System.currentTimeMillis() + 3000L
            while (!ready && !initFailed && System.currentTimeMillis() < end) {
                try { lock.wait(end - System.currentTimeMillis()) } catch (e: InterruptedException) { break }
            }
            if (ready) return tts else {
                initFailed = true
                EventLog.push("[tts] engine unavailable")
                return null
            }
        }
    }

    /** Releases the engine (called from the service when it stops). */
    fun shutdown() {
        synchronized(lock) {
            try { tts?.stop(); tts?.shutdown() } catch (e: Exception) {}
            tts = null
            ready = false
            initFailed = false
        }
    }
}
