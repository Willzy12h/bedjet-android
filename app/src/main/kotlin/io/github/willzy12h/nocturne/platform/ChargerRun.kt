package io.github.willzy12h.nocturne.platform

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.core.link.latestStatusFrom
import io.github.willzy12h.nocturne.core.plan.NightPreview
import io.github.willzy12h.nocturne.core.plan.Profile
import io.github.willzy12h.nocturne.core.store.deviceId
import io.github.willzy12h.nocturne.night.AlarmSource
import io.github.willzy12h.nocturne.night.NightActions.Command
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.store.LoggedNotice
import io.github.willzy12h.nocturne.core.store.NoticeKind
import io.github.willzy12h.nocturne.night.CommandOutcome
import io.github.willzy12h.nocturne.night.CommandOrigin
import io.github.willzy12h.nocturne.night.OutcomeKind
import io.github.willzy12h.nocturne.notify.Notifications
import io.github.willzy12h.nocturne.ui.MainActivity
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withTimeout
import java.time.Duration

/** All work remains a child of the JobService, including grace and the coordinator's owned connection. */
class ChargerRun(private val graph: Graph, private val charging: () -> Boolean,
    private val notify: (String,String) -> Boolean, private val grace: suspend () -> Unit,
    private val permitted: () -> Boolean) {
    private data class Candidate(val profile: Profile, val device: String, val window: BedtimeWindow) {
        val key get() = window.key(device)
    }
    private fun candidate(): Candidate? {
        val repo=graph.repository; val now=graph.now(); val settings=repo.settings.value
        // Resolve weekday defaults at the BEDTIME date, not the next calendar day after midnight.
        return repo.profiles.value.mapNotNull { p -> PlatformPolicy.window(p.bedtimeMinute,now)?.let { window ->
            if (settings.resolveProfile(repo.profiles.value,window.bedtime)?.id == p.id)
                Candidate(p,settings.deviceId() ?: return@mapNotNull null,window) else null
        } }.firstOrNull()
    }
    private fun eligible(c: Candidate, ownAttempt: Boolean = false, requireReadableData: Boolean = true): Boolean {
        val repo=graph.repository; val settings=repo.settings.value; val now=graph.now()
        val current=candidate() ?: return false
        if (current.key != c.key || current.profile != c.profile || settings.deviceId() != c.device) return false
        val alarm=AlarmSource.describe(graph.context,settings)
        val preview=NightPreview.forStartNow(c.profile,now,alarm?.time,settings.resolveWakeOverride(now),settings,alarm?.creator)
        val claims=repo.automation.value.claims
        return PlatformPolicy.eligible(ChargerEligibility(
            enabled=settings.platform.chargerAutoStart, charging=charging(),
            selected=!settings.demoMode && settings.targetDevice == c.device && settings.platform.chargerDevice == c.device,
            validPlan=preview.plan != null, minutes=preview.plan?.let { Duration.between(it.start,it.wake).toMinutes() } ?: 0,
            inWindow=c.window.contains(now), active=repo.activeNight.value != null,
            // Only the chosen BedJet's own report counts: one from a session still on another unit says nothing.
            manual=graph.link.latestStatusFrom(c.device)?.isRunning == true,
            // IR-06: only a write still in question on this BedJet holds the start back.
            uncertain=repo.runtime.value.unresolvedSlotWrite?.let { it.deviceId == c.device && it.affectsNights(settings.workingSlot) } == true,
            takeover=graph.sessions.released.value, prerequisites=permitted() &&
                (!requireReadableData || repo.unreadable.value.isEmpty()) && settings.platform.validationErrors().isEmpty(),
            alreadyAttempted="cancel:${c.key}" in claims || (!ownAttempt && "charger:${c.key}" in claims)))
    }
    suspend fun execute() {
        val candidate=candidate()
        if (reportUnreadable(candidate)) return
        if (candidate == null || !eligible(candidate)) return
        if (!notify(candidate.profile.name,candidate.key)) return
        grace() // Cancellation never continues this countdown; a later run starts from the beginning.
        currentCoroutineContext().ensureActive()
        if (reportUnreadable(candidate) || !eligible(candidate)) return
        if (!graph.repository.claimAutomation("charger:${candidate.key}")) return
        val request=graph.actions.capture(Command.START_NIGHT,candidate.profile.id,requireIdentity=true)
        val outcome=try {
            withTimeout(120_000) {
                graph.actions.performJobOwned(request) { fresh ->
                    currentCoroutineContext().ensureActive()
                    !fresh.isRunning && fresh.extra?.programStep == 0 && eligible(candidate,ownAttempt=true)
                }
            }
        } catch (e: TimeoutCancellationException) {
            withContext(NonCancellable) { record(CommandOutcome(OutcomeKind.FAILED,graph.context.getString(R.string.runtime_interrupted),origin=CommandOrigin.CHARGER)) }
            throw e
        }
        // An attempt stays claimed even if transport fails: an accepted partial write cannot be replayed.
        record(outcome)
    }
    /** Only an otherwise eligible start earns a notice; a skipped start never creates a History night. */
    private suspend fun reportUnreadable(candidate: Candidate?): Boolean {
        val repo = graph.repository
        if (repo.unreadable.value.isEmpty()) return false
        if (candidate == null || !eligible(candidate, requireReadableData = false)) return true
        if (!repo.claimAutomation("charger-unreadable:${candidate.key}")) return true
        val title = graph.context.getString(R.string.feature_charger_outcome)
        val message = graph.context.getString(R.string.runtime_charger_saved_data_unreadable)
        repo.addNotice(LoggedNotice(graph.now().toString(), NoticeKind.PROBLEM, title, message))
        if (Notifications.canPost(graph.context)) {
            // showError also logs asynchronously; this job has already saved its single durable notice.
            val notification = Notification.Builder(graph.context, "alerts")
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(Notification.BigTextStyle().bigText(message))
                .setContentIntent(PendingIntent.getActivity(graph.context, 0,
                    Intent(graph.context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
                .setAutoCancel(true)
                .build()
            graph.context.getSystemService(NotificationManager::class.java).notify(3, notification)
        }
        return true
    }
    /** The job has no screen, so what the attempt did is kept in the notification log, not dropped (F04). */
    private suspend fun record(outcome: CommandOutcome) {
        graph.repository.addNotice(LoggedNotice(graph.now().toString(),
            if (outcome.kind == OutcomeKind.CONFIRMED) NoticeKind.INFO else NoticeKind.PROBLEM,
            graph.context.getString(R.string.feature_charger_outcome),outcome.message))
    }
}
