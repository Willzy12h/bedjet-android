package io.github.willzy12h.nocturne.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.updateAll
import io.github.willzy12h.nocturne.graph
import io.github.willzy12h.nocturne.widget.QuickControlsWidget
import io.github.willzy12h.nocturne.widget.SingleActionWidget
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** Only protected clock/boot/package events; no hardware access or foreground-service promotion. */
class PlatformClockReceiver:BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        if(intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED,Intent.ACTION_MY_PACKAGE_REPLACED,
                Intent.ACTION_TIMEZONE_CHANGED,Intent.ACTION_TIME_CHANGED,
                android.app.AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED,
                android.app.AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED))return
        // A change between the 12- and 24-hour clock arrives as a time change; the widgets below redraw with it.
        io.github.willzy12h.nocturne.ui.Format.followPhoneClock(context)
        val pending=goAsync()
        context.graph.scope.launch {
            try {withTimeout(8_000) {
                ChargerJobs.sync(context.graph)
                BedtimeReminders.sync(context.graph)
                QuickControlsWidget().updateAll(context)
                SingleActionWidget().updateAll(context)
            }} finally {pending.finish()}
        }
    }
}
