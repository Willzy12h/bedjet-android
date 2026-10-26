package io.github.willzy12h.nocturne.core.store

import io.github.willzy12h.nocturne.core.link.DeviceId
import io.github.willzy12h.nocturne.core.plan.Profile
import io.github.willzy12h.nocturne.core.plan.TonightChoice
import io.github.willzy12h.nocturne.core.plan.TonightOverride
import io.github.willzy12h.nocturne.core.protocol.Mode
import java.time.ZonedDateTime
import kotlinx.serialization.Serializable

/** A button on the wake-up notification. */
@Serializable
enum class WakeAction { SNOOZE_WARM, SNOOZE_COOL, EXTEND_WARM, EXTEND_COOL, OFF }

/** What a quick action runs: a mode at a temperature (half °C) and fan for a number of minutes. */
@Serializable
data class ActionPreset(val mode: Mode, val temperature: Int, val fanPercent: Int, val minutes: Int)

/** Which palette the app and widgets use. SYSTEM follows the phone's dark theme setting. */
@Serializable
enum class ThemeMode { SYSTEM, DARK, LIGHT, SCHEDULED }

@Serializable
data class Settings(
    /** BedJet slot (0-based) that tonight's plan is written into. */
    val workingSlot: Int = 2,
    /** v1 read bridge; migrate once with an injected clock before saving or resolving. */
    val selectedProfileId: String? = null,
    val tonightChoice: TonightChoice? = null,
    val tonightOverride: TonightOverride? = null,
    /** Android sometimes omits the alarm creator; accepted by default for backwards compatibility. */
    val acceptUnknownAlarmSource: Boolean = true,
    /** Up to three, in the order they appear on the notification. */
    val notificationActions: List<WakeAction> = listOf(WakeAction.SNOOZE_WARM, WakeAction.EXTEND_COOL, WakeAction.OFF),
    val snoozeWarm: ActionPreset = ActionPreset(Mode.HEAT, 66, 40, 10),
    val snoozeCool: ActionPreset = ActionPreset(Mode.COOL, 44, 50, 10),
    val extendWarm: ActionPreset = ActionPreset(Mode.HEAT, 64, 30, 30),
    val extendCool: ActionPreset = ActionPreset(Mode.COOL, 44, 40, 30),
    /** Boost: Turbo for its 10 minutes, then Heat for the rest. */
    val boostMinutes: Int = 15,
    val fahrenheit: Boolean = false,
    val demoMode: Boolean = false,
    /** The real BedJet chosen by the person, as a [DeviceId] (`ble:<MAC>`). Null until one is chosen. */
    val targetDevice: String? = null,
    /** Local supervised fan check for this exact real BedJet; never inferred from a backup. */
    val setupFanCheckedDevice: String? = null,
    /** GitHub `owner/repo` whose latest release is checked for updates. The token is kept outside backups. */
    val updateRepo: String = "Willzy12h/bedjet-android",
    val autoUpdateCheck: Boolean = false,
    /** Alarms set by these apps move the wake-up; others (calendar reminders, timers) don't. */
    val alarmPackages: Set<String> = setOf("com.google.android.deskclock", "com.sec.android.app.clockpackage"),
    // Appearance. Defaults keep older saved settings and backups loading unchanged.
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** Local clock times. Equal boundaries mean always dark. */
    val scheduledDarkMinute: Int = 22 * 60,
    val scheduledLightMinute: Int = 7 * 60,
    /** Tonight shows the night as a list of steps instead of the bar curve. */
    val simpleView: Boolean = true,
    val quickControlsCollapsed: Boolean = false,
    /** Stable section IDs; unknown, repeated and missing IDs are normalised when displayed. */
    val tonightSectionOrder: List<String> = listOf("night", "wake", "connection"),
    val quietNightNotification: Boolean = false,
    val platform: PlatformSettings = PlatformSettings(),
) {
    fun normalisedTonightOrder(): List<String> {
        val known = listOf("night", "wake", "connection")
        val saved = tonightSectionOrder.filter { it in known }.distinct()
        return saved + known.filterNot { it in saved }
    }

    fun isDarkTheme(at: ZonedDateTime, systemDark: Boolean): Boolean = when (themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SCHEDULED -> {
            val minute = at.hour * 60 + at.minute
            when {
                scheduledDarkMinute == scheduledLightMinute -> true
                scheduledDarkMinute < scheduledLightMinute -> minute in scheduledDarkMinute until scheduledLightMinute
                else -> minute >= scheduledDarkMinute || minute < scheduledLightMinute
            }
        }
    }
    fun setWakeOverride(wakeAt: ZonedDateTime, at: ZonedDateTime): Settings =
        copy(tonightOverride = TonightOverride(wakeAt.toString(), at.toString()))
    fun clearWakeOverride(): Settings = copy(tonightOverride = null)
    fun resolveWakeOverride(at: ZonedDateTime): TonightOverride? =
        tonightOverride?.takeIf { at.isBefore(ZonedDateTime.parse(it.wakeAt)) }
    /** Today's bedtime window ends at the next noon, including the morning after midnight. */
    fun chooseTonight(profileId: String, at: ZonedDateTime): Settings {
        val noon = at.toLocalDate().atTime(12, 0).atZone(at.zone)
        val end = if (at.isBefore(noon)) noon else noon.plusDays(1)
        return copy(selectedProfileId = null, tonightChoice = TonightChoice(profileId, end.toString()))
    }
    fun followSchedule(): Settings = copy(selectedProfileId = null, tonightChoice = null)
    /** Call once on load and persist: repeatedly migrating v1 would extend its expiry. */
    fun migrateTonightChoice(at: ZonedDateTime): Settings =
        if (tonightChoice == null && selectedProfileId != null) chooseTonight(selectedProfileId, at) else copy(selectedProfileId = null)
    fun resolveTonightChoice(at: ZonedDateTime): TonightChoice? =
        tonightChoice?.takeIf { at.isBefore(ZonedDateTime.parse(it.expiresAt)) }
    fun resolveProfile(profiles: List<Profile>, at: ZonedDateTime): Profile? =
        profiles.firstOrNull { it.id == resolveTonightChoice(at)?.profileId }
            ?: profiles.firstOrNull { at.dayOfWeek.value in it.days } ?: profiles.firstOrNull()

}

/** The BedJet commands go to: the demo unit, the chosen real one, or null when none is chosen yet. */
fun Settings.deviceId(): String? = if (demoMode) DeviceId.DEMO else targetDevice
