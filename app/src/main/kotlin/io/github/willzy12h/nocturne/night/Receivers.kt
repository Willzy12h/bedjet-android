package io.github.willzy12h.nocturne.night

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.willzy12h.nocturne.graph
import io.github.willzy12h.nocturne.service.BedJetService
import kotlinx.coroutines.launch

/** Broadcasts only perform short durable local transitions; hardware work belongs to the service/job. */
class NightAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val graph = context.graph
        when (intent.action) {
            NightAlarms.ACTION_WAKE_UP, NightAlarms.ACTION_NIGHT_END -> local(context) {
                graph.actions.submitLocalDelivery(intent.action!!, NightAlarms.identity(intent))
            }
            NightAlarms.ACTION_LOCAL_RETRY -> local(context) { graph.actions.drainLocalDeliveries() }
            NightAlarms.ACTION_RETRY -> {
                if (!graph.actions.retimePending) return
                NightAlarms.scheduleObservation(context, graph.now())
                if (!BedJetService.start(context, NightActions.Command.RETRY_RETIME)) local(context) { graph.actions.submitAlarmChange() }
            }
        }
    }
}
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_TIME_CHANGED, AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED)) return
        local(context) { context.graph.actions.reconcile() }
    }
}
class AlarmChangedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED || context.graph.repository.activeNight.value == null) return
        NightAlarms.scheduleObservation(context, context.graph.now())
        local(context) {
            context.graph.actions.submitAlarmChange()
            BedJetService.start(context, NightActions.Command.RETRY_RETIME)
        }
    }
}
private fun BroadcastReceiver.local(context: Context, work: suspend () -> Unit) {
    val pending = goAsync()
    context.graph.scope.launch {
        try { kotlinx.coroutines.withTimeout(8_500) { work() } }
        catch (e: Exception) {
            io.github.willzy12h.nocturne.notify.Notifications.showError(context, context.getString(io.github.willzy12h.nocturne.R.string.runtime_failed),
                e.message ?: context.getString(io.github.willzy12h.nocturne.R.string.runtime_failed))
        } finally { pending.finish() }
    }
}
