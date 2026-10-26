package io.github.willzy12h.nocturne.night

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import io.github.willzy12h.nocturne.core.night.ActiveNight
import io.github.willzy12h.nocturne.core.night.NightIdentity
import io.github.willzy12h.nocturne.graph
import java.time.ZonedDateTime

/** Separate wake, end and best-effort retry alarms. None is a phone alarm-clock alarm. */
object NightAlarms {
    const val ACTION_WAKE_UP = "io.github.willzy12h.nocturne.WAKE_UP"
    const val ACTION_NIGHT_END = "io.github.willzy12h.nocturne.NIGHT_END"
    const val ACTION_LOCAL_RETRY = "io.github.willzy12h.nocturne.RETRY_LOCAL_DELIVERY"
    const val ACTION_RECONCILE_RETRY = "io.github.willzy12h.nocturne.RETRY_RECONCILE"
    const val ACTION_RETRY = "io.github.willzy12h.nocturne.RETRY_RETIME"
    const val EXTRA_NIGHT = "nightId"
    const val EXTRA_REVISION = "revision"
    fun schedule(context: Context, night: ActiveNight) {
        if (!night.trackingManual) set(context, ACTION_WAKE_UP, ZonedDateTime.parse(night.wakeUpStart), night.identity)
        else cancel(context, ACTION_WAKE_UP)
        set(context, ACTION_NIGHT_END, ZonedDateTime.parse(night.endsAt), night.identity)
    }
    fun scheduleLocalRetry(context: Context) = set(context, ACTION_LOCAL_RETRY, context.graph.now().plusMinutes(1), null)
    fun cancelLocalRetry(context: Context) = cancel(context, ACTION_LOCAL_RETRY)
    fun scheduleReconcileRetry(context: Context) = set(context, ACTION_RECONCILE_RETRY, context.graph.now().plusMinutes(1), null)
    fun cancelReconcileRetry(context: Context) = cancel(context, ACTION_RECONCILE_RETRY)
    fun scheduleObservation(context: Context, now: ZonedDateTime) = set(context, ACTION_RETRY, now.plusMinutes(10), null)
    fun scheduleRetry(context: Context, pending: PendingRetime, now: ZonedDateTime) {
        val deadline = ZonedDateTime.parse(pending.deadline)
        val at = minOf(now.plusMinutes(10), deadline)
        if (!at.isAfter(now)) { cancelRetry(context); return }
        set(context, ACTION_RETRY, at, pending.expected)
    }
    fun cancel(context: Context) = listOf(ACTION_WAKE_UP, ACTION_NIGHT_END, ACTION_RETRY).forEach { cancel(context, it) }
    fun cancelRetry(context: Context) = cancel(context, ACTION_RETRY)
    private fun cancel(context: Context, action: String) = context.getSystemService(AlarmManager::class.java).cancel(intent(context, action, null))
    fun canScheduleExact(context: Context): Boolean = context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    private fun set(context: Context, action: String, at: ZonedDateTime, identity: NightIdentity?) {
        val manager = context.getSystemService(AlarmManager::class.java)
        val trigger = at.toInstant().toEpochMilli()
        if (trigger <= context.graph.clock.millis()) { cancel(context, action); return }
        val intent = intent(context, action, identity)
        try {
            if (manager.canScheduleExactAlarms()) manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, intent)
            else manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, intent)
        } catch (_: SecurityException) { manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, intent) }
    }
    private fun intent(context: Context, action: String, identity: NightIdentity?) = PendingIntent.getBroadcast(
        context, action.hashCode(), Intent(context, NightAlarmReceiver::class.java).setAction(action)
            .putExtra(EXTRA_NIGHT, identity?.nightId).putExtra(EXTRA_REVISION, identity?.revision ?: -1),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    fun identity(intent: Intent): NightIdentity? = intent.getStringExtra(EXTRA_NIGHT)?.let { NightIdentity(it, intent.getIntExtra(EXTRA_REVISION, -1)) }
}
