package io.github.willzy12h.nocturne.core.link

import io.github.willzy12h.nocturne.core.protocol.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.ZonedDateTime

/** Records outgoing bytes before the demo can apply any firmware-like clamps. */
class CommandSafetySecurityTest {
    private val now = ZonedDateTime.parse("2026-10-27T22:30:00Z")
    private class RecordingLink(val demo: DemoBedJet) : BedJetLink by demo {
        val writes = mutableListOf<ByteArray>()
        override suspend fun send(command: ByteArray) { writes += command.copyOf(); demo.send(command) }
    }
    private suspend fun fixture(): Pair<RecordingLink, BedJetController> {
        val link = RecordingLink(DemoBedJet(now = { now }))
        link.connect()
        return link to BedJetController(link) { now }
    }
    @Test fun turboElevenAnd719MinuteProgrammesAreRefusedBeforeWriting() = runTest {
        for (minutes in listOf(11, 719)) {
            val (link, controller) = fixture()
            assertThrows<BedJetError> {
                controller.store(StoredProgram("edited", listOf(ProgramStep(Mode.TURBO, 19, 86,
                    minutes / 60, minutes % 60))), 0)
            }
            assertTrue(link.writes.isEmpty(), "Unsafe originals must produce zero writes")
            assertNull(link.demo.slots[0])
        }
    }
    @Test fun turboTenMinuteProgrammeStillRoundTrips() = runTest {
        val (link, controller) = fixture()
        val safe = StoredProgram("Turbo", listOf(ProgramStep(Mode.TURBO, 19, 86, 0, 10)))
        controller.store(safe, 0)
        assertEquals(safe, link.demo.slots[0])
    }
    @Test fun aClockEndingTurboCannotBypassItsEffectiveDurationLimit() = runTest {
        val (link, controller) = fixture()
        assertThrows<BedJetError> {
            controller.store(StoredProgram("edited clock", listOf(
                ProgramStep(Mode.TURBO, 19, 86, 23, 0, endsAtClockTime = true))), 0)
        }
        assertTrue(link.writes.isEmpty())
    }
    @Test fun aValidClockEndingCoolProgrammeStillRoundTrips() = runTest {
        val (link, controller) = fixture()
        val safe = StoredProgram("Night", listOf(ProgramStep(Mode.COOL, 5, 50, 7, 0, endsAtClockTime = true)))
        controller.store(safe, 0)
        assertEquals(safe, link.demo.slots[0])
    }
    @Test fun unsafeManualRunsAreRejectedBeforeTheModeCommand() = runTest {
        val cases = listOf(
            ProgramStep(Mode.TURBO, 19, 86, 0, 11),
            ProgramStep(Mode.DRY, 10, 63, 0, 10),
            ProgramStep(Mode.HEAT, 19, 80, 2, 0),
            ProgramStep(Mode.COOL, 20, 50, 0, 10),
            ProgramStep(Mode.COOL, 5, 50, 12, 0),
        )
        for (step in cases) {
            val (link, controller) = fixture()
            assertThrows<BedJetError> {
                controller.runManual(step.mode, step.temperature, step.fanStep, step.durationMinutes)
            }
            assertTrue(link.writes.isEmpty(), "Invalid manual input must not partially start a mode: $step")
        }
    }
    @Test fun malformedWireTimersAndFanValuesCannotBeStored() = runTest {
        val safe = ProgramStep(Mode.COOL, 5, 50, 0, 10)
        for (step in listOf(safe.copy(minutes = 60), safe.copy(hours = -1),
            safe.copy(fanStep = 20), safe.copy(temperature = 255))) {
            val (link, controller) = fixture()
            assertThrows<BedJetError> { controller.store(StoredProgram("edited", listOf(step)), 0) }
            assertTrue(link.writes.isEmpty())
        }
    }
    @Test fun directRuntimeCannotExtendTurboBeyondTenMinutes() = runTest {
        val (link, controller) = fixture()
        controller.runManual(Mode.TURBO, 86, 19, 10)
        link.writes.clear()
        assertThrows<BedJetError> { controller.setRuntime(11) }
        assertTrue(link.writes.isEmpty())
    }
    @Test fun directTemperatureCannotTakeDryAbove31Degrees() = runTest {
        val (link, controller) = fixture()
        controller.runManual(Mode.DRY, 60, 5, 10)
        link.writes.clear()
        assertThrows<BedJetError> { controller.setTemperature(63) }
        assertTrue(link.writes.isEmpty())
    }
}
