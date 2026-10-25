package io.github.willzy12h.nocturne.core.protocol

import kotlinx.serialization.Serializable

/** Operating modes as the BedJet 3 reports them and stores them in program steps. */
@Serializable
enum class Mode(val code: Int) {
    STANDBY(0),
    HEAT(1),
    TURBO(2),
    EXTENDED_HEAT(3),
    COOL(4),
    DRY(5),
    WAIT(6);

    /** English name used in messages shown to the person using the app. */
    val displayName: String
        get() = when (this) {
            STANDBY -> "Off"
            HEAT -> "Heat"
            TURBO -> "Turbo"
            EXTENDED_HEAT -> "Gentle heat"
            COOL -> "Cool"
            DRY -> "Dry"
            WAIT -> "Pause"
        }

    /** Button code that switches the unit into this mode right now. */
    val button: Int?
        get() = when (this) {
            STANDBY -> 0x01
            COOL -> 0x02
            HEAT -> 0x03
            TURBO -> 0x04
            DRY -> 0x05
            EXTENDED_HEAT -> 0x06
            WAIT -> null
        }

    /** Target temperature limits in half-degrees Celsius, as the official app enforces them. */
    val temperatureRange: IntRange
        get() = when (this) {
            HEAT -> 45..80
            TURBO -> 86..86
            EXTENDED_HEAT -> 38..67
            COOL -> 38..62
            DRY -> 40..80
            STANDBY, WAIT -> 80..80
        }

    val usesTemperature: Boolean get() = temperatureRange.first != temperatureRange.last
    val usesFan: Boolean get() = this != STANDBY && this != WAIT

    companion object {
        fun fromCode(code: Int): Mode? = entries.firstOrNull { it.code == code }
    }
}

object Limits {
    const val SLOTS = 3
    const val STEPS_PER_PROGRAM = 32
    const val STEPS_PER_FRAGMENT = 8
    const val NAME_LENGTH = 15
    /** The unit refuses steps of 12 hours or longer. */
    const val MAX_STEP_MINUTES = 11 * 60 + 59
    const val TURBO_MINUTES = 10
}

/** One step of an on-device program. Five bytes on the wire. */
@Serializable
data class ProgramStep(
    val mode: Mode,
    /** 0..19, where the fan runs at `(fanStep + 1) * 5` percent. */
    val fanStep: Int,
    /** Target temperature in half-degrees Celsius. */
    val temperature: Int,
    val hours: Int,
    val minutes: Int,
    /** When set, hours/minutes are a wall-clock end time instead of a duration. */
    val endsAtClockTime: Boolean = false,
) {
    val durationMinutes: Int get() = hours * 60 + minutes

    val longestMinutes: Int get() = longestMinutes(mode, temperature, fanStep)

    /** False when the unit would stop this step before it finishes. Clock-time steps end when they say. */
    val fitsUnitLimits: Boolean get() = endsAtClockTime || durationMinutes <= longestMinutes

    companion object {
        /**
         * Longest the unit lets a step run, in minutes. Heat is cut off sooner the hotter and faster the air,
         * following the table the official app enforces.
         */
        fun longestMinutes(mode: Mode, temperature: Int, fanStep: Int): Int {
            if (mode != Mode.HEAT || temperature < 60) return Limits.MAX_STEP_MINUTES
            val row = HEAT_HOURS[fanStep.coerceIn(0, HEAT_HOURS.size - 1)]
            val offset = temperature - 60
            val pair = row[minOf(offset shr 1, row.size - 1)]
            val hours = if (offset and 1 == 0) pair shr 4 else pair and 0x0f
            return minOf(hours * 60, Limits.MAX_STEP_MINUTES)
        }

        /** Heat hours by fan step (rows) and target from 30 °C up in half-degree pairs (columns): even in the high nibble. */
        private val HEAT_HOURS: List<IntArray> = run {
            val quiet = intArrayOf(0xcc, 0xcc, 0xcc, 0xcc, 0xcc, 0xcc, 0xcc, 0xc6, 0x66, 0x66, 0x41)
            val strong = intArrayOf(0xcc, 0xcc, 0xcc, 0xcc, 0x44, 0x44, 0x44, 0x42, 0x21, 0x11, 0x11)
            List(5) { quiet } + listOf(
                intArrayOf(0xcc, 0xcc, 0xcc, 0xcc, 0xcc, 0xcc, 0xc6, 0x66, 0x64, 0x44, 0x21),
                intArrayOf(0xcc, 0xcc, 0xcc, 0xcc, 0xcc, 0xcc, 0xc6, 0x64, 0x44, 0x44, 0x21),
                intArrayOf(0xcc, 0xcc, 0xcc, 0xcc, 0xcc, 0xcc, 0xc6, 0x64, 0x42, 0x22, 0x21),
                intArrayOf(0xcc, 0xcc, 0xcc, 0xcc, 0xcc, 0xcc, 0xc4, 0x44, 0x42, 0x22, 0x21),
                intArrayOf(0xcc, 0xcc, 0xcc, 0xcc, 0xcc, 0xcc, 0xc4, 0x44, 0x42, 0x11, 0x11),
                intArrayOf(0xcc, 0xcc, 0xcc, 0xcc, 0xcc, 0xc4, 0x44, 0x44, 0x42, 0x11, 0x11),
                intArrayOf(0xcc, 0xcc, 0xcc, 0xcc, 0xcc, 0x44, 0x44, 0x42, 0x21, 0x11, 0x11),
                intArrayOf(0xcc, 0xcc, 0xcc, 0xcc, 0xc4, 0x44, 0x44, 0x42, 0x21, 0x11, 0x11),
                intArrayOf(0xcc, 0xcc, 0xcc, 0xcc, 0xc4, 0x44, 0x44, 0x42, 0x21, 0x11, 0x11),
            ) + List(6) { strong }
        }
    }
}

/** A program stored in one of the unit's three slots. */
@Serializable
data class StoredProgram(val name: String, val steps: List<ProgramStep>)

data class DeviceStatus(
    val remainingSeconds: Int,
    val actualTemperature: Int,
    val targetTemperature: Int,
    val mode: Mode,
    val fanStep: Int,
    val maxRuntimeMinutes: Int,
    val minTemperature: Int,
    val maxTemperature: Int,
    val ambientTemperature: Int,
    /** Why the unit last turned itself off. The codes are undocumented; reported for diagnosis. */
    val lastShutdownReason: Int,
    /** The second half of the status packet arrives separately and may lag the first. */
    val extra: Extra?,
) {
    data class Extra(
        val ledEnabled: Boolean,
        val beepsMuted: Boolean,
        /** Non-zero (1-based) while an on-device program is running. */
        val programStep: Int,
        /** One of [Codec.Notification]. The unit repeats it until acknowledged. */
        val notification: Int,
    )

    val fanPercent: Int get() = (fanStep + 1) * 5
    val isRunning: Boolean get() = mode != Mode.STANDBY
}
