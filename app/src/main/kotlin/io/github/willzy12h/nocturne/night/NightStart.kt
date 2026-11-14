package io.github.willzy12h.nocturne.night

import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.link.*
import io.github.willzy12h.nocturne.core.night.*
import io.github.willzy12h.nocturne.core.plan.*
import io.github.willzy12h.nocturne.core.protocol.*
import io.github.willzy12h.nocturne.core.store.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.ZonedDateTime
import java.time.Duration
import io.github.willzy12h.nocturne.night.NightActions.Command
import io.github.willzy12h.nocturne.night.NightActions.RetimeTakeover

// Part of [NightActions], split out of NightActions.kt: preparing a command under ownership. Start night (including
// auto-start attribution, see AutoStartAttribution.kt), Re-apply, Resume and temporary actions, and the dispatch of
// the manual commands to NightManual.kt.

private val TIMED_MANUAL = setOf(Command.SNOOZE_WARM, Command.SNOOZE_COOL, Command.EXTEND_WARM, Command.EXTEND_COOL)

internal fun NightActions.prepareTemporary(request: CommandRequest): Preparation {
    val preset = request.actionPreset
    if (request.command != Command.TEMPORARY || preset == null || !validCapturedPreset(request.command, preset))
        return Preparation.Refused(text(R.string.runtime_temporary_invalid))
    val night = repo.activeNight.value ?: return Preparation.Refused(text(R.string.runtime_no_night))
    if (request.expected != night.identity || request.deviceId != repo.settings.value.deviceId() || request.deviceId != night.deviceId)
        return Preparation.Stale(request.expected, night.identity)
    if (repo.runtime.value.unresolvedSlotWrite?.let { it.deviceId == night.deviceId && it.displaced == night.identity } == true)
        return Preparation.Refused(text(R.string.runtime_temporary_not_confirmed))
    return graph.runner().prepareTemporary(night, night.identity, preset)
}

/** One stored programme owns the return. There is no later phone timer or detached resume command. */
internal suspend fun NightActions.temporaryLocked(request: CommandRequest): CommandOutcome {
    val preview = prepareTemporary(request)
    if (preview !is Preparation.Ready) return apply(preview, R.string.runtime_temporary_confirmed)
    slotWriteRefusal(Command.TEMPORARY)?.let { return it }
    return connected {
        val night = repo.activeNight.value ?: return@connected refused(R.string.runtime_no_night)
        if (request.expected != night.identity) return@connected stale()
        takeoverBeforeRewrite(night)?.let { return@connected it }
        // The fresh read may have used the remaining safe interval; never apply the earlier preview.
        val preparation = prepareTemporary(request)
        if (preparation !is Preparation.Ready) return@connected apply(preparation, R.string.runtime_temporary_confirmed)
        // Journal the observation before a possible write. A crash after the new CONFIRMED
        // revision must not strand an alarm request whose identity still names the old one.
        repo.updateRuntime { it.withTemporaryAlarmHandoff(preparation.change) }
        if (repo.runtime.value.alarmInbox?.let { it.nightId == night.recordId && it.deviceId == night.deviceId } == true)
            NightAlarms.scheduleObservation(context, graph.now())
        advanceGeneration()
        apply(preparation, R.string.runtime_temporary_confirmed, afterWrite = {
            // A physical Off while uploading must not be undone by this programme start.
            if (!matchesCurrentProgramme(night, freshStatus(), graph.now())) throw RetimeTakeover()
        })
    }
}

/** Resolve once under the owner, before a connection can suspend or the command changes generation. */
internal fun NightActions.timedPreset(command: Command): ActionPreset? = repo.settings.value.let { settings ->
    when (command) {
        Command.SNOOZE_WARM -> settings.snoozeWarm
        Command.SNOOZE_COOL -> settings.snoozeCool
        Command.EXTEND_WARM -> settings.extendWarm
        Command.EXTEND_COOL -> settings.extendCool
        else -> null
    }
}

internal suspend fun NightActions.run(request: CommandRequest, manualPreset: ActionPreset?,
    automaticWake: io.github.willzy12h.nocturne.core.plan.WakeChoice? = null, stillEligible: () -> Boolean = { true },
    onPrepared: (PreparedChange) -> Unit = {}): CommandOutcome {
    if (request.deviceId != repo.settings.value.deviceId()) return stale(R.string.runtime_target_changed)
    val command = request.command
    if (command == Command.RESUME) return resumeLocked()
    if (command == Command.START_NIGHT) {
        val profile = if (request.profileId == null) repo.tonightsProfile() else repo.profile(request.profileId)
        if (profile == null) return refused(if (request.profileId == null) R.string.runtime_add_profile else R.string.runtime_profile_deleted)
        advanceGeneration()
        val s = repo.settings.value
        val old = repo.activeNight.value
        val alarm = AlarmSource.describe(context, s)
        val explicit = s.resolveWakeOverride(graph.now())
        val override = explicit ?: automaticWake?.let {
            io.github.willzy12h.nocturne.core.plan.TonightOverride(it.time.toString(), graph.now().toString())
        }
        val prepared = graph.runner().prepareStart(profile, alarm?.time, s.workingSlot, request.deviceId!!,
            old?.identity, old, override, s, alarm?.creator)
        val attributed = if (explicit == null && automaticWake != null) attributeAutoStart(prepared, profile, automaticWake)
            else prepared
        if (attributed is Preparation.Ready) onPrepared(attributed.change)
        return apply(attributed, R.string.runtime_started, validate = {
            if (!stillEligible()) throw PlanException(text(R.string.auto_start_skip_changed))
        })
    }
    if (command == Command.REAPPLY) {
        val night = repo.activeNight.value ?: return refused(R.string.runtime_no_night)
        if (night.isTestRun) return refused(R.string.short_test_fixed_plan)
        if (night.trackingManual) return refused(R.string.runtime_manual_tracking)
        val profile = repo.profile(night.profileId) ?: return refused(R.string.runtime_profile_deleted)
        takeoverBeforeRewrite(night)?.let { return it }
        advanceGeneration()
        return apply(graph.runner().reapply(night, profile, night.identity, repo.settings.value.resolveWakeOverride(graph.now())?.wakeAt?.let(ZonedDateTime::parse)), R.string.runtime_reapplied)
    }
    advanceGeneration(preserveOffer = command in TIMED_MANUAL || repo.activeNight.value?.trackingManual != false)
    return when (command) {
        Command.OFF, Command.END_NIGHT -> manualChange(text(R.string.runtime_off), endsNight = true, confirms = { it.mode == Mode.STANDBY },
            allowCurrentStatus = true, unconfirmed = text(R.string.runtime_off_unconfirmed)) { graph.controller().stop() }
        Command.TURBO -> quick(request.actionPreset ?: ActionPreset(Mode.TURBO, 86, 100, 10),
            request.actionPreset?.let { capturedPresetMessage(context, it) } ?: text(R.string.runtime_turbo))
        Command.COOL -> quick(request.actionPreset ?: repo.settings.value.extendCool,
            request.actionPreset?.let { capturedPresetMessage(context, it) } ?: text(R.string.runtime_cooling))
        Command.WARM -> quick(request.actionPreset ?: repo.settings.value.extendWarm,
            request.actionPreset?.let { capturedPresetMessage(context, it) } ?: text(R.string.runtime_warming))
        Command.BOOST -> boostLocked(request.actionPreset)
        Command.SNOOZE_WARM -> timedManual(requireNotNull(manualPreset), EventKind.SNOOZED, text(R.string.runtime_snooze_warm))
        Command.SNOOZE_COOL -> timedManual(requireNotNull(manualPreset), EventKind.SNOOZED, text(R.string.runtime_snooze_cool))
        Command.EXTEND_WARM -> timedManual(requireNotNull(manualPreset), EventKind.EXTENDED, text(R.string.runtime_extend_warm))
        Command.EXTEND_COOL -> timedManual(requireNotNull(manualPreset), EventKind.EXTENDED, text(R.string.runtime_extend_cool))
        else -> refused(R.string.runtime_ineligible)
    }
}

private suspend fun NightActions.resumeLocked(): CommandOutcome {
    val offer = repo.runtime.value.resumeOffer ?: return refused(R.string.runtime_resume_expired)
    if (offer.night.deviceId != repo.settings.value.deviceId()) return stale()
    val initial = graph.runner().resume(offer, graph.now(), repo.runtime.value.generation)
    val current = repo.activeNight.value
    val prepared = if (initial is Preparation.Ready && current != null) Preparation.Ready(initial.change.copy(
        previous = current, request = initial.change.request.copy(expected = current.identity))) else initial
    return apply(prepared, R.string.runtime_resumed)
}
