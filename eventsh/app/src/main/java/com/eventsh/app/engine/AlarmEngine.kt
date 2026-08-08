package com.eventsh.app.engine

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.util.Log
import com.eventsh.app.receiver.AlarmFireReceiver
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.UUID

/**
 * In-app alarm clock used by the Set Alarm / Cancel Alarm actions.
 *
 * Normal mode schedules an exact [AlarmManager.setAlarmClock] alarm that rings
 * with the configured sound + vibration, posts a notification with Snooze /
 * Stop actions. "Run with su" mode hands the job to the system clock app via
 * `am start` as root instead.
 */
object AlarmEngine {
    const val TAG = "EVENTSH"

    const val ACTION_FIRE = "com.eventsh.ALARM_FIRE"
    const val ACTION_SNOOZE = "com.eventsh.ALARM_SNOOZE"
    const val ACTION_STOP = "com.eventsh.ALARM_STOP"
    const val EXTRA_ID = "id"

    const val CHANNEL_ALARM = "alarm"
    const val NOTIF_ID = 0xA11

    data class Alarm(
        val id: String,
        val label: String,
        val epoch: Long,
        val vibrate: Boolean = true,
        val sound: String = "",
        val snoozeMin: Int = 5
    )

    @Volatile private var player: MediaPlayer? = null

    // ------------------------------------------------------------------ store
    private const val PREFS = "alarms"
    private const val KEY = "list"

    private fun load(ctx: Context): List<Alarm> = try {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        val arr = JSONArray(raw)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Alarm(
                id = o.optString("id"),
                label = o.optString("label"),
                epoch = o.optLong("epoch"),
                vibrate = o.optBoolean("vibrate", true),
                sound = o.optString("sound"),
                snoozeMin = o.optInt("snooze", 5)
            )
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun save(ctx: Context, list: List<Alarm>) {
        try {
            val arr = JSONArray()
            list.forEach { a ->
                arr.put(JSONObject()
                    .put("id", a.id)
                    .put("label", a.label)
                    .put("epoch", a.epoch)
                    .put("vibrate", a.vibrate)
                    .put("sound", a.sound)
                    .put("snooze", a.snoozeMin))
            }
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, arr.toString()).apply()
        } catch (e: Exception) {
        }
    }

    fun all(ctx: Context): List<Alarm> = load(ctx)

    // ------------------------------------------------------------------ api
    private fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL_ALARM, "Alarms", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Ringing alarm alerts" }
            ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    /** Schedules an alarm at [hour]:[minute] via the standard API. */
    fun setAlarm(ctx: Context, label: String, hour: Int, minute: Int, cfg: Actions.AlarmCfg) {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }
        val id = "al_" + UUID.randomUUID().toString().take(8)
        val alarm = Alarm(
            id = id,
            label = label,
            epoch = cal.timeInMillis,
            vibrate = cfg.vibrate,
            sound = cfg.sound,
            snoozeMin = cfg.snoozeMin
        )
        val list = load(ctx).filter { it.epoch > System.currentTimeMillis() }.toMutableList()
        list += alarm
        save(ctx, list)

        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val showIntent = PendingIntent.getActivity(
            ctx, id.hashCode() and 0xffff,
            Intent(ctx, com.eventsh.app.MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        try {
            am.setAlarmClock(
                AlarmManager.AlarmClockInfo(alarm.epoch, showIntent),
                fireIntent(ctx, id)
            )
        } catch (e: Exception) {
            Log.w(TAG, "setAlarmClock failed", e)
            return
        }
        EventLog.push("[alarm] set '$label' at ${String.format("%02d:%02d", hour, minute)}")
    }

    /** Sets the alarm through the system clock app (Run with su mode). */
    fun setAlarmSu(ctx: Context, label: String, hour: Int, minute: Int, vibrate: Boolean) {
        val cmd = buildString {
            append("am start -a android.intent.action.SET_ALARM")
            append(" --ei android.intent.extra.alarm.HOUR $hour")
            append(" --ei android.intent.extra.alarm.MINUTES $minute")
            append(" --ez android.intent.extra.alarm.VIBRATE ${if (vibrate) "true" else "false"}")
            // SKIP_UI sets the alarm directly without opening the clock app UI
            append(" --ez android.intent.extra.alarm.SKIP_UI true")
            if (label.isNotBlank()) append(" --es android.intent.extra.alarm.MESSAGE \"${label.replace("\"", "")}\"")
        }
        Thread {
            val out = RootBridge.execute(cmd)
            EventLog.push("[alarm] su SET_ALARM -> ${out?.trim()?.take(60) ?: "ok"}")
        }.start()
    }

    /** Cancels scheduled alarms; blank [label] cancels every alarm. */
    fun cancel(ctx: Context, label: String) {
        val now = System.currentTimeMillis()
        val list = load(ctx)
        val targets = if (label.isBlank()) list.filter { it.epoch > now }
        else list.filter { it.epoch > now && it.label.equals(label, true) }
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        targets.forEach { a ->
            try { am.cancel(fireIntent(ctx, a.id)) } catch (e: Exception) {}
        }
        save(ctx, list.filterNot { targets.contains(it) })
        EventLog.push("[alarm] cancelled ${targets.size} alarm(s)" + (if (label.isNotBlank()) " for '$label'" else ""))
    }

    /** Opens the system clock's alarm list (Run with su mode). */
    fun cancelAlarmSu(ctx: Context) {
        Thread {
            val out = RootBridge.execute("am start -a android.intent.action.SHOW_ALARMS")
            EventLog.push("[alarm] su SHOW_ALARMS -> ${out?.trim()?.take(60) ?: "ok"}")
        }.start()
    }

    /** Called by [AlarmFireReceiver] when a scheduled alarm fires. */
    fun onFire(ctx: Context, id: String) {
        val alarm = load(ctx).find { it.id == id } ?: return
        play(ctx, alarm)
        postAlarmNotification(ctx, alarm)
    }

    fun snooze(ctx: Context, id: String) {
        val list = load(ctx).toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return
        val a = list[idx]
        val min = a.snoozeMin.coerceAtLeast(1)
        val newEpoch = System.currentTimeMillis() + min * 60_000L
        list[idx] = a.copy(epoch = newEpoch)
        save(ctx, list)
        stopPlayback(ctx)
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.setAlarmClock(
            AlarmManager.AlarmClockInfo(newEpoch, null),
            fireIntent(ctx, id)
        )
        EventLog.push("[alarm] '${a.label}' snoozed for ${min}m")
        try {
            ensureChannel(ctx)
            val n = android.app.Notification.Builder(ctx, CHANNEL_ALARM)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("Alarm snoozed")
                .setContentText("'${a.label.ifBlank { "Alarm" }}' will ring again in $min minute(s)")
                .setAutoCancel(true)
                .build()
            ctx.getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n)
        } catch (e: Exception) {
        }
    }

    /** Stops ringing, dismisses the notification and removes the ringing alarm. */
    fun stop(ctx: Context, id: String? = null) {
        stopPlayback(ctx)
        try {
            ctx.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
        } catch (e: Exception) {
        }
        if (id != null) {
            val list = load(ctx).filterNot { it.id == id }
            save(ctx, list)
        }
        EventLog.push("[alarm] stopped")
    }

    /** Re-arms every future alarm (call on boot / service start). */
    fun rescheduleAll(ctx: Context) {
        val now = System.currentTimeMillis()
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        load(ctx).filter { it.epoch > now }.forEach { a ->
            try {
                am.setAlarmClock(AlarmManager.AlarmClockInfo(a.epoch, null), fireIntent(ctx, a.id))
            } catch (e: Exception) {
            }
        }
    }

    // ------------------------------------------------------------------ internals
    private fun fireIntent(ctx: Context, id: String): PendingIntent {
        val i = Intent(ctx, AlarmFireReceiver::class.java).apply {
            action = ACTION_FIRE
            putExtra(EXTRA_ID, id)
        }
        return PendingIntent.getBroadcast(
            ctx, id.hashCode(), i,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun play(ctx: Context, alarm: Alarm) {
        stopPlayback(ctx)
        val uri = if (alarm.sound.isBlank()) Settings.System.DEFAULT_ALARM_ALERT_URI else Uri.parse(alarm.sound)
        try {
            val p = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(ctx, uri)
                isLooping = true
                prepare()
                start()
            }
            player = p
        } catch (e: Exception) {
            Log.w(TAG, "alarm sound failed, using ringtone fallback", e)
            try {
                val p = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    setDataSource(ctx, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
                    isLooping = true
                    prepare()
                    start()
                }
                player = p
            } catch (e2: Exception) {
                Log.w(TAG, "alarm fallback sound failed", e2)
            }
        }
        if (alarm.vibrate) {
            try {
                val v = ctx.getSystemService(Vibrator::class.java)
                if (v != null) v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 1000, 800), 0))
            } catch (e: Exception) {
            }
        }
    }

    fun stopPlayback(ctx: Context? = null) {
        try {
            player?.let { p -> p.stop(); p.release() }
        } catch (e: Exception) {
        }
        player = null
        ctx?.getSystemService(Vibrator::class.java)?.cancel()
    }

    private fun postAlarmNotification(ctx: Context, alarm: Alarm) {
        ensureChannel(ctx)
        val id = alarm.id
        val snoozePi = PendingIntent.getBroadcast(
            ctx, id.hashCode() and 0x7fff,
            Intent(ctx, AlarmFireReceiver::class.java).apply {
                action = ACTION_SNOOZE
                putExtra(EXTRA_ID, id)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopPi = PendingIntent.getBroadcast(
            ctx, (id.hashCode() and 0x7fff) or 0x8000,
            Intent(ctx, AlarmFireReceiver::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val label = alarm.label.ifBlank { "Alarm" }
        val n = android.app.Notification.Builder(ctx, CHANNEL_ALARM)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(label)
            .setContentText("Alarm ringing")
            .setOngoing(true)
            .setPriority(android.app.Notification.PRIORITY_HIGH)
            .setVisibility(android.app.Notification.VISIBILITY_PUBLIC)
            .addAction(0, "SNOOZE ${alarm.snoozeMin}m", snoozePi)
            .addAction(0, "STOP", stopPi)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n)
    }
}
