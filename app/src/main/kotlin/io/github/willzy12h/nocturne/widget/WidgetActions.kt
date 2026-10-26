package io.github.willzy12h.nocturne.widget

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.state.updateAppWidgetState
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.night.ActiveNight
import io.github.willzy12h.nocturne.core.night.NightIdentity
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.store.ActionPreset
import io.github.willzy12h.nocturne.core.store.BackupCodec
import io.github.willzy12h.nocturne.core.store.deviceId
import io.github.willzy12h.nocturne.graph
import io.github.willzy12h.nocturne.night.CommandOrigin
import io.github.willzy12h.nocturne.night.CommandOriginContext
import io.github.willzy12h.nocturne.night.CommandOutcome
import io.github.willzy12h.nocturne.night.CommandRequest
import io.github.willzy12h.nocturne.night.NightActions.Command
import io.github.willzy12h.nocturne.night.OutcomeKind
import io.github.willzy12h.nocturne.night.ReplacementPolicy
import io.github.willzy12h.nocturne.night.SubmissionTarget
import io.github.willzy12h.nocturne.night.compactBoostLabel
import io.github.willzy12h.nocturne.night.usesCapturedPreset
import io.github.willzy12h.nocturne.night.validCapturedPreset
import io.github.willzy12h.nocturne.service.BedJetService
import kotlinx.coroutines.*
import java.util.UUID

internal fun widgetParameters(request: CommandRequest, kind: String) = actionParametersOf(
    WidgetActionCallback.COMMAND to request.command.name,
    WidgetActionCallback.PROFILE to request.profileId.orEmpty(),
    WidgetActionCallback.TARGET to request.deviceId.orEmpty(),
    WidgetActionCallback.NIGHT to request.expected?.nightId.orEmpty(),
    WidgetActionCallback.REVISION to request.expected?.revision?.toString().orEmpty(),
    WidgetActionCallback.PRESET to request.actionPreset?.let { BackupCodec.json.encodeToString(ActionPreset.serializer(), it) }.orEmpty(),
    WidgetActionCallback.KIND to kind,
)

internal fun widgetRequest(parameters: ActionParameters): CommandRequest? {
    val command = parameters[WidgetActionCallback.COMMAND]?.let { runCatching { Command.valueOf(it) }.getOrNull() } ?: return null
    val nightId = parameters[WidgetActionCallback.NIGHT]?.takeIf { it.isNotEmpty() }
    val expected = if (nightId != null) NightIdentity(nightId,
        parameters[WidgetActionCallback.REVISION]?.toIntOrNull() ?: return null) else null
    val target = parameters[WidgetActionCallback.TARGET]?.takeIf { it.isNotEmpty() }
    val encoded = parameters[WidgetActionCallback.PRESET]?.takeIf { it.isNotEmpty() && command.usesCapturedPreset() }
    val preset = if (encoded == null) null else {
        if (encoded.length > 1_024) return null
        runCatching { BackupCodec.json.decodeFromString(ActionPreset.serializer(), encoded) }.getOrNull() ?: return null
    }
    // Quick entries may preview a capped Temporary choice from an imported preset. The owner validates
    // the selected command before writing; a direct Temporary callback must also have a complete capture.
    if (command == Command.TEMPORARY && (preset == null || !validCapturedPreset(command, preset) ||
            expected == null || expected.nightId.isBlank() || expected.revision < 0 || target.isNullOrBlank())) return null
    return CommandRequest(command, parameters[WidgetActionCallback.PROFILE]?.takeIf { it.isNotEmpty() },
        target, expected, requireIdentity = true, origin = CommandOrigin.WIDGET, actionPreset = preset)
}

internal fun widgetNeedsConfirmation(request: CommandRequest, active: ActiveNight?): Boolean =
    active != null && request.command in setOf(Command.OFF, Command.END_NIGHT, Command.STOP_TRACKING) ||
        ReplacementPolicy.decide(request.command, active).needsConfirmation

internal suspend fun refreshWidget(context: Context, id: GlanceId, kind: String) {
    try {
        if (kind == WidgetActionCallback.SINGLE) SingleActionWidget().update(context, id)
        else QuickControlsWidget().update(context, id)
    } catch (cancelled: CancellationException) { throw cancelled
    } catch (failure: Exception) {
        // The stored state is still truthful; a later host/data event can render it if this refresh failed.
        Log.w("NocturneWidget", "Unable to redraw widget", failure)
    }
}

class WidgetPageCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val page = parameters[PAGE] ?: return
        if (widgetPages(context, 1).none { it.key == page }) return
        updateAppWidgetState(context, glanceId) { it[QuickControlsWidget.KEY_PAGE] = page }
        refreshWidget(context, glanceId, WidgetActionCallback.QUICK)
    }
    companion object { val PAGE = ActionParameters.Key<String>("widget_page") }
}

/** A callback never starts an activity. Confirmation controls use their own direct Activity PendingIntent. */
class WidgetActionCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val request = widgetRequest(parameters) ?: return
        WidgetDelivery.submit(context.applicationContext, glanceId, parameters[KIND] ?: QUICK, request)
    }
    companion object {
        const val QUICK = "quick"
        const val SINGLE = "single"
        val COMMAND = ActionParameters.Key<String>("widget_command")
        val PROFILE = ActionParameters.Key<String>("widget_profile")
        val TARGET = ActionParameters.Key<String>("widget_target")
        val NIGHT = ActionParameters.Key<String>("widget_night")
        val REVISION = ActionParameters.Key<String>("widget_revision")
        val PRESET = ActionParameters.Key<String>("widget_preset")
        val KIND = ActionParameters.Key<String>("widget_kind")
    }
}

internal enum class WidgetFeedbackState(val title: Int) {
    PENDING(R.string.widget_pending), CONFIRMED(R.string.widget_confirmed),
    FAILED(R.string.widget_failed), UNKNOWN(R.string.feature_unknown), LOCAL(R.string.widget_local),
}
internal data class WidgetResult(val state: WidgetFeedbackState, val message: String, val action: String)
internal data class WidgetRequestStamp(val id: String, val target: String?, val deadline: Long, val action: String)

/** Per-widget presentation only. Receipt decisions remain with NightActions, never with a widget snapshot. */
internal object WidgetFeedback {
    private val process = UUID.randomUUID().toString()
    private val STATE = stringPreferencesKey("widget_feedback_state")
    private val REQUEST = stringPreferencesKey("widget_feedback_request")
    private val TARGET = stringPreferencesKey("widget_feedback_target")
    private val PROCESS = stringPreferencesKey("widget_feedback_process")
    private val DEADLINE = longPreferencesKey("widget_feedback_deadline")
    private val MESSAGE = stringPreferencesKey("widget_feedback_message")
    private val ACTION = stringPreferencesKey("widget_feedback_action")

    fun visible(context: Context, prefs: Preferences): WidgetResult? {
        var state = prefs[STATE]?.let { runCatching { WidgetFeedbackState.valueOf(it) }.getOrNull() } ?: return null
        if (prefs[TARGET] != context.graph.repository.settings.value.deviceId().orEmpty()) return null
        var message = prefs[MESSAGE].orEmpty()
        if (state == WidgetFeedbackState.PENDING && (prefs[PROCESS] != process ||
            (prefs[DEADLINE] ?: 0L) <= context.graph.now().toInstant().toEpochMilli())) {
            state = WidgetFeedbackState.UNKNOWN
            message = context.getString(R.string.widget_interrupted)
        }
        return WidgetResult(state, message, prefs[ACTION].orEmpty())
    }

    fun stamp(context: Context, request: CommandRequest) = WidgetRequestStamp(UUID.randomUUID().toString(), request.deviceId,
        context.graph.now().toInstant().toEpochMilli() + BedJetService.RUN_OWNED_MS, widgetActionLabel(context, request))

    suspend fun pending(context: Context, id: GlanceId, kind: String, stamp: WidgetRequestStamp) {
        updateAppWidgetState(context, id) { prefs ->
            prefs[REQUEST] = stamp.id
            prefs[TARGET] = stamp.target.orEmpty()
            prefs[PROCESS] = process
            prefs[DEADLINE] = stamp.deadline
            prefs[ACTION] = stamp.action
            prefs[STATE] = WidgetFeedbackState.PENDING.name
            prefs[MESSAGE] = context.getString(R.string.widget_sending, stamp.action)
        }
        refreshWidget(context, id, kind)
    }

    /** A previous waiter cannot replace the newer tap's Pending or result. */
    suspend fun finish(context: Context, id: GlanceId, kind: String, stamp: WidgetRequestStamp, outcome: CommandOutcome, onlyPending: Boolean = false): Boolean = try {
        var changed = false
        updateAppWidgetState(context, id) { prefs ->
            if (prefs[REQUEST] == stamp.id && prefs[TARGET] == stamp.target.orEmpty() &&
                (!onlyPending || prefs[STATE] == WidgetFeedbackState.PENDING.name)) {
                prefs[STATE] = when (outcome.kind) {
                    OutcomeKind.CONFIRMED -> WidgetFeedbackState.CONFIRMED
                    OutcomeKind.LOCAL -> WidgetFeedbackState.LOCAL
                    OutcomeKind.ACCEPTED, OutcomeKind.UNKNOWN -> WidgetFeedbackState.UNKNOWN
                    else -> WidgetFeedbackState.FAILED
                }.name
                prefs[MESSAGE] = outcome.message
                changed = true
            }
        }
        if (changed) refreshWidget(context, id, kind)
        true
    } catch (cancelled: CancellationException) { throw cancelled
    } catch (failure: Exception) {
        Log.w("NocturneWidget", "Unable to save widget outcome", failure)
        false
    }
}

internal fun widgetActionLabel(context: Context, request: CommandRequest): String {
    // Feedback for an approved programme describes the rendered request, not a later Settings edit.
    request.actionPreset?.let { preset ->
        when (request.command) {
            Command.BOOST -> return compactBoostLabel(context, preset.minutes)
            Command.TURBO -> return context.getString(R.string.widget_turbo_duration, preset.minutes)
            else -> Unit
        }
    }
    return widgetActions(context).firstOrNull { it.command == request.command && it.profileId == request.profileId }?.label
        ?: configuredWidgetAction(context, request.command,
            io.github.willzy12h.nocturne.night.NightActions.commandLabel(context, request.command), request.profileId).label
}

/** The application job waits for feedback; real hardware work is handed to the existing bounded service. */
internal object WidgetDelivery {
    suspend fun submit(context: Context, id: GlanceId, kind: String, captured: CommandRequest): Job {
        // A dynamic Start selects tonight at the tap; a later change of selection cannot change an open approval.
        val request = if (captured.command == Command.START_NIGHT && captured.profileId == null)
            captured.copy(profileId = context.graph.repository.tonightsProfile()?.id) else captured
        val stamp = WidgetFeedback.stamp(context, request)
        try {
            WidgetFeedback.pending(context, id, kind, stamp)
            currentCoroutineContext().ensureActive()
            return context.graph.scope.launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                deliver(context, id, kind, request, stamp)
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { WidgetFeedback.finish(context, id, kind, stamp, interrupted(context)) }
            throw cancelled
        }
    }

    private suspend fun deliver(context: Context, id: GlanceId, kind: String, request: CommandRequest, stamp: WidgetRequestStamp) = coroutineScope {
        var settled = false
        var result: CommandOutcome? = null
        // Only a live request has a deadline job. No timer remains after its result is durably published.
        val deadline = launch {
            delay((stamp.deadline - context.graph.now().toInstant().toEpochMilli()).coerceAtLeast(0))
            WidgetFeedback.finish(context, id, kind, stamp, interrupted(context), onlyPending = true)
        }
        try {
            result = try {
                withContext(CommandOriginContext(CommandOrigin.WIDGET) + SubmissionTarget(request.deviceId)) {
                    BedJetService.runOwned(context, notifyFailures = false, hardware = request.command != Command.STOP_TRACKING) {
                        if (request.expected != null && !request.replacementConfirmed &&
                            request.command in setOf(Command.OFF, Command.END_NIGHT, Command.STOP_TRACKING)) {
                            CommandOutcome(OutcomeKind.CONFIRMATION_REQUIRED, context.getString(R.string.widget_confirmation_needed), origin = CommandOrigin.WIDGET)
                        } else if (request.command == Command.OFF || request.command == Command.END_NIGHT) {
                            // Generic emergency Off tolerates old night identities. A widget tap must not end a
                            // different night without its confirmation; remote checks this under the same mutex.
                            context.graph.actions.remote(context.getString(R.string.runtime_off), endsNight = true,
                                target = request.deviceId, expected = request.expected, confirms = { it.mode == Mode.STANDBY }) { it.stop() }
                        } else context.graph.actions.execute(request)
                    }
                }
            } catch (timeout: TimeoutCancellationException) {
                CommandOutcome(OutcomeKind.UNKNOWN, context.getString(R.string.widget_timed_out), origin = CommandOrigin.WIDGET)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: Exception) {
                CommandOutcome(OutcomeKind.FAILED, failure.message ?: context.getString(R.string.widget_failed_message), origin = CommandOrigin.WIDGET)
            }
            settled = WidgetFeedback.finish(context, id, kind, stamp, result)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                settled = withTimeoutOrNull(3_000) {
                    WidgetFeedback.finish(context, id, kind, stamp, result ?: interrupted(context))
                } ?: false
            }
            throw cancelled
        } finally {
            if (settled || !currentCoroutineContext().isActive) withContext(NonCancellable) { deadline.cancelAndJoin() }
        }
    }

    private fun interrupted(context: Context) = CommandOutcome(OutcomeKind.UNKNOWN,
        context.getString(R.string.widget_interrupted), origin = CommandOrigin.WIDGET)
}
