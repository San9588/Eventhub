package com.eventsh.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.eventsh.app.engine.EventHub
import com.eventsh.app.engine.EventLog
import com.eventsh.app.engine.UserVars

/**
 * Set a user variable from shell / Termux / root scripts:
 *   am broadcast -a com.eventsh.SET_VAR --es name myvar --es value 42
 *   am broadcast -a com.eventsh.SET_VAR --es name MYVAR --es value persistent
 * lowercase -> RAM only, UPPERCASE -> saved to disk.
 */
class VarReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "com.eventsh.SET_VAR") return
        val name = intent.getStringExtra("name") ?: return
        val value = intent.getStringExtra("value") ?: ""
        UserVars.set(context, name, value)
        val where = if (UserVars.isDiskName(name)) "DISK" else "RAM"
        EventLog.push("[var] $name=$value ($where)")
        EventHub.dispatch("var.state", mapOf("name" to name, "value" to value, "summary" to "$name=$value"))
    }
}
