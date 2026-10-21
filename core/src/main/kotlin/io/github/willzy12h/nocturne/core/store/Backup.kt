package io.github.willzy12h.nocturne.core.store

import io.github.willzy12h.nocturne.core.plan.Profile
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.protocol.StoredProgram
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** A button on the wake-up notification. */
@Serializable
enum class WakeAction { SNOOZE_WARM, SNOOZE_COOL, EXTEND_WARM, EXTEND_COOL, OFF }

/** What a quick action runs: a mode at a temperature (half °C) and fan for a number of minutes. */
@Serializable
data class ActionPreset(val mode: Mode, val temperature: Int, val fanPercent: Int, val minutes: Int)

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
    /** GitHub `owner/repo` whose latest release is checked for updates. The token is kept outside backups. */
    val updateRepo: String = "Willzy12h/bedjet-android",
    val autoUpdateCheck: Boolean = false,
    /** Alarms set by these apps move the wake-up; others (calendar reminders, timers) don't. */
    val alarmPackages: Set<String> = setOf("com.google.android.deskclock", "com.sec.android.app.clockpackage"),
)

/** A program the app found in a BedJet slot before it first overwrote it. */
@Serializable
data class SlotBackup(val slot: Int, val program: StoredProgram, val savedAt: String)

@Serializable
data class BackupBundle(
    val schema: Int = BackupCodec.SCHEMA,
    val createdAt: String,
    val profiles: List<Profile>,
    val settings: Settings,
    val slotBackups: List<SlotBackup> = emptyList(),
    val history: List<NightRecord> = emptyList(),
)

class BackupException(message: String) : Exception(message)

object BackupCodec {
    const val SCHEMA = 1

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    fun encode(bundle: BackupBundle): String = json.encodeToString(BackupBundle.serializer(), bundle)

    fun decode(text: String): BackupBundle {
        val bundle = try {
            json.decodeFromString(BackupBundle.serializer(), text)
        } catch (e: SerializationException) {
            throw BackupException("This isn't a Nocturne backup, or it is damaged.")
        } catch (e: IllegalArgumentException) {
            throw BackupException("This isn't a Nocturne backup, or it is damaged.")
        }
        if (bundle.schema > SCHEMA) throw BackupException("This backup is from a newer version of Nocturne. Update the app first.")
        return bundle
    }
}
