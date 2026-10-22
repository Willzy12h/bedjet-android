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
                Intent.ACTION_TIMEZONE_CHANGED,Intent.ACTION_TIME_CHANGED))return
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
