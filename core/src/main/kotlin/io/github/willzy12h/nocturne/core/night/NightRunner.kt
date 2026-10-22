package io.github.willzy12h.nocturne.core.night

import io.github.willzy12h.nocturne.core.link.BedJetController
import io.github.willzy12h.nocturne.core.plan.Phase
import io.github.willzy12h.nocturne.core.plan.Plan
import io.github.willzy12h.nocturne.core.plan.PlanCompiler
import io.github.willzy12h.nocturne.core.plan.Profile
import io.github.willzy12h.nocturne.core.plan.ProfileLimits
import io.github.willzy12h.nocturne.core.plan.TurboChain
import io.github.willzy12h.nocturne.core.plan.WakeResolver
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.protocol.StoredProgram
import io.github.willzy12h.nocturne.core.store.ActionPreset
import io.github.willzy12h.nocturne.core.store.EventKind
import io.github.willzy12h.nocturne.core.store.HistoryEvent
import io.github.willzy12h.nocturne.core.store.Migrations
import io.github.willzy12h.nocturne.core.store.NightRecord
import io.github.willzy12h.nocturne.core.store.RecordedStep
import io.github.willzy12h.nocturne.core.store.SlotBackup
import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.ZonedDateTime

/** The night running on the BedJet, as the app remembers it between launches. Times are ISO zoned date-times. */
@Serializable
data class ActiveNight(
    val recordId: String,
    val profileId: String,
    val startedAt: String,
    val wakeAt: String,
    val wakeFromAlarm: Boolean,
    val slot: Int,
    val wakeUpStart: String,
    val endsAt: String,
)

data class StartResult(val plan: Plan, val night: ActiveNight, val record: NightRecord, val slotBackup: SlotBackup?)
data class RetimeResult(val plan: Plan, val night: ActiveNight, val event: HistoryEvent)

/** Starts, re-times and interrupts nights on a BedJet. Everything here is what the buttons, widgets and alarms do. */
class NightRunner(private val controller: BedJetController, private val now: () -> ZonedDateTime) {

    /**
     * Plans tonight around [alarm] (or the profile's wake time), writes it to [slot] of the BedJet named [deviceId] and
     * starts it.
     */
    suspend fun start(
        profile: Profile,
        alarm: ZonedDateTime?,
        slot: Int,
        alreadyBackedUp: List<SlotBackup>,
        deviceId: String = Migrations.UNKNOWN_DEVICE,
    ): StartResult {
        val time = now()
        val wake = WakeResolver.resolve(profile, time, alarm)
        val plan = PlanCompiler.compile(profile, time, wake.time)
        val backup = backupIfNeeded(deviceId, slot, alreadyBackedUp)
        controller.store(StoredProgram(profile.name, plan.programSteps), slot)
        controller.startProgram(slot)
        val night = ActiveNight(
            recordId = "night-${time.toInstant().toEpochMilli()}",
            profileId = profile.id,
            startedAt = time.toString(),
            wakeAt = wake.time.toString(),
            wakeFromAlarm = wake.fromAlarm,
            slot = slot,
            wakeUpStart = plan.wakeUpStart.toString(),
            endsAt = plan.end.toString(),
        )
        val source = if (wake.fromAlarm) "alarm" else "default wake time"
        val record = NightRecord(
            id = night.recordId, profileId = profile.id, profileName = profile.name,
            startedAt = night.startedAt, wakeAt = night.wakeAt, wakeFromAlarm = wake.fromAlarm,
            steps = recordedSteps(plan),
            events = listOf(HistoryEvent(time.toString(), EventKind.STARTED, "Started ${profile.name}, waking at ${wake.time.toLocalTime()} ($source)")),
            deviceId = deviceId,
        )
        return StartResult(plan, night, record, backup)
    }

    /**
     * Follows an alarm that changed during the night. Returns null when nothing needs to change: the wake time is the
     * same, the wake-up has already begun, or the fallback wake time has already passed. With [force] the rest of the
     * night is rewritten even when the wake time hasn't changed, to apply edits made to the running profile.
     */
    suspend fun retime(profile: Profile, night: ActiveNight, alarm: ZonedDateTime?, force: Boolean = false): RetimeResult? {
        val time = now()
        val start = ZonedDateTime.parse(night.startedAt)
        val oldWake = ZonedDateTime.parse(night.wakeAt)
        val alarmFits = profile.wakeSource.useAlarm && alarm != null && alarm.isAfter(time) &&
            Duration.between(start, alarm) <= WakeResolver.MAX_NIGHT
        val newWake = if (alarmFits) alarm!! else WakeResolver.nextOccurrence(profile.wakeSource.defaultWakeMinute, start.plus(WakeResolver.MIN_NIGHT))
        if ((newWake.isEqual(oldWake) && !force) || !newWake.isAfter(time)) return null
        val original = PlanCompiler.compile(profile, start, oldWake)
        val plan = PlanCompiler.replan(profile, original, time, newWake) ?: return null
        controller.store(StoredProgram(profile.name, plan.programSteps), night.slot)
        controller.startProgram(night.slot)
        val updated = night.copy(
            wakeAt = newWake.toString(), wakeFromAlarm = alarmFits,
            wakeUpStart = plan.wakeUpStart.toString(), endsAt = plan.end.toString(),
        )
        val detail = if (newWake.isEqual(oldWake)) "Profile changes applied to the rest of the night" else "Wake-up moved from ${oldWake.toLocalTime()} to ${newWake.toLocalTime()}"
        val event = HistoryEvent(time.toString(), EventKind.RETIMED, detail)
        return RetimeResult(plan, updated, event)
    }

    /** Runs a snooze, extend or widget action now, held to the profile's limits. */
    suspend fun quickAction(preset: ActionPreset, limits: ProfileLimits) {
        val temperature = PlanCompiler.temperatureFor(preset.mode, preset.temperature, limits)
        val fanStep = PlanCompiler.fanStepFor(preset.fanPercent, limits)
        controller.runManual(preset.mode, temperature, fanStep, preset.minutes)
    }

    /** Turbo for its 10 minutes then Heat at the hottest the limits allow, written to [slot] so the BedJet chains them. */
    suspend fun boost(
        minutes: Int,
        limits: ProfileLimits,
        slot: Int,
        alreadyBackedUp: List<SlotBackup> = emptyList(),
        deviceId: String = Migrations.UNKNOWN_DEVICE,
    ): SlotBackup? {
        val steps = PlanCompiler.phaseSteps(Phase(Mode.TURBO, 86, 100, minutes), limits, TurboChain.TURBO_THEN_HEAT)
        val backup = backupIfNeeded(deviceId, slot, alreadyBackedUp)
        controller.store(StoredProgram("Boost", steps), slot)
        controller.startProgram(slot)
        return backup
    }

    suspend fun stop() = controller.stop()

    /** Keeps a copy of a program the app didn't write, the first time a slot of this device is overwritten. */
    private suspend fun backupIfNeeded(deviceId: String, slot: Int, alreadyBackedUp: List<SlotBackup>): SlotBackup? {
        if (alreadyBackedUp.any { it.deviceId == deviceId && it.slot == slot }) return null
        val existing = controller.storedPrograms().getOrNull(slot) ?: return null
        return SlotBackup(deviceId, slot, existing, now().toString())
    }

    private fun recordedSteps(plan: Plan) = plan.steps.map {
        RecordedStep(it.block, it.step.mode, it.step.temperature, (it.step.fanStep + 1) * 5, it.startsAt.toString(), it.minutes)
    }
}
