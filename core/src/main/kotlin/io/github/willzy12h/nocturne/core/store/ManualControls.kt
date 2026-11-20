package io.github.willzy12h.nocturne.core.store

import io.github.willzy12h.nocturne.core.link.DeviceId
import io.github.willzy12h.nocturne.core.protocol.Mode
import kotlinx.serialization.Serializable

/** Only values explicitly changed by the person and confirmed by this real BedJet. */
@Serializable
data class ManualControls(val device: String, val mode: Mode, val temperature: Int? = null, val fanStep: Int? = null) {
    fun valid(): Boolean = device.matches(ADDRESS) && mode.usesFan && (temperature != null || fanStep != null) &&
        (temperature == null || mode.usesTemperature && temperature in mode.temperatureRange) &&
        (fanStep == null || fanStep in 0..19)

    companion object {
        private val ADDRESS = Regex("ble:(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}")
        const val MAX_ENTRIES = 80 // Sixteen remembered units with five running modes each.
    }
}

fun Settings.manualFor(device: String?, mode: Mode) = manualControls.firstOrNull { device != null && DeviceId.same(it.device, device) && it.mode == mode }

fun Settings.rememberManual(change: ManualControls): Settings {
    if (!change.valid()) return this
    val old = manualFor(change.device, change.mode)
    val combined = change.copy(temperature = change.temperature ?: old?.temperature, fanStep = change.fanStep ?: old?.fanStep)
    return copy(manualControls = (listOf(combined) + manualControls.filterNot { it == old }).take(ManualControls.MAX_ENTRIES))
}
