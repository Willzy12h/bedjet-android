package io.github.willzy12h.nocturne.core.protocol

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Byte fixtures were captured from a BedJet 3 on firmware V1.20 (published with rkanjani/lull). */
class CodecTest {
    private fun bytes(hex: String) = hex.split(" ").map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun parsesStatusCapturedFromDevice() {
        val status = Codec.parseStatus(bytes("01 56 1b 01 00 00 00 2e 4f 00 13 00 00 14 50 00 00 2d 00 12 01 9a 01 10 10 00 15 35 00 01 53"))!!
        assertEquals(Mode.STANDBY, status.mode)
        assertEquals(46, status.actualTemperature)
        assertEquals(79, status.targetTemperature)
        assertEquals(100, status.fanPercent)
        assertEquals(45, status.ambientTemperature)
        assertEquals(0, status.lastShutdownReason)
        val extra = status.extra!!
        assertEquals(0, extra.programStep)
        assertTrue(extra.ledEnabled)
        assertTrue(extra.beepsMuted)
        assertEquals(Codec.Notification.CLEAN_FILTER, extra.notification)
    }

    @Test
    fun firstChunkAloneStillParses() {
        val head = bytes("01 56 1b 01 00 00 00 2e 4f 00 13 00 00 14 50 00 00 2d 00 12")
        assertTrue(Codec.isStatusStart(head))
        assertNull(Codec.parseStatus(head)!!.extra)
    }

    @Test
    fun parsesProgramCapturedFromDevice() {
        val reply = bytes("07 08 04 04 09 2c 01 00 04 0d 31 03 00 04 09 2e 01 0f 01 04 2f 01 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00")
        val fragment = Codec.parseFragment(reply.copyOfRange(2, reply.size), index = 0)!!
        assertEquals(4, fragment.total)
        assertEquals(
            listOf(
                ProgramStep(Mode.COOL, fanStep = 9, temperature = 44, hours = 1, minutes = 0),
                ProgramStep(Mode.COOL, fanStep = 13, temperature = 49, hours = 3, minutes = 0),
                ProgramStep(Mode.COOL, fanStep = 9, temperature = 46, hours = 1, minutes = 15),
                ProgramStep(Mode.HEAT, fanStep = 4, temperature = 47, hours = 1, minutes = 0),
            ),
            fragment.steps,
        )
    }

    @Test
    fun writingReproducesTheBytesTheDeviceReturned() {
        val reply = bytes("07 08 04 04 09 2c 01 00 04 0d 31 03 00 04 09 2e 01 0f 01 04 2f 01 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00")
        val steps = Codec.parseFragment(reply.copyOfRange(2, reply.size), 0)!!.steps
        val commands = Codec.writeProgram(slot = 0, steps = steps)
        assertEquals(1, commands.size)
        assertArrayEquals(byteArrayOf(0x40, 0x07), commands[0].copyOfRange(0, 2))
        assertArrayEquals(reply.copyOfRange(2, reply.size), commands[0].copyOfRange(2, commands[0].size))
    }

    @Test
    fun parsesNamesCapturedFromDevice() {
        val reply = bytes("04 05 63 68 6f 74 65 20 6d 6f 74 65 7a 00 00 00 00 00 01 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 73 6c 65 65 65 65 70 00 00 00 00 00 00 00 00 00")
        assertEquals(listOf("chote motez", null, "sleeeep"), Codec.parseNames(reply.copyOfRange(2, reply.size)))
    }

    @Test
    fun longProgramsSplitIntoFragments() {
        val steps = (0 until 19).map { ProgramStep(Mode.COOL, fanStep = it, temperature = 40, hours = 0, minutes = 30) }
        val commands = Codec.writeProgram(slot = 2, steps = steps)
        assertEquals(listOf(15, 16, 17), commands.map { it[1].toInt() })
        assertTrue(commands.all { it.size == 43 && it[2].toInt() == 19 })
        assertEquals(steps.drop(16), Codec.parseFragment(commands[2].copyOfRange(2, 43), 2)!!.steps)
    }

    @Test
    fun clockTimeFlagSharesTheHoursByte() {
        val step = ProgramStep(Mode.COOL, fanStep = 14, temperature = 36, hours = 6, minutes = 30, endsAtClockTime = true)
        val command = Codec.writeProgram(0, listOf(step))[0]
        assertArrayEquals(bytes("04 0e 24 86 1e"), command.copyOfRange(3, 8))
        assertEquals(listOf(step), Codec.parseFragment(command.copyOfRange(2, command.size), 0)!!.steps)
    }

    @Test
    fun nameCommandIsPaddedAndTrimmed() {
        val command = Codec.nameProgram(slot = 1, name = "A very long schedule name ✨")
        assertEquals(19, command.size)
        assertArrayEquals(bytes("40 05 10"), command.copyOfRange(0, 3))
        assertEquals("A very long sch", Codec.parseText(command.copyOfRange(3, command.size)))
    }

    @Test
    fun simpleCommandBytes() {
        assertArrayEquals(bytes("02 02 1e"), Codec.setRuntime(150))
        assertArrayEquals(bytes("01 02"), Codec.setMode(Mode.COOL))
        assertNull(Codec.setMode(Mode.WAIT))
        assertArrayEquals(bytes("07 13"), Codec.setFan(40))
        assertArrayEquals(bytes("08 16 2d"), Codec.setClock(22, 45))
        assertArrayEquals(bytes("01 82"), Codec.startProgram(2))
        assertArrayEquals(bytes("41 04 07"), Codec.query(Codec.Query.PROGRAM_NAMES, tag = 7))
    }

    /** Values follow the heat table in the official BedJet app (v1.0.6), which keeps the unit's safety cut-off. */
    @Test
    fun heatIsCutOffSoonerWhenHotterAndFaster() {
        assertEquals(60, ProgramStep.longestMinutes(Mode.HEAT, 80, 19))
        assertEquals(120, ProgramStep.longestMinutes(Mode.HEAT, 75, 19))
        assertEquals(240, ProgramStep.longestMinutes(Mode.HEAT, 80, 0))
        assertEquals(60, ProgramStep.longestMinutes(Mode.HEAT, 81, 0))
        assertEquals(Limits.MAX_STEP_MINUTES, ProgramStep.longestMinutes(Mode.HEAT, 66, 19))
        assertEquals(Limits.MAX_STEP_MINUTES, ProgramStep.longestMinutes(Mode.HEAT, 50, 19))
        assertEquals(Limits.MAX_STEP_MINUTES, ProgramStep.longestMinutes(Mode.COOL, 80, 19))
        assertFalse(ProgramStep(Mode.HEAT, 19, 80, 2, 0).fitsUnitLimits)
        assertTrue(ProgramStep(Mode.HEAT, 19, 80, 1, 0).fitsUnitLimits)
        assertTrue(ProgramStep(Mode.HEAT, 19, 80, 6, 0, endsAtClockTime = true).fitsUnitLimits)
    }
}
