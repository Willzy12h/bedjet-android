package io.github.willzy12h.nocturne.night

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import io.github.willzy12h.nocturne.core.night.ActiveNight
import java.time.ZonedDateTime

/**
 * Exact alarms for the night's wake-up notification and its end. These don't need the BedJet, so they work even
 * when it's out of reach. They are not alarm clocks, so they don't show in the clock app or move "next alarm".
 */
object NightAlarms {
    const val ACTION_WAKE_UP = "io.github.willzy12h.nocturne.WAKE_UP"
    const val ACTION_NIGHT_END = "io.github.willzy12h.nocturne.NIGHT_END"

    fun schedule(context: Context, night: ActiveNight) {
        set(context, ACTION_WAKE_UP, ZonedDateTime.parse(night.wakeUpStart))
        set(context, ACTION_NIGHT_END, ZonedDateTime.parse(night.endsAt))
    }

    fun cancel(context: Context) {
        val manager = context.getSystemService(AlarmManager::class.java)
        listOf(ACTION_WAKE_UP, ACTION_NIGHT_END).forEach { manager.cancel(intent(context, it)) }
    }

    fun canScheduleExact(context: Context): Boolean =
        context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    private fun set(context: Context, action: String, at: ZonedDateTime) {
        val manager = context.getSystemService(AlarmManager::class.java)
        val trigger = at.toInstant().toEpochMilli()
        if (manager.canScheduleExactAlarms()) {
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, intent(context, action))
        } else {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, intent(context, action))
        }
    }

    private fun intent(context: Context, action: String): PendingIntent = PendingIntent.getBroadcast(
        context, action.hashCode(),
        Intent(context, NightAlarmReceiver::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
