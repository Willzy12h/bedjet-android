package io.github.willzy12h.nocturne.night

import io.github.willzy12h.nocturne.core.protocol.CommandSafety
import io.github.willzy12h.nocturne.core.protocol.DeviceStatus

/** A reported maximum may narrow, never widen, the command policy. */
internal fun manualRuntimeMaximum(status: DeviceStatus): Int = CommandSafety.manualMaximum(status)
