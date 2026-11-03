package io.github.willzy12h.nocturne.core.link

import io.github.willzy12h.nocturne.core.protocol.Codec
import io.github.willzy12h.nocturne.core.protocol.DeviceStatus
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.protocol.ProgramStep
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.ZonedDateTime

/** A fresh step number alone does not identify the programme that was requested. */
class ProgrammeStartConfirmationTest {
    private val now = ZonedDateTime.parse("2026-10-29T22:00:00Z")

    @Test fun anotherModeCannotConfirmTheStoredFirstStep() = mismatchedFirstStep { it.copy(mode = Mode.HEAT) }
    @Test fun anotherFanSpeedCannotConfirmTheStoredFirstStep() = mismatchedFirstStep { it.copy(fanStep = 7) }
    @Test fun anotherTargetTemperatureCannotConfirmTheStoredFirstStep() = mismatchedFirstStep { it.copy(targetTemperature = 50) }

    private fun mismatchedFirstStep(change: (DeviceStatus) -> DeviceStatus) = runTest {
        val link = ReportsLink()
        link.afterStart = { link.report(change(link.matching)) }
        val outcome = runCatching { BedJetController(link) { now }.startProgram(2) }
        assertTrue(outcome.exceptionOrNull() is BedJetError,
            "A different first step must leave the start unconfirmed: $outcome")
        assertEquals(1, link.starts)
        assertTrue(link.preparationReleased)
    }

    @Test fun countdownAndActualTemperatureDoNotHaveToEqualTheStoredStep() = runTest {
        val link = ReportsLink()
        link.afterStart = { link.report(link.matching.copy(remainingSeconds = 3598, actualTemperature = 49)) }
        BedJetController(link) { now }.startProgram(2)
        assertEquals(1, link.starts)
        assertTrue(link.preparationReleased)
    }

    @Test fun pauseDoesNotCompareUnusedFanOrTemperatureFields() = runTest {
        val link = ReportsLink(ProgramStep(Mode.WAIT, 0, 80, 1, 0))
        link.afterStart = { link.report(link.matching.copy(fanStep = 7, targetTemperature = 44)) }
        BedJetController(link) { now }.startProgram(2)
        assertEquals(1, link.starts)
    }

    @Test fun aNewStandbyDuringTheConfirmationGraceCannotLeaveTheStartConfirmed() = runTest {
        val link = ReportsLink()
        link.afterStart = {
            link.report(link.matching)
            launch {
                delay(500)
                link.report(link.matching.copy(mode = Mode.STANDBY, remainingSeconds = 0,
                    extra = link.matching.extra!!.copy(programStep = 0)))
            }
        }
        val outcome = runCatching { BedJetController(link) { now }.startProgram(2) }
        assertEquals(Mode.STANDBY, link.latestSourcedStatus()!!.status.mode)
        assertTrue(outcome.exceptionOrNull() is BedJetError,
            "The current report says Off before confirmation finishes: $outcome")
        assertEquals(1, link.starts, "A conflict must not restart the programme")
    }

    @Test fun cancellationReleasesPreparationWithoutConfirmingOrRetrying() = runTest {
        val link = ReportsLink()
        val starting = async { BedJetController(link) { now }.startProgram(2) }
        testScheduler.runCurrent()
        assertEquals(1, link.starts)
        assertFalse(starting.isCompleted)
        starting.cancelAndJoin()
        assertTrue(starting.isCancelled)
        assertTrue(link.preparationReleased)
        assertEquals(1, link.starts)
    }

    private class ReportsLink(private val first: ProgramStep = ProgramStep(Mode.COOL, 5, 44, 1, 0)) : BedJetLink {
        override val state = MutableStateFlow(LinkState.READY)
        override val status = MutableStateFlow<DeviceStatus?>(null)
        override val sourcedStatus = MutableStateFlow<SourcedStatus?>(null)
        override fun latestSourcedStatus(): SourcedStatus? = sourcedStatus.value
        val matching = DeviceStatus(remainingSeconds = 3600, actualTemperature = 44,
            targetTemperature = first.temperature, mode = first.mode, fanStep = first.fanStep,
            maxRuntimeMinutes = 720, minTemperature = 38, maxTemperature = 86,
            ambientTemperature = 44, lastShutdownReason = 0,
            extra = DeviceStatus.Extra(true, false, programStep = 1, notification = 0))
        var afterStart: suspend () -> Unit = {}
        var starts = 0
        var preparationReleased = false
        fun report(value: DeviceStatus) {
            sourcedStatus.value = SourcedStatus(null, value)
            status.value = value
        }
        override suspend fun connect() = Unit
        override suspend fun disconnect() = Unit
        override suspend fun query(request: Int): ByteArray =
            Codec.writeProgram(2, listOf(first)).single().drop(2).toByteArray()
        override suspend fun send(command: ByteArray) {
            if (command.contentEquals(Codec.startProgram(2))) { starts++; afterStart() }
        }
        override fun cancelPreparedStart() { preparationReleased = true }
    }
}
