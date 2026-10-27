package io.github.willzy12h.nocturne.night

import io.github.willzy12h.nocturne.core.protocol.DeviceStatus
import io.github.willzy12h.nocturne.core.protocol.Limits
import io.github.willzy12h.nocturne.core.protocol.Mode

/** BedJet status max_hrs/max_mins, bounded to the command's one-byte hours and normalised minutes. */
internal fun manualRuntimeMaximum(status: DeviceStatus): Int {
    if (status.mode == Mode.STANDBY || status.mode == Mode.WAIT) return 0
    val reported = status.maxRuntimeMinutes.coerceIn(0, 255 * 60 + 59)
    return if (status.mode == Mode.TURBO) minOf(reported, Limits.TURBO_MINUTES) else reported
}
