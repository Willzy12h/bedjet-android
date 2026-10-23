package io.github.willzy12h.nocturne.platform

import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.core.link.latestStatusFrom
import io.github.willzy12h.nocturne.core.plan.NightPreview
import io.github.willzy12h.nocturne.core.plan.Profile
import io.github.willzy12h.nocturne.core.store.deviceId
import io.github.willzy12h.nocturne.night.AlarmSource
import io.github.willzy12h.nocturne.night.NightActions.Command
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
    private suspend fun eligible(c: Candidate, ownAttempt: Boolean = false): Boolean {
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
            // IR-06: only a slot write on this BedJet that is still in question; a failure before any write isn't.
            uncertain=repo.runtime.value.unresolvedSlotWrite?.deviceId == c.device,
            takeover=graph.sessions.released.value, prerequisites=permitted() && repo.unreadable.value.isEmpty() && settings.platform.validationErrors().isEmpty(),
            alreadyAttempted="cancel:${c.key}" in claims || (!ownAttempt && "charger:${c.key}" in claims)))
    }
    suspend fun execute() {
        val candidate=candidate() ?: return
        if (!eligible(candidate)) return
        if (!notify(candidate.profile.name,candidate.key)) return
        grace() // Cancellation never continues this countdown; a later run starts from the beginning.
        currentCoroutineContext().ensureActive()
        if (!eligible(candidate)) return
        if (!graph.repository.claimAutomation("charger:${candidate.key}")) return
        val request=graph.actions.capture(Command.START_NIGHT,candidate.profile.id,requireIdentity=true)
        withTimeout(120_000) {
            graph.actions.performJobOwned(request) { fresh ->
                currentCoroutineContext().ensureActive()
                !fresh.isRunning && fresh.extra?.programStep == 0 && eligible(candidate,ownAttempt=true)
            }
        }
        // An attempt stays claimed even if transport fails: an accepted partial write cannot be replayed.
    }
}
