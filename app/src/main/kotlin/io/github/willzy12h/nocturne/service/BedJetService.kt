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
import io.github.willzy12h.nocturne.core.night.Confidence
import io.github.willzy12h.nocturne.core.store.deviceId
import io.github.willzy12h.nocturne.night.*
import io.github.willzy12h.nocturne.notify.Notifications
import io.github.willzy12h.nocturne.platform.PlatformActionActivity
import io.github.willzy12h.nocturne.ui.ShortcutActivity
import kotlinx.coroutines.*
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Owns the entire bounded operation, including waiting for the coordinator and connecting. */
class BedJetService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pending = 0
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val handoff = intent?.getStringExtra(EXTRA_HANDOFF)?.let { handoffs.remove(it) }
        val request = intent?.let(::request)
        val notifyFailures = handoff?.notifyFailures ?: intent?.getBooleanExtra(EXTRA_NOTIFY_FAILURES, true) ?: true
        if (request == null && handoff == null) { if (pending == 0) stopSelf(startId); return START_NOT_STICKY }
        try {
            if (!permitted(this, request?.command ?: NightActions.Command.START_NIGHT)) throw SecurityException(getString(R.string.runtime_connect_permission))
            val local = graph.repository.settings.value.demoMode || request?.command == NightActions.Command.STOP_TRACKING
            val type = if (!local) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                else if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE else 0
            startForeground(Notifications.WORKING_ID, Notifications.working(this), type)
        } catch (e: RuntimeException) {
            handoff?.result?.completeExceptionally(e)
            failure(this, e, notifyFailures); if (pending == 0) stopSelf(startId); return START_NOT_STICKY
        }
        pending++
        val retimeExecution = RetimeExecution()
        val operation = scope.launch {
            try {
                if (handoff != null) {
                    if (!handoff.result.isActive) return@launch
                    val value = withTimeout(SERVICE_MS) {
                        withContext(SubmissionTarget(handoff.target) + CommandOriginContext(handoff.origin) +
                            (handoff.feedback ?: kotlin.coroutines.EmptyCoroutineContext)) { handoff.work() }
                    }
                    handoff.result.complete(value)
                    return@launch
                }
                val result = withTimeout(SERVICE_MS) { withContext(retimeExecution) { graph.actions.execute(request!!) } }
                lastResult = result.message
                // A stale re-time retry only discards obsolete work. Other stale commands still report their failure.
                if (!result.succeeded && notifyFailures && !result.notificationHandled && retimeExecution.reportedNotice == null &&
                    !(request?.command == NightActions.Command.RETRY_RETIME && result.kind == OutcomeKind.STALE))
                    Notifications.showError(this@BedJetService, getString(R.string.runtime_failed), result.message)
            } catch (e: CancellationException) {
                handoff?.result?.cancel(e)
                if (e is TimeoutCancellationException) {
                    if (retimeExecution.reportedNotice != null) lastResult = retimeExecution.reportedNotice
                    else if (handoff == null && request?.command == NightActions.Command.RETRY_RETIME) {
                        val expected = retimeExecution.identity ?: request.expected
                        val sameNight = expected == graph.repository.activeNight.value?.identity &&
                            request.deviceId == graph.repository.settings.value.deviceId()
                        val quiet = if (sameNight) graph.actions.quietRetimeTimeout(expected, request.deviceId,
                            admitted = retimeExecution.identity != null) else null
                        val current = graph.actions.currentConfirmedProgramme()?.takeIf { sameNight }
                        if (!sameNight) lastResult = getString(R.string.runtime_stale)
                        else if (quiet != null) lastResult = quiet
                        else if (current != null) {
                            // Only obsolete journal cleanup may have timed out, or another owner may have published
                            // this programme. Report current confirmation without attributing it to the timed-out request.
                            val wake = ZonedDateTime.parse(current.wakeAt).format(DateTimeFormatter.ofPattern("HH:mm"))
                            lastResult = getString(R.string.runtime_retime_timeout_current_confirmed)
                            Notifications.showInfo(this@BedJetService, getString(R.string.runtime_retime_timeout_current_wake, wake), lastResult.orEmpty())
                        } else {
                            // A possible write cannot be replayed. Background delivery owns one truthful timeout notice.
                            val detail = getString(R.string.runtime_retime_timeout_detail)
                            lastResult = if (graph.repository.activeNight.value?.confidence == Confidence.UNKNOWN)
                                getString(R.string.runtime_retime_unknown_detail, detail) else detail
                            Notifications.showError(this@BedJetService, getString(R.string.runtime_retime_timeout), lastResult.orEmpty())
                        }
                    } else failure(this@BedJetService, e, notifyFailures)
                }
                throw e
            } catch (e: Exception) { handoff?.result?.completeExceptionally(e); failure(this@BedJetService, e, notifyFailures)
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
        private const val EXTRA_NOTIFY_FAILURES = "notifyFailures"
        /** The service's bound outlasts the owner's own budget, so the owner's cleanup and outcome always finish (IR-11). */
        const val SERVICE_MS = NightActions.OPERATION_MS + 10_000L
        /** The UI waits longer still: foreground promotion, the service bound and a margin. */
        const val RUN_OWNED_MS = SERVICE_MS + 15_000L
        private class Handoff(val target: String?, val notifyFailures: Boolean, val origin: CommandOrigin,
            val feedback: CommandFeedback?, val work: suspend () -> Any?) {
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
            val origin = entryOrigin(context, currentCoroutineContext()[CommandOriginContext]?.origin ?: CommandOrigin.APP)
            val submitted = currentCoroutineContext()[SubmissionTarget]
            val target = if (submitted != null) submitted.deviceId else context.graph.repository.settings.value.deviceId()
            if (context.graph.repository.settings.value.demoMode)
                return withContext(SubmissionTarget(target) + CommandOriginContext(origin)) { withTimeout(SERVICE_MS) { work() } }
            val id = java.util.UUID.randomUUID().toString()
            val handoff = Handoff(target, notifyFailures, origin, currentCoroutineContext()[CommandFeedback], work)
            handoffs[id] = handoff
            try {
                if (!permitted(context, NightActions.Command.START_NIGHT)) throw SecurityException(context.getString(R.string.runtime_connect_permission))
                deliver(context, Intent(context, BedJetService::class.java).putExtra(EXTRA_HANDOFF, id))
                return withTimeout(RUN_OWNED_MS) { handoff.result.await() } as T
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
        private const val EXTRA_ORIGIN = "commandOrigin"
        @Volatile var lastResult: String? = null
        fun intent(context: Context, command: NightActions.Command, profileId: String? = null, requireIdentity: Boolean = false, replacementConfirmed: Boolean = false): Intent {
            val captured = context.graph.actions.capture(command, profileId, requireIdentity)
            return intent(context, captured.copy(replacementConfirmed = replacementConfirmed, origin = entryOrigin(context, captured.origin)))
        }
        private fun intent(context: Context, captured: CommandRequest) =
            Intent(context, BedJetService::class.java).putExtra(EXTRA_COMMAND, captured.command.name)
                .putExtra(EXTRA_PROFILE, captured.profileId).putExtra(EXTRA_TARGET, captured.deviceId)
                .putExtra(EXTRA_CONFIRMED, captured.replacementConfirmed).putExtra(EXTRA_STRICT, captured.requireIdentity)
                .putExtra(EXTRA_ORIGIN, captured.origin.name)
                .putExtra(NightAlarms.EXTRA_NIGHT, captured.expected?.nightId)
                .putExtra(NightAlarms.EXTRA_REVISION, captured.expected?.revision ?: -1)
        private fun request(intent: Intent): CommandRequest? {
            val command = intent.getStringExtra(EXTRA_COMMAND)?.let { runCatching { NightActions.Command.valueOf(it) }.getOrNull() } ?: return null
            val origin = intent.getStringExtra(EXTRA_ORIGIN)?.let { runCatching { CommandOrigin.valueOf(it) }.getOrNull() }
                ?: defaultCommandOrigin(command)
            return CommandRequest(command, intent.getStringExtra(EXTRA_PROFILE), intent.getStringExtra(EXTRA_TARGET),
                NightAlarms.identity(intent), intent.getBooleanExtra(EXTRA_STRICT, false), intent.getBooleanExtra(EXTRA_CONFIRMED, false), origin)
        }
        /** False means no service owns the work. Receivers may only record/schedule a pending retry. */
        fun start(context: Context, command: NightActions.Command, profileId: String? = null,
            replacementConfirmed: Boolean = false, notifyFailures: Boolean = true): Boolean =
            start(context, context.graph.actions.capture(command, profileId, requireIdentity = replacementConfirmed)
                .copy(replacementConfirmed = replacementConfirmed), notifyFailures)
        fun start(context: Context, captured: CommandRequest, notifyFailures: Boolean = true): Boolean = try {
            if (!permitted(context, captured.command)) throw SecurityException(context.getString(R.string.runtime_connect_permission))
            val request = captured.copy(origin = entryOrigin(context, captured.origin))
            deliver(context, intent(context, request).putExtra(EXTRA_NOTIFY_FAILURES, notifyFailures)); true
        } catch (e: RuntimeException) { failure(context, e, notifyFailures); false }
        /** Ordinary delivery creates no foreground-start obligation if the handoff is missing or promotion is denied.
         * Valid work is promoted synchronously in onStartCommand. Android may refuse this background start earlier. */
        private fun deliver(context: Context, intent: Intent) {
            checkNotNull(context.startService(intent)) { context.getString(R.string.runtime_failed) }
        }
        private fun permitted(context: Context, command: NightActions.Command) =
            context.graph.repository.settings.value.demoMode || command == NightActions.Command.STOP_TRACKING ||
                context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        private fun failure(context: Context, e: Exception, notifyFailures: Boolean = true) {
            lastResult = if (e is TimeoutCancellationException) context.getString(R.string.runtime_operation_timeout)
                else e.message ?: context.getString(R.string.runtime_failed)
            if (notifyFailures) Notifications.showError(context, context.getString(R.string.runtime_failed), lastResult.orEmpty())
        }
        fun pendingIntent(context: Context, command: NightActions.Command, profileId: String? = null): PendingIntent {
            val captured = intent(context, command, profileId, requireIdentity = true).putExtra(EXTRA_ORIGIN, CommandOrigin.NOTIFICATION.name)
            val key = "${command.name}:$profileId:${captured.getStringExtra(NightAlarms.EXTRA_NIGHT)}:${captured.getIntExtra(NightAlarms.EXTRA_REVISION, -1)}"
            return PendingIntent.getService(context, key.hashCode(), captured,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        /** Existing activity entries are identified here, before work crosses the service boundary. */
        private fun entryOrigin(context: Context, fallback: CommandOrigin): CommandOrigin = when (context) {
            is PlatformActionActivity -> {
                val intent = context.intent
                if (intent == null) fallback
                else if (intent.getBooleanExtra("rendered", false) ||
                    intent.data?.let { it.scheme == "nocturne" && it.host == "widget" } == true) CommandOrigin.WIDGET
                else CommandOrigin.REMINDER
            }
            is ShortcutActivity -> if (context.intent?.getBooleanExtra("capturedConfirmation", false) == true)
                CommandOrigin.NOTIFICATION else CommandOrigin.SHORTCUT
            else -> fallback
        }
    }
}
