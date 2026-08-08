package com.eventsh.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.eventsh.app.engine.AlarmEngine

/**
 * Receives fired alarms (set via AlarmManager by [AlarmEngine]) and the
 * notification Snooze / Stop actions that control them.
 */
class AlarmFireReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        try {
            when (intent.action) {
                AlarmEngine.ACTION_STOP -> AlarmEngine.stop(ctx)
                AlarmEngine.ACTION_SNOOZE -> {
                    val id = intent.getStringExtra(AlarmEngine.EXTRA_ID)
                    if (id != null) AlarmEngine.snooze(ctx, id)
                }
                else -> {
                    val id = intent.getStringExtra(AlarmEngine.EXTRA_ID)
                    if (id != null) AlarmEngine.onFire(ctx, id)
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("EVENTSH", "alarm receiver error", e)
        }
    }
}
