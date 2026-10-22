package io.github.willzy12h.nocturne.platform

import java.time.ZonedDateTime
import java.util.UUID

data class BedtimeWindow(val bedtime: ZonedDateTime, val opens: ZonedDateTime, val expires: ZonedDateTime) {
    fun key(device: String) = "$device:${bedtime.toLocalDate()}"
    fun contains(now: ZonedDateTime) = !now.isBefore(opens) && now.isBefore(expires)
}
object PlatformPolicy {
    fun window(minute: Int, now: ZonedDateTime): BedtimeWindow? {
        if (minute !in 0..1439) return null
        return (-1L..1L).map { offset ->
            val bedtime = now.toLocalDate().plusDays(offset).atStartOfDay().plusMinutes(minute.toLong()).atZone(now.zone)
            BedtimeWindow(bedtime, bedtime.minusMinutes(60), bedtime.plusMinutes(120))
        }.firstOrNull { it.contains(now) }
    }
    fun eligible(input: ChargerEligibility): Boolean = with(input) {
        enabled && charging && selected && validPlan && minutes >= 180 && inWindow &&
            !active && !manual && !uncertain && !takeover && prerequisites && !alreadyAttempted
    }
    fun handoverUuid(name: String): UUID {
        val bytes = java.security.MessageDigest.getInstance("SHA-256")
            .digest(("lull.handoff." + name.substringBefore('\u0000')).toByteArray(Charsets.UTF_8)).copyOf(16)
        bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x50).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
        val buffer = java.nio.ByteBuffer.wrap(bytes)
        return UUID(buffer.long, buffer.long)
    }
}
data class ChargerEligibility(val enabled: Boolean = true, val charging: Boolean = true,
    val selected: Boolean = true, val validPlan: Boolean = true, val minutes: Long = 180,
    val inWindow: Boolean = true, val active: Boolean = false, val manual: Boolean = false,
    val uncertain: Boolean = false, val takeover: Boolean = false, val prerequisites: Boolean = true,
    val alreadyAttempted: Boolean = false)
