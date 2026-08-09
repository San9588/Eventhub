package com.eventsh.app

import com.eventsh.app.ui.showThemed

import android.app.AlertDialog
import android.content.pm.ApplicationInfo
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.eventsh.app.engine.EventCatalog
import com.eventsh.app.engine.Store
import com.eventsh.app.ui.Maniflow
import com.eventsh.app.ui.Theme

/**
 * MainActivity PICKERS - app picker and event picker dialogs.
 *
 * These are Kotlin extension functions on MainActivity.
 */
fun MainActivity.appPick(selected: Set<String>, onDone: (List<String>) -> Unit) {
    val act = this
    val t = Theme.current
    val pm = packageManager
    val all = try {
        pm.getInstalledApplications(0)
            .filter { it.packageName != packageName }
    } catch (e: Exception) {
        emptyList()
    }
    fun label(ai: ApplicationInfo): String {
        val l = pm.getApplicationLabel(ai)?.toString() ?: ai.packageName
        return if (l.equals(ai.packageName, true)) l else "$l  [${ai.packageName}]"
    }
    fun isSystem(ai: ApplicationInfo) = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
    val user = all.filter { !isSystem(it) }.sortedBy { label(it).lowercase() }
    val system = all.filter { isSystem(it) }.sortedBy { label(it).lowercase() }
    val checked = selected.toMutableSet()
    val rows = ArrayList<Any>()
    if (user.isNotEmpty()) {
        rows.add("USER APPS (${user.size})")
        rows.addAll(user)
    }
    if (system.isNotEmpty()) {
        rows.add("SYSTEM APPS (${system.size})")
        rows.addAll(system)
    }
    val lv = ListView(this)
    lv.adapter = object : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(pos: Int) = rows[pos]
        override fun getItemId(pos: Int) = pos.toLong()
        override fun getItemViewType(pos: Int) = if (rows[pos] is String) 0 else 1
        override fun getViewTypeCount() = 2
        override fun isEnabled(pos: Int) = rows[pos] !is String
        override fun getView(pos: Int, convertView: View?, parent: ViewGroup): View {
            val r = rows[pos]
            if (r is String) {
                return Maniflow.text(act, r, 13f, t.accentPrimary, bold = true).apply {
                    setPadding(dp(12f), dp(14f), dp(12f), dp(4f))
                    setBackgroundColor(t.surfaceBg)
                }
            }
            val ai = r as ApplicationInfo
            val cb = CheckBox(act).apply {
                isChecked = checked.contains(ai.packageName)
                isClickable = false
                isFocusable = false
            }
            val tv = Maniflow.text(act, label(ai), 15f, t.textPrimary)
            return LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
                setBackgroundColor(t.surfaceBg)
                addView(cb)
                addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = dp(8f)
                })
                setOnClickListener {
                    if (!checked.remove(ai.packageName)) checked.add(ai.packageName)
                    cb.isChecked = checked.contains(ai.packageName)
                }
            }
        }
    }
    AlertDialog.Builder(this)
        .setTitle("SELECT APPS (${all.size})")
        .setView(lv)
        .setPositiveButton("OK") { _, _ ->
            onDone(rows.filter { it is ApplicationInfo }.map { (it as ApplicationInfo).packageName }.filter { checked.contains(it) })
        }
        .setNegativeButton("CANCEL", null)
        .showThemed()
}

fun MainActivity.pickEvent(onPick: (String) -> Unit) {
    val act = this
    val t = Theme.current
    val used = Store.profiles(this).flatMap { it.eventActions }
        .filter { it.isNotBlank() }
        .distinct()
        .filter { it !in EventCatalog.STANDARD }
    val all = EventCatalog.STANDARD + used + "custom..."
    val filtered = all.toMutableList()

    val lv = ListView(this)
    val adapter = object : BaseAdapter() {
        override fun getCount() = filtered.size
        override fun getItem(pos: Int) = filtered[pos]
        override fun getItemId(pos: Int) = pos.toLong()
        override fun getView(pos: Int, convertView: View?, parent: ViewGroup): View {
            val label = filtered[pos]
            val isCustom = label !in EventCatalog.STANDARD
            return TextView(act).apply {
                text = label
                textSize = 15f
                setTextColor(if (isCustom) t.accentPrimary else t.textPrimary)
                setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
            }
        }
    }
    lv.adapter = adapter
    var pickerDialog: AlertDialog? = null
    lv.setOnItemClickListener { _, _, pos, _ ->
        val sel = filtered[pos]
        if (sel == "custom...") {
            val input = EditText(this).apply {
                hint = "your event name or any broadcast action"
                setTextColor(t.textPrimary)
                setHintTextColor(t.textMuted)
                textSize = 18f
            }
            AlertDialog.Builder(this)
                .setTitle("CUSTOM EVENT")
                .setView(input)
                .setPositiveButton("OK") { _, _ ->
                    val v = input.text.toString().trim()
                    if (v.isNotEmpty()) {
                        pickerDialog?.dismiss()
                        onPick(v)
                    }
                }
                .setNegativeButton("CANCEL", null)
                .showThemed()
        } else {
            pickerDialog?.dismiss()
            onPick(sel)
        }
    }

    val search = EditText(this).apply {
        hint = "search events..."
        setHintTextColor(t.textMuted)
        setTextColor(t.textPrimary)
        textSize = 16f
        background = Maniflow.rounded(act, t.surfaceBg, 10, t.borderColor, 1f)
        setPadding(dp(10f), dp(9f), dp(10f), dp(9f))
        addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString()?.trim()?.lowercase() ?: ""
                filtered.clear()
                if (q.isEmpty()) {
                    filtered.addAll(all)
                } else {
                    filtered.addAll(all.filter { it.lowercase().contains(q) })
                }
                adapter.notifyDataSetChanged()
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
    }

    val col = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(14f), dp(10f), dp(14f), dp(4f))
        })
        addView(Maniflow.text(act, "custom event action: any broadcast string", 12f, t.textMuted).apply {
            setPadding(dp(16f), dp(4f), dp(16f), dp(2f))
        })
        addView(lv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    pickerDialog = AlertDialog.Builder(this)
        .setTitle("CHOOSE EVENT (${all.size})")
        .setView(col)
        .setNegativeButton("CANCEL", null)
        .showThemed()
}
