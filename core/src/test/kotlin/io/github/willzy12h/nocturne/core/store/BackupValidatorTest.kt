package io.github.willzy12h.nocturne.core.store

import io.github.willzy12h.nocturne.core.plan.Phase
import io.github.willzy12h.nocturne.core.plan.Presets
import io.github.willzy12h.nocturne.core.plan.TonightChoice
import io.github.willzy12h.nocturne.core.plan.TonightOverride
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
        assertRejected(good.copy(settings = Settings(tonightChoice = TonightChoice("p", "tomorrow"))), "profile expiry")
        assertRejected(good.copy(settings = Settings(tonightOverride = TonightOverride("six", now))), "wake time")
        assertRejected(good.copy(settings = Settings(tonightOverride = TonightOverride(now, "yesterday"))), "selection time")
    }

    @Test
    fun importedSlotStepsMustFitTheProtocol() {
        val original = good.slotBackups.first()
        assertRejected(good.copy(slotBackups = listOf(original.copy(program = StoredProgram("old", listOf(
            ProgramStep(Mode.COOL, 20, 44, 1, 0)
        ))))), "fan")
        assertRejected(good.copy(slotBackups = listOf(original.copy(program = StoredProgram("old", listOf(
            ProgramStep(Mode.COOL, 1, 44, 12, 0)
        ))))), "time")
        assertRejected(good.copy(slotBackups = listOf(original.copy(program = StoredProgram("old", List(33) {
            ProgramStep(Mode.DRY, 1, 60, 1, 0)
        })))), "at most 32 steps")
        assertEquals(emptyList<String>(), errors(good.copy(slotBackups = listOf(original.copy(program = StoredProgram("old", emptyList()))))))
    }

    /**
     * A recorded original is what the BedJet held, written back as it is: only what the codec can carry is checked,
     * never the official app's per-mode range or its heat cut-off table, or the app would refuse its own snapshots.
     */
    @Test
    fun aRecordedOriginalOutsideTheModeRangeStillPasses() {
        val original = good.slotBackups.first()
        val hot = original.copy(program = StoredProgram("bedjet", listOf(
            ProgramStep(Mode.HEAT, 5, 200, 1, 0),
            ProgramStep(Mode.COOL, 19, 10, 0, 30),
            ProgramStep(Mode.HEAT, 19, 80, 11, 0),
        )))
        assertEquals(emptyList<String>(), errors(good.copy(slotBackups = listOf(hot))))
        assertRejected(good.copy(slotBackups = listOf(original.copy(program = StoredProgram("bad", listOf(
            ProgramStep(Mode.HEAT, 5, 256, 1, 0)
        ))))), "temperature")
        assertRejected(good.copy(slotBackups = listOf(original.copy(program = StoredProgram("bad", listOf(
            ProgramStep(Mode.HEAT, 5, -1, 1, 0)
        ))))), "temperature")
    }

    /** IR-16: a saved original names the demo unit, a BedJet saved before devices were recorded, or a real one. */
    @Test
    fun aSlotBackupMustNameABedJet() {
        val original = good.slotBackups.first()
        assertRejected(good.copy(slotBackups = listOf(original.copy(deviceId = "toaster"))), "toaster", "isn't a BedJet")
        assertRejected(good.copy(slotBackups = listOf(original.copy(deviceId = "ble:"))), "isn't a BedJet")
        listOf("demo", "unknown", "ble:AA:BB:CC:DD:EE:FF").forEach { id ->
            assertEquals(emptyList<String>(), errors(good.copy(slotBackups = listOf(original.copy(deviceId = id)))), id)
        }
    }

    @Test
    fun settingsRanges() {
        assertRejected(good.copy(settings = Settings(boostMinutes = 0)), "boost")
        assertRejected(good.copy(settings = Settings(extendWarm = ActionPreset(Mode.HEAT, 64, 30, 0))), "minutes")
        assertRejected(good.copy(settings = Settings(notificationActions = WakeAction.entries)), "notification")
        assertRejected(good.copy(settings = Settings(scheduledDarkMinute = 1440)), "theme")
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
}
