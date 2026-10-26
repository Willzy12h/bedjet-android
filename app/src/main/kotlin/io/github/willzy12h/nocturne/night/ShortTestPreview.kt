package io.github.willzy12h.nocturne.night

import java.time.ZonedDateTime

/** The equipment and slot the person saw before confirming the separate Settings test. */
data class ShortTestPreview(val deviceId: String, val slot: Int, val previewedAt: ZonedDateTime)
