package io.github.willzy12h.nocturne.core.store

import kotlinx.serialization.Serializable

@Serializable
enum class AutoStartMode { FIXED, BEFORE_WAKE }

/** Explicit automatic-control consent is tied to one real unit and one selected profile. */
@Serializable
data class AutoStartSettings(
    val enabled: Boolean = false,
    val mode: AutoStartMode = AutoStartMode.FIXED,
    val fixedMinute: Int = 22 * 60 + 30,
    val minutesBeforeWake: Int = 8 * 60,
    val profileId: String? = null,
    val deviceId: String? = null,
    val onlyIfCharging: Boolean = false,
) {
    /** Diagnostic codes; the Android UI supplies the resource-backed explanation. */
    fun validationErrors(): List<String> = buildList {
        if (fixedMinute !in 0..1439) add("AUTO_START_TIME")
        if (minutesBeforeWake !in 60..1080) add("AUTO_START_OFFSET")
        if ((enabled && profileId == null) || profileId?.let { it.isBlank() || it.length > 128 } == true)
            add("AUTO_START_PROFILE")
        if ((enabled && deviceId == null) || deviceId?.let { !REAL_BEDJET.matches(it) } == true)
            add("AUTO_START_DEVICE")
    }
    companion object {
        private val REAL_BEDJET = Regex("ble:(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}")
    }
}

@Serializable
data class PlatformSettings(
    val bedtimeReminder: Boolean = false,
    val reminderMinutes: Int = 30,
    val chargerAutoStart: Boolean = false,
    /** Explicit opt-in is tied to the selected real unit. Changing target cannot transfer authorisation. */
    val chargerDevice: String? = null,
    val healthSleepOverlay: Boolean = false,
    /** Separate explicit consent for writing completed, confirmed BedJet programme sessions. */
    val healthSleepExport: Boolean = false,
    val lullHandover: Boolean = false,
    /** Null reads the legacy charging-only opt-in until its one-time local migration. */
    val autoStart: AutoStartSettings? = null,
) {
    fun validationErrors(): List<String> = buildList {
        if (reminderMinutes !in 0..120) add("Bedtime reminder must be 0–120 minutes before bedtime.")
        if (chargerDevice != null && !Regex("ble:(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}").matches(chargerDevice))
            add("Automatic start needs a valid selected BedJet.")
        addAll(autoStart?.validationErrors().orEmpty())
    }
}
