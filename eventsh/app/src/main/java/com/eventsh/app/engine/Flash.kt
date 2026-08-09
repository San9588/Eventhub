package com.eventsh.app.engine

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

/**
 * "Flash" action: flashes a short, non-blocking message on screen.
 *
 * A plain Android Toast is blocked for background apps since Android 12, so a
 * flash is shown as a floating overlay whenever the app has the "Display over
 * other apps" permission (see [canOverlay]). That makes flashes work even when
 * a profile fires while the app runs in the background. Without the overlay
 * permission a foreground Toast is used instead and a hint is logged.
 */
object Flash {
    private const val TAG = "EVENTSH"

    @Volatile private var overlay: TextView? = null
    private val handler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { hideOverlay() }

    /** True when "Display over other apps" is granted, so background flashes work. */
    fun canOverlay(ctx: Context): Boolean = Settings.canDrawOverlays(ctx)

    /** Shows [text] for [durationMs] (clamped to 0.8s..30s, default short ~2s). */
    fun show(ctx: Context, text: String, durationMs: Long = 2000L) {
        val app = ctx.applicationContext
        handler.post {
            if (canOverlay(app)) {
                showOverlay(app, text, durationMs)
            } else {
                showToast(app, text)
                EventLog.push("[flash] overlay permission missing - flash shows only in foreground")
            }
        }
    }

    private fun showToast(ctx: Context, text: String) {
        try {
            android.widget.Toast.makeText(ctx, text, android.widget.Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.w(TAG, "flash toast failed", e)
        }
    }

    private fun showOverlay(ctx: Context, text: String, durationMs: Long) {
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        var tv = overlay
        if (tv == null) {
            tv = TextView(ctx).apply {
                setTextColor(Color.WHITE)
                textSize = 16f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(px(20), px(12), px(20), px(12))
                background = GradientDrawable().apply {
                    setColor(0xCC000000.toInt())
                    cornerRadius = px(14).toFloat()
                }
                alpha = 0f
            }
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                android.graphics.PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
                y = px(180)
            }
            try {
                wm.addView(tv, lp)
                overlay = tv
            } catch (e: Exception) {
                Log.w(TAG, "flash overlay blocked", e)
                overlay = null
                showToast(ctx, text)
                return
            }
        }
        tv.text = text
        tv.animate().cancel()
        tv.animate().alpha(1f).setDuration(180).start()
        handler.removeCallbacks(hideRunnable)
        handler.postDelayed(hideRunnable, durationMs.coerceIn(800L, 30_000L))
    }

    private fun hideOverlay() {
        handler.removeCallbacks(hideRunnable)
        val tv = overlay ?: return
        tv.animate().cancel()
        tv.animate().alpha(0f).setDuration(220).withEndAction {
            try {
                val wm = tv.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                wm.removeView(tv)
            } catch (e: Exception) {
            }
            if (overlay === tv) overlay = null
        }.start()
    }

    private fun px(v: Int): Int =
        (v * android.content.res.Resources.getSystem().displayMetrics.density).toInt()
}
