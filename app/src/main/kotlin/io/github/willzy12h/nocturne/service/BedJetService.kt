package io.github.willzy12h.nocturne.service

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import io.github.willzy12h.nocturne.graph
import io.github.willzy12h.nocturne.night.NightActions
import io.github.willzy12h.nocturne.notify.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * A short foreground service that connects to the BedJet, runs one command, and lets go. Widgets, notification
 * buttons, shortcuts and alarm changes all come through here, so the work survives the app being in the background.
 */
class BedJetService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val queue = Mutex()
    private var pending = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(Notifications.WORKING_ID, Notifications.working(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        val command = intent?.getStringExtra(EXTRA_COMMAND)?.let { runCatching { NightActions.Command.valueOf(it) }.getOrNull() }
        if (command == null) {
            if (pending == 0) stopSelf(startId)
            return START_NOT_STICKY
        }
        val profileId = intent.getStringExtra(EXTRA_PROFILE)
        pending++
        scope.launch {
            queue.withLock {
                try {
                    // A re-time reports its own failure; it retries for up to a minute, within this limit.
                    val message = withTimeout(TIMEOUT_MS) { graph.actions.perform(command, profileId) }
                    lastResult = message
                } catch (e: Exception) {
                    lastResult = e.message ?: "Something went wrong."
                    Notifications.showError(this@BedJetService, "BedJet: ${label(command)} didn't work", lastResult.orEmpty())
                } finally {
                    pending--
                    if (pending == 0) stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun label(command: NightActions.Command) = command.name.lowercase().replace('_', ' ')

    companion object {
        const val EXTRA_COMMAND = "command"
        const val EXTRA_PROFILE = "profile"
        private const val TIMEOUT_MS = 90_000L

        /** The outcome of the latest command, shown by the app when it is open. */
        @Volatile var lastResult: String? = null

        fun intent(context: Context, command: NightActions.Command, profileId: String? = null): Intent =
            Intent(context, BedJetService::class.java)
                .putExtra(EXTRA_COMMAND, command.name)
                .apply { profileId?.let { putExtra(EXTRA_PROFILE, it) } }

        fun start(context: Context, command: NightActions.Command, profileId: String? = null) {
            context.startForegroundService(intent(context, command, profileId))
        }

        /** For notification buttons and widgets: a tap is a user action, so the service may start from the background. */
        fun pendingIntent(context: Context, command: NightActions.Command, profileId: String? = null): PendingIntent =
            PendingIntent.getForegroundService(
                context, (command.name + profileId).hashCode(), intent(context, command, profileId),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
    }
}
