package io.github.willzy12h.nocturne.core.link

import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.protocol.ProgramStep
import io.github.willzy12h.nocturne.core.protocol.StoredProgram
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.ZoneId
import java.time.ZonedDateTime

class ControllerTest {
    private var now = ZonedDateTime.of(2026, 10, 21, 22, 30, 0, 0, ZoneId.of("Europe/London"))
    private fun demo(slots: List<StoredProgram?> = listOf(null, null, null)) = DemoBedJet(slots) { now }

    @Test
    fun storeAndReadBackKeepsOtherSlots() = runTest {
        val old = StoredProgram("old", listOf(ProgramStep(Mode.DRY, 1, 60, 1, 0)))
        val link = demo(listOf(old, null, null))
        val controller = BedJetController(link) { now }
        link.connect()
        val steps = (0 until 11).map { ProgramStep(if (it % 2 == 0) Mode.COOL else Mode.HEAT, it, 50, 0, 20 + it) }
        controller.store(StoredProgram("Deep Sleep", steps), slot = 1)
        val stored = controller.storedPrograms()
        assertEquals("old", stored[0]?.name)
        assertEquals(StoredProgram("Deep Sleep", steps), stored[1])
        assertNull(stored[2])
    }

    @Test
    fun startingSyncsTheClockAndRunsTheFirstStep() = runTest {
        val link = demo()
        val controller = BedJetController(link) { now }
        link.connect()
        controller.store(StoredProgram("Night", listOf(ProgramStep(Mode.COOL, 5, 44, 2, 0))), slot = 2)
        controller.startProgram(slot = 2)
        assertEquals(22 to 30, link.clock)
        val status = link.status.value!!
        assertEquals(Mode.COOL, status.mode)
        assertEquals(5, status.fanStep)
        assertEquals(1, status.extra?.programStep)
    }

    @Test
    fun programTooLongIsReportedAndAcknowledged() = runTest {
        val tooHot = ProgramStep(Mode.HEAT, 19, 80, 3, 0)
        val link = demo(listOf(StoredProgram("too hot", listOf(tooHot)), null, null))
        val controller = BedJetController(link) { now }
        link.connect()
        assertThrows<BedJetError.ProgramTooLong> { controller.startProgram(slot = 0) }
        assertEquals(Mode.STANDBY, link.status.value!!.mode)
        // The controller clears the notification so the unit stops repeating it.
        assertEquals(0, link.status.value!!.extra?.notification)
    }

    @Test
    fun storeRejectsStepsTheUnitWouldStop() = runTest {
        val link = demo()
        link.connect()
        assertThrows<BedJetError.ProgramTooLong> {
            BedJetController(link) { now }.store(StoredProgram("too hot", listOf(ProgramStep(Mode.HEAT, 19, 80, 3, 0))), 0)
        }
        assertNull(link.slots[0])
    }

    @Test
    fun verifyFailsOnMismatch() = runTest {
        val link = CorruptingLink(demo())
        link.connect()
        assertThrows<BedJetError.VerifyFailed> {
            BedJetController(link) { now }.store(StoredProgram("Night", listOf(ProgramStep(Mode.COOL, 5, 44, 2, 0))), 0)
        }
    }

    @Test
    fun manualRunSetsModeTemperatureFanAndTimer() = runTest {
        val link = demo()
        link.connect()
        BedJetController(link) { now }.runManual(Mode.HEAT, temperature = 66, fanStep = 7, minutes = 30)
        val status = link.status.value!!
        assertEquals(Mode.HEAT, status.mode)
        assertEquals(66, status.targetTemperature)
        assertEquals(7, status.fanStep)
        assertEquals(30 * 60, status.remainingSeconds)
    }

    @Test
    fun runtimeIsClampedToTheModeLimit() = runTest {
        val link = demo()
        link.connect()
        val controller = BedJetController(link) { now }
        controller.setMode(Mode.HEAT)
        controller.setRuntime(600)
        assertEquals(240 * 60, link.status.value!!.remainingSeconds)
    }

    @Test
    fun demoProgramAdvancesWithTime() = runTest {
        val link = demo()
        val controller = BedJetController(link) { now }
        link.connect()
        controller.store(StoredProgram("Night", listOf(ProgramStep(Mode.COOL, 5, 44, 0, 30), ProgramStep(Mode.HEAT, 7, 66, 0, 20))), 2)
        controller.startProgram(2)
        now = now.plusMinutes(31)
        link.tick()
        assertEquals(Mode.HEAT, link.status.value!!.mode)
        assertEquals(2, link.status.value!!.extra?.programStep)
        now = now.plusMinutes(20)
        link.tick()
        assertEquals(Mode.STANDBY, link.status.value!!.mode)
    }

    @Test
    fun compiledNightReachesWakeUpOnTime() = runTest {
        val profile = io.github.willzy12h.nocturne.core.plan.Presets.springAutumn
        val wake = now.plusHours(8)
        val plan = io.github.willzy12h.nocturne.core.plan.PlanCompiler.compile(profile, now, wake)
        val link = demo()
        val controller = BedJetController(link) { now }
        link.connect()
        controller.store(StoredProgram(profile.name, plan.programSteps), 2)
        controller.startProgram(2)
        now = plan.wakeUpStart.minusMinutes(1)
        link.tick()
        assertEquals(Mode.COOL, link.status.value!!.mode)
        now = plan.wakeUpStart.plusSeconds(5)
        link.tick()
        assertEquals(Mode.HEAT, link.status.value!!.mode)
        now = wake.plusSeconds(5)
        link.tick()
        assertEquals(Mode.STANDBY, link.status.value!!.mode)
    }

    /** Flips one byte of every program write, as a dropped or garbled Bluetooth packet would. */
    private class CorruptingLink(private val inner: DemoBedJet) : BedJetLink by inner {
        override suspend fun send(command: ByteArray) {
            val copy = command.copyOf()
            if (copy[0].toInt() == 0x40 && copy[1].toInt() >= 7) copy[4] = (copy[4] + 1).toByte()
            inner.send(copy)
        }
    }

}
