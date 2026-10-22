package io.github.willzy12h.nocturne.core.night

import io.github.willzy12h.nocturne.core.link.BedJetController
import io.github.willzy12h.nocturne.core.plan.*
import io.github.willzy12h.nocturne.core.store.*
import java.time.ZonedDateTime

/** Test-only in-memory owner. Exercises the same prepared/current/publish/beforeWrite contract as the app. */
class NightFixture(private val runner: NightRunner, private val controller: BedJetController, private val now: () -> ZonedDateTime) {
    private var current: ActiveNight? = null
    data class StartResult(val plan: Plan, val night: ActiveNight, val record: NightRecord, val slotBackup: SlotBackup?)
    data class RetimeResult(val plan: Plan, val night: ActiveNight, val event: HistoryEvent)
    suspend fun start(profile: Profile, alarm: ZonedDateTime?, slot: Int, alreadyBackedUp: List<SlotBackup>,
        deviceId: String = "unknown", override: TonightOverride? = null): StartResult {
        val change = (runner.prepareStart(profile, alarm, slot, deviceId, current?.identity, current, override) as Preparation.Ready).change
        var backup: SlotBackup? = null
        val result = runner.apply(change, { current }, publish = { current = it }, beforeWrite = {
            if (alreadyBackedUp.none { it.deviceId == deviceId && it.slot == slot }) {
                backup = SlotBackup(deviceId, slot, controller.storedPrograms()[slot], now().toString())
            }
        }) as NightMutation.Applied
        val n = result.night
        val detail = if (n.wakeOrigin == WakeOrigin.OVERRIDE) "Started with tonight-only wake time" else "Started"
        return StartResult(result.plan, n, NightRecord(n.recordId, profile.id, profile.name, n.startedAt, n.wakeAt,
            n.wakeFromAlarm, n.applied.steps, listOf(HistoryEvent(now().toString(), EventKind.STARTED, detail)),
            deviceId = deviceId, revisions = listOf(n.applied)), backup)
    }
    suspend fun retime(profile: Profile, night: ActiveNight, alarm: ZonedDateTime?, force: Boolean = false): RetimeResult? {
        val preparation = if (force) runner.reapply(night, profile, night.identity) else runner.prepareRetime(night, night.identity, alarm, liveProfile = profile)
        val change = (preparation as? Preparation.Ready)?.change ?: return null
        current = night
        return when (val result = runner.apply(change, { current }, publish = { current = it })) {
            is NightMutation.Applied -> RetimeResult(result.plan, result.night, HistoryEvent(now().toString(), EventKind.RETIMED, "Retimed"))
            is NightMutation.Failed -> throw result.cause
            else -> null
        }
    }
}
