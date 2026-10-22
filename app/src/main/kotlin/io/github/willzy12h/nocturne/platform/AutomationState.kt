package io.github.willzy12h.nocturne.platform

import kotlinx.serialization.Serializable

/** Device-local journal, never imported/exported with portable profiles. */
@Serializable data class AutomationState(
    val claims: List<String> = emptyList(),
    val observed: DeviceObservation? = null,
)
@Serializable data class DeviceObservation(val device: String, val name: String?, val mode: String,
    val running: Boolean, val at: String)
