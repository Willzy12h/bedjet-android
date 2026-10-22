package io.github.willzy12h.nocturne.platform

import io.github.willzy12h.nocturne.core.store.NightRecord
import io.github.willzy12h.nocturne.core.store.RecordedStep
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One RFC 4180 table: typed rows preserve nights, every applied revision, steps, samples and events. */
object HistoryCsv {
    fun encode(records: List<NightRecord>, fahrenheit: Boolean, identifiers: Boolean): String = buildString {
        fun row(values: List<String>) {
            append(values.joinToString(",") { raw ->
                val text = if (identifiers) raw else raw.replace(Regex("(?i)(?:ble:)?(?:[0-9a-f]{2}:){5}[0-9a-f]{2}"), "[device omitted]")
                if (text.any { it in ",\"\r\n" }) "\"${text.replace("\"", "\"\"")}\"" else text
            }); append("\r\n")
        }
        fun time(value: String): String = runCatching { ZonedDateTime.parse(value).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME) }.getOrDefault(value)
        fun zone(value: String): String = runCatching { ZonedDateTime.parse(value).zone.id }.getOrDefault("")
        val head = mutableListOf("row", "night id", "profile", "revision", "timestamp ISO 8601", "end ISO 8601", "zone", "mode", "temperature °C")
        if (fahrenheit) head += "temperature °F"
        head += listOf("fan %", "duration min", "detail")
        if (identifiers) head += "device identifier"
        row(head)
        records.forEach { n ->
            fun emit(kind: String, at: String, end: String = "", revision: String = "", mode: String = "", temp: Int? = null,
                fan: String = "", minutes: String = "", detail: String = "") {
                val c = temp?.div(2.0)
                val cells = mutableListOf(kind, n.id, n.profileName, revision, time(at), time(end), zone(at), mode,
                    c?.let { String.format(Locale.ROOT, "%.1f", it) }.orEmpty())
                if (fahrenheit) cells += c?.let { String.format(Locale.ROOT, "%.1f", it * 9 / 5 + 32) }.orEmpty()
                cells += listOf(fan, minutes, detail)
                if (identifiers) cells += n.deviceId
                row(cells)
            }
            fun step(s: RecordedStep, revision: String) = emit("step", s.startsAt, revision=revision, mode=s.mode.name,
                temp=s.temperature, fan=s.fanPercent.toString(), minutes=s.minutes.toString(), detail=s.block.name)
            emit("night", n.startedAt, n.wakeAt, detail=if (n.wakeFromAlarm) "Phone alarm" else "Profile or override")
            n.steps.forEach { step(it, "0") }
            n.revisions.forEach { r ->
                emit("revision", r.start, r.wake, r.revision.toString())
                r.steps.forEach { step(it, r.revision.toString()) }
            }
            n.events.forEach { emit("event", it.at, detail="${it.kind}: ${it.detail}") }
            n.temperatures.forEach {
                emit("room sample", it.at, temp=it.room)
                emit("air sample", it.at, temp=it.air)
            }
        }
    }
}
