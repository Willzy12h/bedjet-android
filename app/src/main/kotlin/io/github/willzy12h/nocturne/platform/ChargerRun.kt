package io.github.willzy12h.nocturne.platform

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.link.latestStatusFrom
import io.github.willzy12h.nocturne.core.night.ChangeKind
import io.github.willzy12h.nocturne.core.night.Confidence
import io.github.willzy12h.nocturne.core.night.PreparedChange
import io.github.willzy12h.nocturne.core.night.WakeOrigin
import io.github.willzy12h.nocturne.core.plan.NightPreview
import io.github.willzy12h.nocturne.core.plan.Profile
import io.github.willzy12h.nocturne.core.plan.TonightOverride
import io.github.willzy12h.nocturne.core.plan.WakeChoice
import io.github.willzy12h.nocturne.core.store.LoggedNotice
import io.github.willzy12h.nocturne.core.store.NoticeKind
import io.github.willzy12h.nocturne.core.store.deviceId
import io.github.willzy12h.nocturne.night.AlarmSource
import io.github.willzy12h.nocturne.night.CommandOutcome
import io.github.willzy12h.nocturne.night.CommandOrigin
import io.github.willzy12h.nocturne.night.NightActions.Command
import io.github.willzy12h.nocturne.night.OutcomeKind
import io.github.willzy12h.nocturne.notify.Notifications
import io.github.willzy12h.nocturne.service.BedJetService
import io.github.willzy12h.nocturne.ui.MainActivity
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.time.Duration

/** One bounded JobService child; the optional charging grace never becomes a polling loop. */
class ChargerRun(private val graph: Graph, private val charging: () -> Boolean,
    private val notify: (String, String) -> Boolean, private val grace: suspend () -> Unit,
    private val permitted: () -> Boolean, private val ticket: AutoStartTicket? = null) {
    private data class Candidate(val profile: Profile, val device: String, val window: BedtimeWindow,
        val automatic: AutoStartPlan? = null) {
        val key get() = automatic?.key ?: window.key(device)
        val requiresCharging get() = automatic?.settings?.onlyIfCharging ?: true
    }
    private fun candidate(): Candidate? {
        val repo = graph.repository; val now = graph.now(); val settings = repo.settings.value
        if (ticket != null || settings.platform.autoStart != null) {
            val plan = AutoStartSchedule.due(graph, ticket) ?: return null
            return Candidate(plan.profile, plan.settings.deviceId!!,
                BedtimeWindow(plan.startAt, plan.startAt, plan.startAt.plusMinutes(15)), plan)
        }
        // Read bridge for old settings while startup migration is pending. All new schedules use a ticket.
        return repo.profiles.value.mapNotNull { profile -> PlatformPolicy.window(profile.bedtimeMinute, now)?.let { window ->
            if (settings.resolveProfile(repo.profiles.value, window.bedtime)?.id == profile.id)
                Candidate(profile, settings.deviceId() ?: return@mapNotNull null, window) else null
        } }.firstOrNull()
    }
    private fun eligible(candidate: Candidate, ownAttempt: Boolean = false, requireReadableData: Boolean = true): Boolean {
        val repo = graph.repository; val settings = repo.settings.value; val now = graph.now()
        val current = candidate() ?: return false
        if (current.key != candidate.key || current.profile != candidate.profile ||
            current.automatic?.fingerprint != candidate.automatic?.fingerprint || settings.deviceId() != candidate.device) return false
        val alarm = AlarmSource.describe(graph.context, settings)
        val override = settings.resolveWakeOverride(now) ?: candidate.automatic?.wakeAt?.let { TonightOverride(it.toString(), now.toString()) }
        val preview = NightPreview.forStartNow(candidate.profile, now, alarm?.time, override, settings, alarm?.creator)
        val claims = repo.automation.value.claims
        val active = repo.activeNight.value
        val ownPendingStart = ownAttempt && active?.pending?.kind == ChangeKind.START &&
            active.pending?.intended?.profile == candidate.profile && active.deviceId == candidate.device
        val selected = !settings.demoMode && settings.targetDevice == candidate.device &&
            (candidate.automatic?.settings?.deviceId ?: settings.platform.chargerDevice) == candidate.device
        val minutes = preview.plan?.let { Duration.between(it.start, it.wake).toMinutes() } ?: 0
        return (candidate.automatic?.settings?.enabled ?: settings.platform.chargerAutoStart) &&
            (!candidate.requiresCharging || charging()) && selected && preview.plan != null &&
            minutes >= (if (candidate.automatic == null) 180 else 60) && candidate.window.contains(now) &&
            (active == null || ownPendingStart) && graph.link.latestStatusFrom(candidate.device)?.isRunning != true &&
            repo.runtime.value.unresolvedSlotWrite?.let { it.deviceId == candidate.device && it.affectsNights(settings.workingSlot) } != true &&
            !graph.sessions.released.value && permitted() && (!requireReadableData || repo.unreadable.value.isEmpty()) &&
            settings.platform.validationErrors().isEmpty() && "cancel:${candidate.key}" !in claims &&
            (ownAttempt || "charger:${candidate.key}" !in claims)
    }
    suspend fun execute() {
        val candidate = candidate()
        if (reportUnreadable(candidate)) return
        if (candidate == null) return
        // A system reschedule must preserve the terminal result of the one already-claimed attempt.
        if ("charger:${candidate.key}" in graph.repository.automation.value.claims) return
        if (!eligible(candidate)) { reportSkip(candidate); return }
        if (candidate.requiresCharging) {
            if (!notify(candidate.profile.name, candidate.key)) return
            grace()
            currentCoroutineContext().ensureActive()
            if (reportUnreadable(candidate)) return
            if (!eligible(candidate)) { reportSkip(candidate); return }
        }
        if (!graph.repository.claimAutomation("charger:${candidate.key}")) return
        val request = graph.actions.capture(Command.START_NIGHT, candidate.profile.id, requireIdentity = true)
        val capturedWake = candidate.automatic?.wakeAt?.let {
            WakeChoice(it, candidate.automatic.wakeSource == AutoStartWakeSource.ALARM, WakeOrigin.AUTO_START)
        }
        var prepared: PreparedChange? = null
        val outcome = try {
            BedJetService.runJobOwned(graph) {
                graph.actions.performJobOwned(request, automaticWake = capturedWake,
                    stillEligible = { candidate.automatic == null || eligible(candidate, ownAttempt = true) },
                    onPrepared = { prepared = it }) { fresh ->
                    currentCoroutineContext().ensureActive()
                    !fresh.isRunning && fresh.extra?.programStep == 0 && eligible(candidate, ownAttempt = true)
                }
            }
        } catch (e: CancellationException) {
            val attempt = prepared
            val current = graph.repository.activeNight.value
            val kind = when {
                attempt != null && current != null && current == attempt.intended && current.confidence == Confidence.CONFIRMED -> OutcomeKind.CONFIRMED
                attempt != null && current != null && current.identity == attempt.pendingNight.identity &&
                    current.deviceId == candidate.device && current.profileId == candidate.profile.id &&
                    current.pending?.requestId == attempt.request.requestId &&
                    current.confidence in setOf(Confidence.PENDING, Confidence.UNKNOWN) -> OutcomeKind.UNKNOWN
                else -> OutcomeKind.FAILED
            }
            val detail = graph.context.getString(when (kind) {
                OutcomeKind.CONFIRMED -> R.string.auto_start_confirmed_interrupted
                OutcomeKind.UNKNOWN -> R.string.runtime_interrupted
                else -> R.string.auto_start_interrupted
            })
            try {
                withContext(NonCancellable) { withTimeout(8_000) {
                    record(candidate, CommandOutcome(kind, detail, origin = CommandOrigin.CHARGER))
                } }
            } catch (cleanup: Exception) { if (cleanup !== e) e.addSuppressed(cleanup) }
            throw e
        }
        // A claimed attempt cannot be replayed after an accepted partial write or a lost acknowledgement.
        record(candidate, outcome)
    }

    private suspend fun reportSkip(candidate: Candidate) {
        if (candidate.automatic == null || graph.repository.settings.value.demoMode ||
            !graph.repository.claimAutomation("auto-skip:${candidate.key}")) return
        val reason = when {
            graph.repository.activeNight.value != null || graph.link.latestStatusFrom(candidate.device)?.isRunning == true -> R.string.auto_start_skip_running
            candidate.requiresCharging && !charging() -> R.string.auto_start_skip_charging
            !permitted() -> R.string.auto_start_skip_permission
            else -> R.string.auto_start_skip_changed
        }
        record(candidate, CommandOutcome(OutcomeKind.REFUSED, graph.context.getString(reason), origin = CommandOrigin.CHARGER))
    }

    /** An otherwise eligible saved-data refusal creates a notice, never a History night. */
    private suspend fun reportUnreadable(candidate: Candidate?): Boolean {
        val repo = graph.repository
        if (repo.unreadable.value.isEmpty()) return false
        if (candidate == null || !eligible(candidate, requireReadableData = false)) return true
        if (!repo.claimAutomation("charger-unreadable:${candidate.key}")) return true
        val title = graph.context.getString(if (candidate.automatic == null) R.string.feature_charger_outcome else R.string.auto_start_outcome)
        val message = graph.context.getString(if (candidate.automatic == null) R.string.runtime_charger_saved_data_unreadable else R.string.auto_start_skip_unreadable)
        repo.addNotice(LoggedNotice(graph.now().toString(), NoticeKind.PROBLEM, title, message))
        if (Notifications.canPost(graph.context)) {
            val notification = Notification.Builder(graph.context, "alerts")
                .setSmallIcon(R.drawable.ic_notification).setContentTitle(title).setContentText(message)
                .setStyle(Notification.BigTextStyle().bigText(message))
                .setContentIntent(PendingIntent.getActivity(graph.context, 0,
                    Intent(graph.context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)).setAutoCancel(true).build()
            graph.context.getSystemService(NotificationManager::class.java).notify(3, notification)
        }
        return true
    }
    private suspend fun record(candidate: Candidate, outcome: CommandOutcome) {
        val automatic = candidate.automatic != null
        val title = graph.context.getString(if (automatic) R.string.auto_start_outcome else R.string.feature_charger_outcome)
        val message = if (!automatic) outcome.message else graph.context.getString(when (outcome.kind) {
            OutcomeKind.CONFIRMED -> R.string.auto_start_result_confirmed
            OutcomeKind.UNKNOWN, OutcomeKind.ACCEPTED -> R.string.auto_start_result_unknown
            OutcomeKind.REFUSED, OutcomeKind.STALE, OutcomeKind.CONFIRMATION_REQUIRED, OutcomeKind.LOCAL -> R.string.auto_start_result_skipped
            else -> R.string.auto_start_result_failed
        }, outcome.message)
        try {
            graph.repository.addNotice(LoggedNotice(graph.now().toString(),
                if (outcome.kind == OutcomeKind.CONFIRMED) NoticeKind.INFO else NoticeKind.PROBLEM, title, message))
        } finally {
            if (automatic && !outcome.notificationHandled) PlatformNotifications.outcome(graph.context, title, message)
        }
    }
}
