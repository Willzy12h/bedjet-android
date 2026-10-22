package io.github.willzy12h.nocturne.core.store

import io.github.willzy12h.nocturne.core.link.DeviceId
import io.github.willzy12h.nocturne.core.protocol.Mode
import kotlinx.serialization.Serializable

/** A button on the wake-up notification. */
@Serializable
enum class WakeAction { SNOOZE_WARM, SNOOZE_COOL, EXTEND_WARM, EXTEND_COOL, OFF }

/** What a quick action runs: a mode at a temperature (half °C) and fan for a number of minutes. */
@Serializable
data class ActionPreset(val mode: Mode, val temperature: Int, val fanPercent: Int, val minutes: Int)

/** Which palette the app and widgets use. SYSTEM follows the phone's dark theme setting. */
@Serializable
enum class ThemeMode { SYSTEM, DARK, LIGHT }

@Serializable
data class Settings(
    /** BedJet slot (0-based) that tonight's plan is written into. */
    val workingSlot: Int = 2,
    val selectedProfileId: String? = null,
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
    /** GitHub `owner/repo` whose latest release is checked for updates. The token is kept outside backups. */
    val updateRepo: String = "Willzy12h/bedjet-android",
    val autoUpdateCheck: Boolean = false,
    /** Alarms set by these apps move the wake-up; others (calendar reminders, timers) don't. */
    val alarmPackages: Set<String> = setOf("com.google.android.deskclock", "com.sec.android.app.clockpackage"),
    // Appearance. Defaults keep older saved settings and backups loading unchanged.
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** Tonight shows the night as a list of steps instead of the bar curve. */
    val simpleView: Boolean = false,
)

/** The BedJet commands go to: the demo unit, the chosen real one, or null when none is chosen yet. */
fun Settings.deviceId(): String? = if (demoMode) DeviceId.DEMO else targetDevice
