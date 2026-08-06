package com.eventsh.app.engine

import java.util.concurrent.CopyOnWriteArrayList

object EventLog {
    const val MAX = 60
    val entries = CopyOnWriteArrayList<String>()
    var listener: (() -> Unit)? = null

    fun push(line: String) {
        entries.add(0, line)
        while (entries.size > MAX) entries.removeAt(entries.size - 1)
        listener?.invoke()
    }

    fun snapshot(n: Int): List<String> = entries.take(n)
}
