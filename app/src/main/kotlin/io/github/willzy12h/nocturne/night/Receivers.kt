package io.github.willzy12h.nocturne.night

import android.app.AlarmManager
import android.app.ForegroundServiceStartNotAllowedException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.core.night.ActiveNight
import io.github.willzy12h.nocturne.core.store.EventKind
import io.github.willzy12h.nocturne.graph
import io.github.willzy12h.nocturne.notify.Notifications
import io.github.willzy12h.nocturne.service.BedJetService
import kotlinx.coroutines.launch
import java.time.ZonedDateTime

/**
 * The wake-up notification and the end of the night, on time, without needing the BedJet. Not exported: only this
 * app's own alarms reach it.
 */
class NightAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val graph = context.graph
        val night = graph.repository.activeNight.value ?: return
        when (intent.action) {
            NightAlarms.ACTION_WAKE_UP -> Notifications.showWakeUp(context, night, graph.repository.settings.value)
            NightAlarms.ACTION_NIGHT_END -> {
                // Snoozes and extends push the end later; only finish once it has really come.
                if (!context.graph.now().isBefore(ZonedDateTime.parse(night.endsAt).minusMinutes(1))) {
                    finishAsync(graph, night)
                } else {
                    NightAlarms.schedule(context, night)
                }
            }
        }
    }
}

/** Ends [night] in the app, keeping the broadcast alive until its history and state are saved. */
private fun BroadcastReceiver.finishAsync(graph: Graph, night: ActiveNight) {
    val pending = goAsync()
    graph.scope.launch {
        try {
            graph.actions.finish(night, EventKind.ENDED, "Night finished")
        } finally {
            pending.finish()
        }
    }
}

/** Re-arms the night's alarms after a reboot or an app update, which clear them. Only the system sends these. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val graph = context.graph
        val night = graph.repository.activeNight.value ?: return
        if (context.graph.now().isAfter(ZonedDateTime.parse(night.endsAt))) {
            finishAsync(graph, night)
        } else {
            NightAlarms.schedule(context, night)
        }
    }
}

/** The clock app's next alarm changed: re-time tonight's wake-up on the BedJet. */
class AlarmChangedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED) return
        val graph = context.graph
        if (graph.repository.activeNight.value == null) return
        // Another app's alarm leaves the wake-up as it is; no need to wake the BedJet for that.
        if (AlarmSource.describe(context, graph.repository.settings.value)?.counts == false && !graph.actions.retimePending) return
        try {
            BedJetService.start(context, NightActions.Command.RETIME)
        } catch (e: ForegroundServiceStartNotAllowedException) {
            // Without "Unrestricted" battery use Android may refuse a background service; try within the broadcast,
            // which only has about ten seconds. On failure NightActions says so and keeps the re-time for later.
            val pending = goAsync()
            graph.scope.launch {
                try {
                    graph.actions.retimeWithin(BROADCAST_PATIENCE_MS)
                } finally {
                    pending.finish()
                }
            }
        }
    }

    private companion object {
        const val BROADCAST_PATIENCE_MS = 9_000L
    }
}
