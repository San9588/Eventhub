package com.eventsh.app

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.eventsh.app.theme.GeminiApi
import com.eventsh.app.theme.ThemeController
import com.eventsh.app.theme.ThemeStore
import com.eventsh.app.theme.ThemeValidator
import com.eventsh.app.ui.Maniflow
import com.eventsh.app.ui.Theme

/**
 * THEME STUDIO - the "build your own theme" screen. User types a natural-language
 * theme request, we ask Gemini for a token set, run it through the validator
 * and (only then) apply + persist it. Also hosts the UNDO snackbar.
 */
class ThemeStudioActivity : Activity() {

    private lateinit var rootFrame: FrameLayout
    private lateinit var body: LinearLayout
    private lateinit var promptInput: EditText
    private lateinit var generateBtn: FrameLayout
    private lateinit var generateLabel: TextView
    private lateinit var generateProgress: ProgressBar
    private var loading = false
    private var snackbarRef: View? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    override fun onBackPressed() {
        if (loading) return
        super.onBackPressed()
    }

    private fun dp(v: Float): Int = Maniflow.dpf(this, v)

    private fun buildUi() {
        val t = Theme.current
        rootFrame = FrameLayout(this).apply {
            setBackgroundColor(t.surfaceBg)
            if (Build.VERSION.SDK_INT >= 30) {
                setOnApplyWindowInsetsListener { v, insets ->
                    val bars = insets.getInsets(
                        WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                    )
                    v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                    insets
                }
                requestApplyInsets()
            }
        }

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(t.surfaceBg)
        }
        col.addView(Maniflow.header(this, "Apni theme banao", status = "AI se apna theme banao", onBack = { finish() }))
        val scroll = ScrollView(this).apply { setBackgroundColor(t.surfaceBg) }
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6f), dp(8f), dp(6f), dp(24f))
        }
        scroll.addView(body, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        col.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        rootFrame.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(rootFrame)
        buildBody()
    }

    private fun buildBody() {
        val t = Theme.current
        body.removeAllViews()

        if (!ThemeStore.hasApiKey(this)) {
            val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            inner.addView(
                Maniflow.text(this, "Pehle Settings mein Gemini API key add karo", 14f, t.textMuted).apply {
                    setPadding(dp(2f), dp(2f), dp(2f), dp(10f))
                }
            )
            inner.addView(Maniflow.button(this, "SETTINGS KHOLO", true) { openSettings() })
            body.addView(Maniflow.card(this, inner), matchWrap())
            return
        }

        val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        inner.addView(Maniflow.sectionLabel(this, "Apni theme describe karo"))
        promptInput = EditText(this).apply {
            hint = "e.g. sunset warm orange theme kardo"
            setHintTextColor(t.textMuted)
            setTextColor(t.textPrimary)
            textSize = 16f
            minLines = 2
            gravity = Gravity.TOP or Gravity.START
            background = Maniflow.rounded(this@ThemeStudioActivity, t.surfaceBg, 10, borderColor = t.borderColor, borderDp = 1f)
            setPadding(dp(12f), dp(12f), dp(12f), dp(12f))
        }
        inner.addView(promptInput)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        btnRow.addView(buildGenerateButton(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12f)
        })
        inner.addView(btnRow, matchWrap())
        body.addView(Maniflow.card(this, inner), matchWrap())
    }

    private fun buildGenerateButton(): View {
        val t = Theme.current
        generateLabel = Maniflow.text(this, "Generate", 15f, t.headerText, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(28f), dp(12f), dp(28f), dp(12f))
        }
        generateProgress = ProgressBar(this).apply {
            isIndeterminate = true
            visibility = View.GONE
            indeterminateTintList = android.content.res.ColorStateList.valueOf(t.headerText)
        }
        generateBtn = FrameLayout(this).apply {
            isClickable = true
            isFocusable = true
            background = Maniflow.rounded(this@ThemeStudioActivity, t.accentPrimary, t.radiusCard)
            elevation = Maniflow.dpf(this@ThemeStudioActivity, (t.shadowElevation / 2).toFloat()).toFloat()
            setOnClickListener { onGenerate() }
        }
        generateBtn.addView(generateLabel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        generateBtn.addView(generateProgress, FrameLayout.LayoutParams(dp(22f), dp(22f), Gravity.CENTER))
        return generateBtn
    }

    private fun setLoading(on: Boolean) {
        loading = on
        generateProgress.visibility = if (on) View.VISIBLE else View.GONE
        generateLabel.alpha = if (on) 0.3f else 1f
        generateBtn.isEnabled = !on
        generateBtn.isClickable = !on
        generateBtn.isFocusable = !on
        promptInput.isEnabled = !on
    }

    private fun onGenerate() {
        if (loading) return
        val prompt = promptInput.text.toString().trim()
        if (prompt.isBlank()) {
            toast("Describe your theme first")
            return
        }
        val apiKey = ThemeStore.apiKey(this)
        if (apiKey.isNullOrBlank()) {
            toast("Add your Gemini API key in Settings first")
            buildBody()
            return
        }

        setLoading(true)
        val act = this
        Thread {
            try {
                val rawJson = GeminiApi.generateTheme(apiKey, prompt)
                val current = Theme.current
                val tokens = ThemeValidator.validate(rawJson, current)
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    setLoading(false)
                    ThemeController.apply(act, tokens)
                    buildUi()
                    showAppliedSnackbar {
                        ThemeController.undo(act)
                        buildUi()
                        toast("Previous theme restored")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    setLoading(false)
                    toast("Theme generate nahi ho paya, dobara try karo")
                }
            }
        }.start()
    }

    private fun showAppliedSnackbar(onUndo: () -> Unit) {
        snackbarRef?.let { rootFrame.removeView(it) }
        val t = Theme.current
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Maniflow.rounded(this@ThemeStudioActivity, t.textPrimary, 12)
            elevation = Maniflow.dpf(this@ThemeStudioActivity, 6f).toFloat()
            setPadding(dp(16f), dp(12f), dp(10f), dp(12f))
        }
        bar.addView(
            Maniflow.text(this, "Theme apply ho gaya", 14f, t.headerText),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        bar.addView(Maniflow.text(this, "UNDO", 14f, t.statGreen, bold = true).apply {
            setPadding(dp(14f), dp(6f), dp(8f), dp(6f))
            setOnClickListener {
                rootFrame.removeView(bar)
                onUndo()
            }
        })
        val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
        lp.setMargins(dp(16f), 0, dp(16f), dp(24f))
        bar.layoutParams = lp
        bar.alpha = 0f
        rootFrame.addView(bar)
        bar.animate().alpha(1f).setDuration(150).start()
        snackbarRef = bar
        handler.postDelayed({ rootFrame.removeView(bar) }, 4000)
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun matchWrap(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun openSettings() {
        val i = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        i.putExtra("open_tab", 4)
        startActivity(i)
        finish()
    }
}
