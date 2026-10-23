package io.github.willzy12h.nocturne.core.store

import io.github.willzy12h.nocturne.core.plan.Phase
import io.github.willzy12h.nocturne.core.plan.Presets
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.protocol.ProgramStep
import io.github.willzy12h.nocturne.core.protocol.StoredProgram
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.io.InputStream

class BackupValidatorTest {
    private val now = "2026-10-21T22:30+01:00[Europe/London]"
    private val good = BackupBundle(
        createdAt = now,
        profiles = Presets.all,
        settings = Settings(),
        slotBackups = listOf(
            SlotBackup("ble:AA:BB", 2, StoredProgram("old", listOf(ProgramStep(Mode.DRY, 1, 60, 1, 0))), now),
            SlotBackup("demo", 1, null, now),
        ),
        history = listOf(
            NightRecord(
                id = "n1", profileId = "p", profileName = "P", startedAt = now, wakeAt = now, wakeFromAlarm = true,
                events = listOf(HistoryEvent(now, EventKind.STARTED, "Started")),
            ),
        ),
    )

    private fun errors(bundle: BackupBundle) = BackupValidator.validate(bundle)

    private fun assertRejected(bundle: BackupBundle, vararg words: String) {
        val errors = errors(bundle)
        assertTrue(errors.isNotEmpty(), "expected errors")
        assertTrue(errors.any { e -> words.all { e.contains(it, ignoreCase = true) } }, "no error mentioning ${words.toList()} in $errors")
    }

    private val summer = Presets.summerCool

    @Test
    fun aGoodBundleAndTheDefaultsPass() {
        assertEquals(emptyList<String>(), errors(good))
    }

    @Test
    fun newerSchema() = assertRejected(good.copy(schema = BackupCodec.SCHEMA + 1), "newer")

    @Test
    fun duplicateProfileIds() = assertRejected(good.copy(profiles = listOf(summer, summer.copy(name = "Copy"))), "preset-summer", "more than once")

    @Test
    fun phaseMinutes() {
        assertRejected(good.copy(profiles = listOf(summer.copy(start = listOf(Phase(Mode.COOL, 44, 70, 0))))), "minutes")
        assertRejected(good.copy(profiles = listOf(summer.copy(wake = listOf(Phase(Mode.HEAT, 60, 30, 721))))), "minutes")
        assertEquals(emptyList<String>(), errors(good.copy(profiles = listOf(summer.copy(afterWake = listOf(Phase(Mode.COOL, 44, 40, 720)))))))
    }

    @Test
    fun fan() {
        assertRejected(good.copy(profiles = listOf(summer.copy(start = listOf(Phase(Mode.COOL, 44, 4, 30))))), "fan")
        assertRejected(good.copy(profiles = listOf(summer.copy(middle = summer.middle.copy(fanEnd = 101)))), "fan")
        assertRejected(good.copy(settings = Settings(snoozeWarm = ActionPreset(Mode.HEAT, 66, 0, 10))), "fan")
    }

    @Test
    fun temperatureOutsideTheModeRange() {
        // Cool goes no warmer than 31 °C (62 half-degrees).
        assertRejected(good.copy(profiles = listOf(summer.copy(start = listOf(Phase(Mode.COOL, 70, 50, 30))))), "temperature")
        assertRejected(good.copy(profiles = listOf(summer.copy(middle = summer.middle.copy(tempStart = 20)))), "temperature")
        assertRejected(good.copy(settings = Settings(extendCool = ActionPreset(Mode.COOL, 80, 40, 30))), "temperature")
    }

    @Test
    fun rampSteps() {
        assertRejected(good.copy(profiles = listOf(summer.copy(middle = summer.middle.copy(rampSteps = 0)))), "ramp")
        assertRejected(good.copy(profiles = listOf(summer.copy(middle = summer.middle.copy(rampSteps = 9)))), "ramp")
    }

    @Test
    fun slot() {
        assertRejected(good.copy(settings = Settings(workingSlot = 3)), "slot")
        assertRejected(good.copy(slotBackups = listOf(SlotBackup("demo", -1, null, now))), "slot")
    }

    @Test
    fun timesThatDoNotParse() {
        assertRejected(good.copy(createdAt = "yesterday"), "time")
        assertRejected(good.copy(history = listOf(good.history.single().copy(startedAt = "22:30"))), "time")
        assertRejected(good.copy(history = listOf(good.history.single().copy(events = listOf(HistoryEvent("soon", EventKind.ERROR, "x"))))), "time")
        assertRejected(good.copy(slotBackups = listOf(SlotBackup("demo", 1, null, "2026-13-01"))), "time")
    }

    @Test
    fun settingsRanges() {
        assertRejected(good.copy(settings = Settings(boostMinutes = 0)), "boost")
        assertRejected(good.copy(settings = Settings(extendWarm = ActionPreset(Mode.HEAT, 64, 30, 0))), "minutes")
        assertRejected(good.copy(settings = Settings(notificationActions = WakeAction.entries)), "notification")
    }

    @Test
    fun errorsAreReadableSentences() {
        val errors = errors(good.copy(profiles = listOf(summer.copy(middle = summer.middle.copy(rampSteps = 9)))))
        assertEquals(listOf("Summer Cool: the middle has 9 ramp steps; it must be 1 to 8."), errors)
    }

    @Test
    fun anOversizedFileIsRefusedBeforeItIsDecoded() {
        var read = 0L
        // Not JSON at all: decoding it would fail with a different message.
        val huge = object : InputStream() {
            override fun read(): Int = if (read++ < BackupValidator.MAX_BYTES + 1_000_000L) 'x'.code else -1
        }
        val e = assertThrows<BackupException> { BackupValidator.readWithinLimit(huge) }
        assertTrue(e.message!!.contains("5 MB"), e.message)
        // It stopped reading just past the limit.
        assertTrue(read <= BackupValidator.MAX_BYTES + 8_192L + 1, "read $read bytes")
    }

    @Test
    fun aFileWithinTheLimitIsReadWhole() {
        val text = BackupCodec.encode(good)
        assertEquals(text, BackupValidator.readWithinLimit(ByteArrayInputStream(text.toByteArray())))
    }

    // IR-16: a saved programme goes back onto a real BedJet, so it must be one the codec can write as it is.

    private fun withSlotProgram(vararg steps: ProgramStep) =
        good.copy(slotBackups = listOf(SlotBackup("ble:AA:BB", 1, StoredProgram("saved", steps.toList()), now)))

    @Test
    fun slotBackupProgramOutOfRangeIsRefused() {
        assertRejected(withSlotProgram(ProgramStep(Mode.DRY, 40, 60, 1, 0)), "slot 2", "fan")
        assertRejected(withSlotProgram(ProgramStep(Mode.HEAT, 5, 200, 1, 0)), "slot 2", "100 °C")
        assertRejected(withSlotProgram(ProgramStep(Mode.TURBO, 5, 300, 0, 10)), "slot 2", "temperature")
        assertRejected(withSlotProgram(ProgramStep(Mode.COOL, 5, 44, 0, 75)), "slot 2", "minutes")
        assertRejected(withSlotProgram(ProgramStep(Mode.COOL, 5, 44, 12, 0)), "slot 2", "12 hours")
        assertRejected(withSlotProgram(ProgramStep(Mode.COOL, 5, 44, 24, 0, endsAtClockTime = true)), "slot 2", "time of day")
        assertRejected(withSlotProgram(*Array(33) { ProgramStep(Mode.COOL, 5, 44, 0, 10) }), "slot 2", "33 steps")
    }

    @Test
    fun aSlotBackupProgramWithinTheCodecsLimitsPasses() {
        assertEquals(emptyList<String>(), errors(withSlotProgram(*Array(32) { ProgramStep(Mode.HEAT, 19, 80, 11, 59) })))
        assertEquals(emptyList<String>(), errors(withSlotProgram(ProgramStep(Mode.TURBO, 0, 86, 0, 10), ProgramStep(Mode.COOL, 0, 44, 6, 30, endsAtClockTime = true))))
        // Version 1 kept programmes with no steps; they still restore.
        assertEquals(emptyList<String>(), errors(withSlotProgram()))
    }

    @Test
    fun aSlotBackupMustBeConsistentAndNameABedJet() {
        assertRejected(good.copy(slotBackups = listOf(SlotBackup("ble:AA:BB", 1, null, now, empty = false))), "slot 2", "empty")
        assertRejected(good.copy(slotBackups = listOf(SlotBackup("ble:AA:BB", 1, StoredProgram("x", emptyList()), now, empty = true))), "slot 2", "empty")
        assertRejected(good.copy(slotBackups = listOf(SlotBackup("toaster", 1, null, now))), "slot 2", "toaster")
        assertRejected(good.copy(slotBackups = listOf(SlotBackup("ble:", 1, null, now))), "slot 2", "ble:")
        assertEquals(emptyList<String>(), errors(good.copy(slotBackups = listOf(SlotBackup("unknown", 1, null, now)))))
    }

    @Test
    fun uncertainAndFromBackupOriginalsAreAccepted() {
        val flagged = good.slotBackups.map { it.copy(uncertain = true, fromBackup = true) }
        assertEquals(emptyList<String>(), errors(good.copy(slotBackups = flagged)))
    }
}
