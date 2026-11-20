package io.github.willzy12h.nocturne.night

import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZonedDateTime

/** Resolve the chosen wall clock once. A later reconnect cannot roll a missed deadline into tomorrow. */
internal fun nextManualFinish(now: ZonedDateTime, time: LocalTime): Instant? {
    val today = now.toLocalDate().atTime(time)
    fun occurrences(local: java.time.LocalDateTime) = now.zone.rules.getValidOffsets(local)
        .map { local.toInstant(it) }.sorted()
    val times = occurrences(today)
    if (times.isEmpty() && time > now.toLocalTime()) return null
    return times.firstOrNull { it.isAfter(now.toInstant()) } ?: occurrences(today.plusDays(1)).firstOrNull()
}

/** The BedJet timer takes whole minutes. Aim to finish within the selected clock minute. */
internal fun manualMinutesUntil(now: Instant, end: Instant, maximumMinutes: Int): Int? {
    val remaining = Duration.between(now, end)
    if (remaining.isNegative || remaining.isZero || remaining > Duration.ofMinutes(maximumMinutes.toLong())) return null
    return (remaining.minusNanos(1).toMinutes() + 1).toInt()
}
