package io.github.willzy12h.nocturne.core.store

import io.github.willzy12h.nocturne.core.link.DeviceId
import io.github.willzy12h.nocturne.core.plan.Block
import io.github.willzy12h.nocturne.core.protocol.Mode
import kotlinx.serialization.Serializable
import java.time.ZonedDateTime

@Serializable
enum class EventKind { STARTED, RETIMED, SNOOZED, EXTENDED, STOPPED, ENDED, UNIT_NOTICE, ERROR, REMOTE_CHANGE }

/** Times are ISO-8601 zoned date-times, as `ZonedDateTime.toString()` writes them. */
@Serializable
data class HistoryEvent(val at: String, val kind: EventKind, val detail: String)

@Serializable
data class RecordedStep(val block: Block, val mode: Mode, val temperature: Int, val fanPercent: Int, val startsAt: String, val minutes: Int)

@Serializable
data class TempSample(val at: String, val room: Int, val air: Int)

/** One night: what was planned, and what happened. */
@Serializable
data class NightRecord(
    val id: String,
    val profileId: String,
    val profileName: String,
    val startedAt: String,
    val wakeAt: String,
    val wakeFromAlarm: Boolean,
    val steps: List<RecordedStep> = emptyList(),
    val events: List<HistoryEvent> = emptyList(),
    val temperatures: List<TempSample> = emptyList(),
    /** The BedJet the night ran on, as a [DeviceId]; [Migrations.UNKNOWN_DEVICE] for nights saved before this was recorded. */
    val deviceId: String = Migrations.UNKNOWN_DEVICE,
)

object History {
    const val KEEP_DAYS = 60L

    fun prune(records: List<NightRecord>, now: ZonedDateTime): List<NightRecord> {
        val cutoff = now.minusDays(KEEP_DAYS)
        return records.filter { runCatching { ZonedDateTime.parse(it.startedAt) }.getOrNull()?.isAfter(cutoff) ?: false }
    }

    fun addEvent(records: List<NightRecord>, id: String, event: HistoryEvent): List<NightRecord> =
        records.map { if (it.id == id) it.copy(events = it.events + event) else it }

    /** The nights run on [deviceId]. */
    fun forDevice(records: List<NightRecord>, deviceId: String): List<NightRecord> = records.filter { it.deviceId == deviceId }

    /** Every night except the demo unit's, for views that should show only real ones. Nothing is deleted. */
    fun withoutDemo(records: List<NightRecord>): List<NightRecord> = records.filterNot { DeviceId.isDemo(it.deviceId) }
}
