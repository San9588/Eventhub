package com.eventsh.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.eventsh.app.engine.EventHub

/**
 * Receives `am broadcast -a com.eventsh.SHELL_EVENT --es name foo --es summary bar`
 * from root/user shell scripts and dispatches the matching rules.
 */
class ShellEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "com.eventsh.SHELL_EVENT") return
        val name = intent.getStringExtra("name") ?: "shell_event"
        val data = HashMap<String, String>()
        intent.extras?.keySet()?.forEach { k ->
            intent.getStringExtra(k)?.let { data[k] = it }
        }
        if (!data.containsKey("summary")) data["summary"] = name
        EventHub.dispatch(name, data)
    }
}
