package com.eventsh.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.eventsh.app.ui.Maniflow
import com.eventsh.app.ui.Theme
import java.io.File

/**
 * Simple file / folder browser rooted at the public storage (/sdcard).
 * Tapping a folder opens it; tapping a file or "USE THIS FOLDER" returns the
 * selected path through [RESULT_PATH]. Used for the file-event triggers and
 * any action that needs a path.
 */
class FilePickerActivity : Activity() {

    companion object {
        const val RESULT_PATH = "path"
        const val EXTRA_START = "start"
    }

    private var currentDir: File = File(Environment.getExternalStorageDirectory(), "")
    private lateinit var pathTv: TextView
    private lateinit var list: ListView
    private var entries: List<Entry> = emptyList()

    private data class Entry(val file: File, val isDir: Boolean)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val t = Theme.current
        val start = intent.getStringExtra(EXTRA_START)
        if (!start.isNullOrBlank() && File(start).isDirectory) currentDir = File(start)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(t.surfaceBg)
        }
        root.addView(buildTopBar())
        pathTv = Maniflow.text(this, "", 13f, t.textMuted).apply {
            setPadding(dp(14f), dp(6f), dp(14f), dp(6f))
            maxLines = 1
        }
        root.addView(pathTv)
        list = ListView(this).apply {
            divider = null
            dividerHeight = 0
            setSelector(android.R.color.transparent)
            setBackgroundColor(t.surfaceBg)
        }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(buildBottomBar())
        setContentView(root)

        list.adapter = Adapter()
        list.setOnItemClickListener { _, _, pos, _ ->
            val e = entries.getOrNull(pos) ?: return@setOnItemClickListener
            if (e.isDir) {
                open(e.file)
            } else {
                select(e.file.absolutePath)
            }
        }
        open(currentDir)
    }

    private fun buildTopBar(): View {
        val t = Theme.current
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8f), dp(12f), dp(8f), dp(10f))
            setBackgroundColor(t.cardBg)
        }
        val back = TextView(this).apply {
            text = "‹  BACK"
            textSize = 16f
            setTextColor(t.accentPrimary)
            setPadding(dp(6f), dp(4f), dp(12f), dp(4f))
            setOnClickListener { goUp() }
        }
        bar.addView(back)
        bar.addView(
            Maniflow.text(this, "SELECT PATH", 16f, t.textPrimary, bold = true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        val home = TextView(this).apply {
            text = "HOME"
            textSize = 13f
            setTextColor(t.accentPrimary)
            setPadding(dp(6f), dp(4f), dp(6f), dp(4f))
            setOnClickListener {
                open(File(Environment.getExternalStorageDirectory(), ""))
            }
        }
        bar.addView(home)
        return bar
    }

    private fun buildBottomBar(): View {
        val t = Theme.current
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(dp(12f), dp(6f), dp(12f), dp(12f))
            setBackgroundColor(t.surfaceBg)
        }
        bar.addView(
            materialButton("CANCEL", t.danger) { setResult(RESULT_CANCELED); finish() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8f) }
        )
        bar.addView(
            materialButton("USE THIS FOLDER", t.accentPrimary) { select(currentDir.absolutePath) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        return bar
    }

    private fun materialButton(label: String, color: Int, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(if (color == Theme.current.accentPrimary) Theme.current.headerText else Theme.current.textPrimary)
            gravity = Gravity.CENTER
            setPadding(dp(10f), dp(12f), dp(10f), dp(12f))
            background = Maniflow.rounded(
                this@FilePickerActivity,
                if (color == Theme.current.accentPrimary) color else Theme.current.cardBg,
                12,
                Theme.current.borderColor,
                1f
            )
            setOnClickListener { onClick() }
        }

    private fun goUp() {
        val parent = currentDir.parentFile
        if (parent != null && currentDir.absolutePath != File(Environment.getExternalStorageDirectory(), "").absolutePath) {
            open(parent)
        } else {
            setResult(RESULT_CANCELED)
            finish()
        }
    }

    private fun select(path: String) {
        setResult(RESULT_OK, Intent().putExtra(RESULT_PATH, path))
        finish()
    }

    private fun open(dir: File) {
        val listRaw = try {
            dir.listFiles()?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() }) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        entries = listRaw.map { Entry(it, it.isDirectory) }
        currentDir = dir
        pathTv.text = dir.absolutePath
        (list.adapter as BaseAdapter).notifyDataSetChanged()
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = entries.size
        override fun getItem(pos: Int) = entries[pos]
        override fun getItemId(pos: Int) = pos.toLong()

        override fun getView(pos: Int, convertView: View?, parent: ViewGroup): View {
            val t = Theme.current
            val e = entries[pos]
            val row = LinearLayout(this@FilePickerActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
                setBackgroundColor(t.surfaceBg)
            }
            val icon = TextView(this@FilePickerActivity).apply {
                text = if (e.isDir) "▸" else "·"
                textSize = 15f
                setTextColor(if (e.isDir) t.accentPrimary else t.textMuted)
            }
            row.addView(icon, LinearLayout.LayoutParams(dp(22f), ViewGroup.LayoutParams.WRAP_CONTENT))
            row.addView(
                Maniflow.text(this@FilePickerActivity, e.file.name, 15f, if (e.isDir) t.textPrimary else t.textMuted).apply {
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(8f)
                }
            )
            return cardWrap(row)
        }
    }

    private fun cardWrap(card: View): View {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6f), dp(2f), dp(6f), dp(2f))
            addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        wrap.layoutParams = AbsListView.LayoutParams(AbsListView.LayoutParams.MATCH_PARENT, AbsListView.LayoutParams.WRAP_CONTENT)
        return wrap
    }

    private fun dp(v: Float): Int = Maniflow.dpf(this, v)
}
