package io.github.willzy12h.nocturne.night

import android.app.AlarmManager
import android.app.ForegroundServiceStartNotAllowedException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.willzy12h.nocturne.core.store.EventKind
import io.github.willzy12h.nocturne.graph
import io.github.willzy12h.nocturne.notify.Notifications
import io.github.willzy12h.nocturne.service.BedJetService
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.ZonedDateTime

/** The wake-up notification and the end of the night, on time, without needing the BedJet. Also re-arms after a reboot. */
class NightAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val graph = context.graph
        val night = graph.repository.activeNight.value ?: return
        when (intent.action) {
            NightAlarms.ACTION_WAKE_UP -> Notifications.showWakeUp(context, night, graph.repository.settings.value)
            NightAlarms.ACTION_NIGHT_END -> {
                // Snoozes and extends push the end later; only finish once it has really come.
                if (!ZonedDateTime.now().isBefore(ZonedDateTime.parse(night.endsAt).minusMinutes(1))) {
                    graph.actions.finish(night, EventKind.ENDED, "Night finished")
                } else {
                    NightAlarms.schedule(context, night)
                }
            }
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                if (ZonedDateTime.now().isAfter(ZonedDateTime.parse(night.endsAt))) {
                    graph.actions.finish(night, EventKind.ENDED, "Night finished")
                } else {
                    NightAlarms.schedule(context, night)
                }
            }
        }
    }
}

/** The clock app's next alarm changed: re-time tonight's wake-up on the BedJet. */
class AlarmChangedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED) return
        val graph = context.graph
        if (graph.repository.activeNight.value == null) return
        try {
            BedJetService.start(context, NightActions.Command.RETIME)
        } catch (e: ForegroundServiceStartNotAllowedException) {
            // Without "Unrestricted" battery use Android may refuse a background service; try within the broadcast.
            val pending = goAsync()
            graph.scope.launch {
                try {
                    withTimeoutOrNull(50_000) { graph.sessions.withConnection { graph.actions.run(NightActions.Command.RETIME) } }
                } catch (_: Exception) {
                    // NightActions has already told the person the wake-up didn't move.
                } finally {
                    pending.finish()
                }
            }
        }
    }
}
