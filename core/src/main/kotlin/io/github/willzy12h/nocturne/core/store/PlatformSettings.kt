package io.github.willzy12h.nocturne.core.store

import kotlinx.serialization.Serializable

@Serializable
data class PlatformSettings(
    val bedtimeReminder: Boolean = false,
    val reminderMinutes: Int = 30,
    val chargerAutoStart: Boolean = false,
    /** Explicit opt-in is tied to the selected real unit. Changing target cannot transfer authorisation. */
    val chargerDevice: String? = null,
    val healthSleepOverlay: Boolean = false,
    val lullHandover: Boolean = false,
) {
    fun validationErrors(): List<String> = buildList {
        if (reminderMinutes !in 0..120) add("Bedtime reminder must be 0–120 minutes before bedtime.")
        if (chargerDevice != null && !Regex("ble:(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}").matches(chargerDevice))
            add("Automatic start needs a valid selected BedJet.")
    }
}
