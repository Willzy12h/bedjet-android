package io.github.willzy12h.nocturne.core.link

import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.protocol.ProgramStep
import io.github.willzy12h.nocturne.core.protocol.StoredProgram
import kotlinx.coroutines.launch
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

    @Test
    fun refusalIsCaughtEvenWhileAnotherProgramIsStillRunning() = runTest {
        val demo = demo()
        demo.connect()
        val controller = BedJetController(LaggingLink(demo, backgroundScope), { now })
        controller.store(StoredProgram("Night", listOf(ProgramStep(Mode.COOL, 5, 44, 2, 0))), 0)
        controller.startProgram(0)
        // The unit refuses the new program but keeps reporting the old one at step 1 for a moment.
        demo.slots[1] = StoredProgram("too hot", listOf(ProgramStep(Mode.HEAT, 19, 80, 3, 0)))
        assertThrows<BedJetError.ProgramTooLong> { controller.startProgram(1) }
    }

    @Test
    fun startingPreparesTheLinkAfterTheClockAndListensOnlyAfterThat() = runTest {
        val link = ScriptedLink()
        assertThrows<BedJetError.Timeout> { BedJetController(link) { now }.startProgram(2) }
        // The preparation ends however the start does, here by timing out.
        assertEquals(listOf("send 08", "prepare", "send 01", "end"), link.calls)
    }

    @Test
    fun aStartRefusedByItsFinalCheckEndsThePreparation() = runTest {
        val link = ScriptedLink()
        assertThrows<IllegalStateException> {
            BedJetController(link) { now }.startProgram(2) { throw IllegalStateException("too late") }
        }
        assertEquals(listOf("send 08", "prepare", "end"), link.calls)
    }

    @Test
    fun aStartCancelledAfterThePreparationEndsIt() = runTest {
        val link = ScriptedLink(holdStart = true)
        val starting = launch { BedJetController(link) { now }.startProgram(2) }
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("send 08", "prepare", "send 01"), link.calls)
        starting.cancel()
        starting.join()
        assertEquals(listOf("send 08", "prepare", "send 01", "end"), link.calls)
    }

    /**
     * Records what the controller asks for and never confirms a start. Preparing publishes a stale program step, as
     * a report racing the start would: a controller already listening would take it as confirmation.
     */
    private class ScriptedLink(private val holdStart: Boolean = false) : BedJetLink {
        val calls = mutableListOf<String>()
        private val stale = io.github.willzy12h.nocturne.core.protocol.DeviceStatus(
            remainingSeconds = 0, actualTemperature = 46, targetTemperature = 60, mode = Mode.COOL, fanStep = 7,
            maxRuntimeMinutes = 0, minTemperature = 38, maxTemperature = 80, ambientTemperature = 45, lastShutdownReason = 0,
            extra = io.github.willzy12h.nocturne.core.protocol.DeviceStatus.Extra(true, false, programStep = 1, notification = 0),
        )
        override val state = kotlinx.coroutines.flow.MutableStateFlow(LinkState.READY)
        override val status = kotlinx.coroutines.flow.MutableStateFlow<io.github.willzy12h.nocturne.core.protocol.DeviceStatus?>(null)
        override suspend fun connect() = Unit
        override suspend fun disconnect() = Unit
        override suspend fun send(command: ByteArray) {
            calls += "send %02x".format(command[0])
            if (holdStart && command[0].toInt() == 0x01) kotlinx.coroutines.awaitCancellation()
        }
        override suspend fun prepareForStart() {
            calls += "prepare"
            status.value = stale
        }
        override suspend fun endStartPreparation() {
            calls += "end"
        }
        override suspend fun query(request: Int) = ByteArray(0)
    }

    /** Reports status a moment after it changes, as the real unit's notifications do. */
    private class LaggingLink(private val inner: DemoBedJet, scope: kotlinx.coroutines.CoroutineScope) : BedJetLink by inner {
        private val lagged = kotlinx.coroutines.flow.MutableStateFlow(inner.status.value)
        override val status: kotlinx.coroutines.flow.StateFlow<io.github.willzy12h.nocturne.core.protocol.DeviceStatus?> = lagged

        init {
            scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                inner.status.collect { kotlinx.coroutines.delay(300); lagged.value = it }
            }
        }
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
