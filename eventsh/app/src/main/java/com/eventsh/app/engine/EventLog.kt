package com.eventsh.app.engine

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

object EventLog {
    const val MAX = 60
    val entries = CopyOnWriteArrayList<String>()
    var listener: (() -> Unit)? = null

    fun push(line: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        entries.add(0, "$ts $line")
        while (entries.size > MAX) entries.removeAt(entries.size - 1)
        listener?.invoke()
    }

    fun snapshot(n: Int): List<String> = entries.take(n)
}
