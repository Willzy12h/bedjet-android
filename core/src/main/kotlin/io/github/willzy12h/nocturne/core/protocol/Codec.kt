package io.github.willzy12h.nocturne.core.protocol

/**
 * Byte-level encoding for the BedJet 3 GATT protocol, ported from rkanjani/lull.
 *
 * Commands are written to characteristic 0x2004. Status arrives as notifications on 0x2000,
 * and replies to [query] requests are read from 0x2006.
 */
object Codec {
    object Query {
        const val DEVICE_NAME = 0x00
        const val PROGRAM_NAMES = 0x04
        const val FIRMWARE = 0x20
        fun fragment(slot: Int, index: Int): Int = 7 + slot * 4 + index
    }

    /** Codes the unit raises in byte 29 of its status. */
    object Notification {
        const val CLEAN_FILTER = 1
        const val UPDATE_AVAILABLE = 2
        const val UPDATE_FAILED = 3
        /** A program was started but the unit's clock isn't set, so it didn't run. */
        const val PROGRAM_NEEDS_CLOCK = 4
        /** A program was started with a step longer than the unit allows, so it didn't run. */
        const val PROGRAM_TOO_LONG = 5
    }

    // Commands

    fun setMode(mode: Mode): ByteArray? = mode.button?.let { bytes(0x01, it) }
    fun setRuntime(minutes: Int): ByteArray = bytes(0x02, minutes / 60, minutes % 60)
    fun setTemperature(halfDegrees: Int): ByteArray = bytes(0x03, halfDegrees.coerceIn(0, 255))
    fun setFan(step: Int): ByteArray = bytes(0x07, step.coerceIn(0, 19))
    fun setClock(hour: Int, minute: Int): ByteArray = bytes(0x08, hour, minute)
    fun setLed(on: Boolean): ByteArray = bytes(0x01, if (on) 0x46 else 0x47)
    fun setMuted(muted: Boolean): ByteArray = bytes(0x01, if (muted) 0x48 else 0x49)
    fun startProgram(slot: Int): ByteArray = bytes(0x01, 0x80 + slot)
    fun deleteProgram(slot: Int): ByteArray = bytes(0x01, 0x83 + slot)
    val acknowledgeNotification: ByteArray get() = bytes(0x01, 0x52)
    fun query(request: Int, tag: Int): ByteArray = bytes(0x41, request, tag)

    fun nameProgram(slot: Int, name: String): ByteArray {
        val out = ByteArray(19)
        out[0] = 0x40
        out[1] = (4 + slot).toByte()
        out[2] = 0x10
        sanitize(name).toByteArray(Charsets.US_ASCII).forEachIndexed { i, b -> out[3 + i] = b }
        return out
    }

    /** One write per 8 steps; every fragment repeats the program's total step count. */
    fun writeProgram(slot: Int, steps: List<ProgramStep>): List<ByteArray> {
        val fragments = maxOf(1, (steps.size + Limits.STEPS_PER_FRAGMENT - 1) / Limits.STEPS_PER_FRAGMENT)
        return (0 until fragments).map { index ->
            val out = ByteArray(43)
            out[0] = 0x40
            out[1] = Query.fragment(slot, index).toByte()
            out[2] = steps.size.toByte()
            steps.drop(index * Limits.STEPS_PER_FRAGMENT).take(Limits.STEPS_PER_FRAGMENT).forEachIndexed { i, step ->
                val base = 3 + i * 5
                out[base] = step.mode.code.toByte()
                out[base + 1] = step.fanStep.coerceIn(0, 19).toByte()
                out[base + 2] = step.temperature.coerceIn(0, 255).toByte()
                out[base + 3] = ((step.hours and 0x7f) or (if (step.endsAtClockTime) 0x80 else 0)).toByte()
                out[base + 4] = step.minutes.toByte()
            }
            out
        }
    }

    /** Names are 15 printable ASCII bytes on the unit. */
    fun sanitize(name: String): String = name.filter { it.code in 32..126 }.take(Limits.NAME_LENGTH)

    // Replies

    data class Fragment(val total: Int, val steps: List<ProgramStep>)

    /** Payload of a fragment reply (after the echoed request and tag bytes). */
    fun parseFragment(payload: ByteArray, index: Int): Fragment? {
        val b = payload.u()
        val total = b.firstOrNull() ?: return null
        if (total > Limits.STEPS_PER_PROGRAM) return null
        val expected = minOf(Limits.STEPS_PER_FRAGMENT, maxOf(0, total - index * Limits.STEPS_PER_FRAGMENT))
        if (b.size < 1 + expected * 5) return null
        val steps = (0 until expected).map { i ->
            val base = 1 + i * 5
            ProgramStep(
                mode = Mode.fromCode(b[base]) ?: return null,
                fanStep = b[base + 1],
                temperature = b[base + 2],
                hours = b[base + 3] and 0x7f,
                minutes = b[base + 4],
                endsAtClockTime = b[base + 3] and 0x80 != 0,
            )
        }
        return Fragment(total, steps)
    }

    /** 16-byte name slots. A slot starting with 0x01 has never been saved. */
    fun parseNames(payload: ByteArray): List<String?> =
        payload.toList().chunked(16).map { slot ->
            if (slot.firstOrNull()?.toInt() == 0x01) null
            else String(slot.takeWhile { it.toInt() != 0 }.toByteArray(), Charsets.UTF_8)
        }

    fun parseText(payload: ByteArray): String =
        String(payload.takeWhile { it.toInt() != 0 }.toByteArray(), Charsets.UTF_8)

    // Status

    /** True for a status notification, as opposed to the remainder served by a follow-up read. */
    fun isStatusStart(chunk: ByteArray): Boolean =
        chunk.size >= 20 && chunk[1].toInt() and 0xff == 0x56 && chunk[3].toInt() == 0x01

    fun parseStatus(packet: ByteArray): DeviceStatus? {
        val b = packet.u()
        if (!isStatusStart(packet)) return null
        val mode = Mode.fromCode(b[9]) ?: return null
        // Recognisable framing is not semantic validity. Never publish these as fresh receipts.
        if (b[5] > 59 || b[6] > 59 || b[12] > 59 || b[10] > 19 ||
            b[13] > b[14] || b[14] > 86 || (mode.usesFan && b[8] > 86) ||
            (b.size >= 30 && b[28] > Limits.STEPS_PER_PROGRAM)) return null
        val extra = if (b.size >= 30) {
            DeviceStatus.Extra(
                ledEnabled = b[27] and 0x10 != 0,
                beepsMuted = b[27] and 0x01 != 0,
                programStep = b[28],
                notification = b[29],
            )
        } else null
        return DeviceStatus(
            remainingSeconds = b[4] * 3600 + b[5] * 60 + b[6],
            actualTemperature = b[7],
            targetTemperature = b[8],
            mode = mode,
            fanStep = b[10],
            maxRuntimeMinutes = b[11] * 60 + b[12],
            minTemperature = b[13],
            maxTemperature = b[14],
            ambientTemperature = b[17],
            lastShutdownReason = b[18],
            extra = extra,
        )
    }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
    private fun ByteArray.u(): List<Int> = map { it.toInt() and 0xff }
}
