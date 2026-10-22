package io.github.willzy12h.nocturne.service

import android.Manifest
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.graph
import io.github.willzy12h.nocturne.core.store.deviceId
import io.github.willzy12h.nocturne.night.*
import io.github.willzy12h.nocturne.notify.Notifications
import kotlinx.coroutines.*

/** Owns the entire bounded operation, including waiting for the coordinator and connecting. */
class BedJetService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pending = 0
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val handoff = intent?.getStringExtra(EXTRA_HANDOFF)?.let { handoffs.remove(it) }
        val request = intent?.let(::request)
        if (request == null && handoff == null) { if (pending == 0) stopSelf(startId); return START_NOT_STICKY }
        try {
            if (!permitted(this, request?.command ?: NightActions.Command.START_NIGHT)) throw SecurityException(getString(R.string.runtime_connect_permission))
            val local = graph.repository.settings.value.demoMode || request?.command == NightActions.Command.STOP_TRACKING
            val type = if (!local) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                else if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE else 0
            startForeground(Notifications.WORKING_ID, Notifications.working(this), type)
        } catch (e: RuntimeException) {
            handoff?.result?.completeExceptionally(e)
            failure(this, e, handoff?.notifyFailures ?: true); if (pending == 0) stopSelf(startId); return START_NOT_STICKY
        }
        pending++
        val operation = scope.launch {
            try {
                if (handoff != null) {
                    if (!handoff.result.isActive) return@launch
                    val value = withTimeout(NightActions.OPERATION_MS) {
                        withContext(SubmissionTarget(handoff.target)) { handoff.work() }
                    }
                    handoff.result.complete(value)
                    return@launch
                }
                val result = withTimeout(NightActions.OPERATION_MS) { graph.actions.execute(request!!) }
                lastResult = result.message
                if (!result.succeeded) Notifications.showError(this@BedJetService, getString(R.string.runtime_failed), result.message)
            } catch (e: CancellationException) {
                handoff?.result?.cancel(e)
                if (e is TimeoutCancellationException) failure(this@BedJetService, e, handoff?.notifyFailures ?: true)
                throw e
            } catch (e: Exception) { handoff?.result?.completeExceptionally(e); failure(this@BedJetService, e, handoff?.notifyFailures ?: true)
            } finally { pending--; if (pending == 0) stopSelf() }
        }
        handoff?.operation = operation
        if (handoff != null) operation.invokeOnCompletion { cause ->
            if (cause != null) handoff.result.cancel(CancellationException("Service operation ended", cause))
        }
        if (handoff != null && !handoff.result.isActive) operation.cancel()
        return START_NOT_STICKY
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    companion object {
        private const val EXTRA_HANDOFF = "ownedUiOperation"
        private class Handoff(val target: String?, val notifyFailures: Boolean, val work: suspend () -> Any?) {
            val result = CompletableDeferred<Any?>()
            @Volatile var operation: Job? = null
        }
        private val handoffs = java.util.concurrent.ConcurrentHashMap<String, Handoff>()
        /** In-process UI delivery only: no persistent closures or replay. Job/local callers stay separate.
         * Capture confirmed request identity before entering; the target is captured here before suspension
         * and enforced by NightActions under its mutex, even for deferred pin/restore/setup calls.
         * An origin that displays exceptions AND operation timeouts may disable duplicate error notifications;
         * exception delivery, lastResult and ownership/cancellation are unchanged. */
        @Suppress("UNCHECKED_CAST")
        suspend fun <T> runOwned(context: Context, notifyFailures: Boolean = true, work: suspend () -> T): T {
            val submitted = currentCoroutineContext()[SubmissionTarget]
            val target = if (submitted != null) submitted.deviceId else context.graph.repository.settings.value.deviceId()
            if (context.graph.repository.settings.value.demoMode)
                return withContext(SubmissionTarget(target)) { withTimeout(NightActions.OPERATION_MS) { work() } }
            val id = java.util.UUID.randomUUID().toString()
            val handoff = Handoff(target, notifyFailures, work)
            handoffs[id] = handoff
            try {
                if (!permitted(context, NightActions.Command.START_NIGHT)) throw SecurityException(context.getString(R.string.runtime_connect_permission))
                context.startForegroundService(Intent(context, BedJetService::class.java).putExtra(EXTRA_HANDOFF, id))
                return withTimeout(NightActions.OPERATION_MS) { handoff.result.await() } as T
            } catch (e: CancellationException) {
                if (e is TimeoutCancellationException) failure(context, e, notifyFailures)
                throw e
            } catch (e: Exception) { failure(context, e, notifyFailures); throw e
            } finally {
                handoffs.remove(id)
                handoff.result.cancel()
                handoff.operation?.cancel()
            }
        }

        const val EXTRA_COMMAND = "command"
        const val EXTRA_PROFILE = "profile"
        private const val EXTRA_TARGET = "target"
        private const val EXTRA_CONFIRMED = "replacementConfirmed"
        private const val EXTRA_STRICT = "strictIdentity"
        @Volatile var lastResult: String? = null
        fun intent(context: Context, command: NightActions.Command, profileId: String? = null, requireIdentity: Boolean = false, replacementConfirmed: Boolean = false): Intent {
            val captured = context.graph.actions.capture(command, profileId, requireIdentity)
            return intent(context, captured.copy(replacementConfirmed = replacementConfirmed))
        }
        private fun intent(context: Context, captured: CommandRequest) =
            Intent(context, BedJetService::class.java).putExtra(EXTRA_COMMAND, captured.command.name)
                .putExtra(EXTRA_PROFILE, captured.profileId).putExtra(EXTRA_TARGET, captured.deviceId)
                .putExtra(EXTRA_CONFIRMED, captured.replacementConfirmed).putExtra(EXTRA_STRICT, captured.requireIdentity)
                .putExtra(NightAlarms.EXTRA_NIGHT, captured.expected?.nightId)
                .putExtra(NightAlarms.EXTRA_REVISION, captured.expected?.revision ?: -1)
        private fun request(intent: Intent): CommandRequest? {
            val command = intent.getStringExtra(EXTRA_COMMAND)?.let { runCatching { NightActions.Command.valueOf(it) }.getOrNull() } ?: return null
            return CommandRequest(command, intent.getStringExtra(EXTRA_PROFILE), intent.getStringExtra(EXTRA_TARGET),
                NightAlarms.identity(intent), intent.getBooleanExtra(EXTRA_STRICT, false), intent.getBooleanExtra(EXTRA_CONFIRMED, false))
        }
        /** False means no service owns the work. Receivers may only record/schedule a pending retry. */
        fun start(context: Context, command: NightActions.Command, profileId: String? = null, replacementConfirmed: Boolean = false): Boolean =
            start(context, context.graph.actions.capture(command, profileId, requireIdentity = replacementConfirmed)
                .copy(replacementConfirmed = replacementConfirmed))
        fun start(context: Context, captured: CommandRequest): Boolean = try {
            if (!permitted(context, captured.command)) throw SecurityException(context.getString(R.string.runtime_connect_permission))
            context.startForegroundService(intent(context, captured)); true
        } catch (e: RuntimeException) { failure(context, e); false }
        private fun permitted(context: Context, command: NightActions.Command) =
            context.graph.repository.settings.value.demoMode || command == NightActions.Command.STOP_TRACKING ||
                context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        private fun failure(context: Context, e: Exception, notifyFailures: Boolean = true) {
            lastResult = e.message ?: context.getString(R.string.runtime_failed)
            if (notifyFailures) Notifications.showError(context, context.getString(R.string.runtime_failed), lastResult.orEmpty())
        }
        fun pendingIntent(context: Context, command: NightActions.Command, profileId: String? = null): PendingIntent {
            val captured = intent(context, command, profileId, requireIdentity = true)
            val key = "${command.name}:$profileId:${captured.getStringExtra(NightAlarms.EXTRA_NIGHT)}:${captured.getIntExtra(NightAlarms.EXTRA_REVISION, -1)}"
            return PendingIntent.getForegroundService(context, key.hashCode(), captured,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
    }
}
