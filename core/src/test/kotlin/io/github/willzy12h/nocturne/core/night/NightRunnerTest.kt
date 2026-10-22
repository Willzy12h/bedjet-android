package io.github.willzy12h.nocturne.core.night

import io.github.willzy12h.nocturne.core.link.BedJetController
import io.github.willzy12h.nocturne.core.link.DemoBedJet
import io.github.willzy12h.nocturne.core.plan.Presets
import io.github.willzy12h.nocturne.core.plan.ProfileLimits
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.protocol.ProgramStep
import io.github.willzy12h.nocturne.core.protocol.StoredProgram
import io.github.willzy12h.nocturne.core.store.ActionPreset
import io.github.willzy12h.nocturne.core.store.EventKind
import io.github.willzy12h.nocturne.core.store.SlotBackup
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class NightRunnerTest {
    private val zone = ZoneId.of("Europe/London")
    private var now = ZonedDateTime.of(2026, 10, 21, 22, 30, 0, 0, zone)
    private val alarm = ZonedDateTime.of(2026, 10, 22, 6, 45, 0, 0, zone)
    private val profile = Presets.springAutumn
    private val mine = StoredProgram("From BedJet app", listOf(ProgramStep(Mode.DRY, 1, 60, 1, 0)))
    private val link = DemoBedJet(listOf(null, null, mine)) { now }
    private val controller = BedJetController(link) { now }
    private val runner = NightRunner(controller) { now }
    private val fixture = NightFixture(runner, controller) { now }

    @Test
    fun startWritesPlanToWorkingSlotAndBacksUpWhatWasThere() = runTest {
        link.connect()
        val result = fixture.start(profile, alarm, slot = 2, alreadyBackedUp = emptyList())
        assertEquals(SlotBackup("unknown", 2, mine, now.toString()), result.slotBackup)
        assertEquals(alarm.toString(), result.night.wakeAt)
        assertTrue(result.night.wakeFromAlarm)
        assertEquals(profile.name, link.slots[2]!!.name)
        assertEquals(result.plan.programSteps, link.slots[2]!!.steps)
        assertEquals(1, link.status.value!!.extra!!.programStep)
        assertEquals(EventKind.STARTED, result.record.events.single().kind)
        assertEquals(result.plan.steps.size, result.record.steps.size)
    }

    @Test
    fun startDoesNotBackUpTheSameSlotTwice() = runTest {
        link.connect()
        val result = fixture.start(profile, alarm, slot = 2, alreadyBackedUp = listOf(SlotBackup("unknown", 2, mine, "earlier")))
        assertNull(result.slotBackup)
    }

    @Test
    fun anotherDevicesBackupOfTheSlotDoesNotCount() = runTest {
        link.connect()
        val result = fixture.start(profile, alarm, slot = 2, alreadyBackedUp = listOf(SlotBackup("demo", 2, mine, "earlier")), deviceId = "ble:AA:BB")
        assertEquals(SlotBackup("ble:AA:BB", 2, mine, now.toString()), result.slotBackup)
        assertEquals("ble:AA:BB", result.record.deviceId)
    }

    @Test
    fun retimeMovesTheWakeUp() = runTest {
        link.connect()
        val night = fixture.start(profile, alarm, 2, emptyList()).night
        now = now.plusHours(2)
        val earlier = alarm.minusMinutes(30)
        val result = fixture.retime(profile, night, earlier)!!
        assertEquals(earlier.toString(), result.night.wakeAt)
        assertEquals(night.startedAt, result.night.startedAt)
        assertEquals(result.plan.programSteps, link.slots[2]!!.steps)
        assertEquals(EventKind.RETIMED, result.event.kind)
    }

    @Test
    fun retimeIgnoredWhenUnchangedOrWakeUpHasBegun() = runTest {
        link.connect()
        val night = fixture.start(profile, alarm, 2, emptyList()).night
        now = now.plusHours(1)
        assertNull(fixture.retime(profile, night, alarm))
        now = ZonedDateTime.parse(night.wakeUpStart).plusMinutes(1)
        assertNull(fixture.retime(profile, night, alarm.plusMinutes(30)))
    }

    @Test
    fun forcedRetimeAppliesProfileEditsToTonightWithTheSameWakeTime() = runTest {
        link.connect()
        val night = fixture.start(profile, alarm, 2, emptyList()).night
        now = now.plusHours(2)
        val edited = profile.copy(middle = profile.middle.copy(fanStart = 60, fanEnd = 60, rampSteps = 1))
        assertNull(fixture.retime(edited, night, alarm))
        val result = fixture.retime(edited, night, alarm, force = true)!!
        assertEquals(alarm.toString(), result.night.wakeAt)
        val middle = result.plan.steps.filter { it.block == io.github.willzy12h.nocturne.core.plan.Block.MIDDLE }
        assertTrue(middle.all { (it.step.fanStep + 1) * 5 == 60 })
        assertEquals(result.plan.programSteps, link.slots[2]!!.steps)
    }

    @Test
    fun alarmRemovedFallsBackToTheDefaultWakeTime() = runTest {
        link.connect()
        val night = fixture.start(profile, alarm, 2, emptyList()).night
        now = now.plusHours(1)
        val result = fixture.retime(profile, night, alarm = null)!!
        assertEquals(ZonedDateTime.of(2026, 10, 22, 7, 0, 0, 0, zone).toString(), result.night.wakeAt)
        assertFalse(result.night.wakeFromAlarm)
    }

    @Test
    fun alarmMovedSoonDuringTheNightIsStillFollowed() = runTest {
        link.connect()
        val night = fixture.start(profile, alarm, 2, emptyList()).night
        now = alarm.minusMinutes(90)
        val soon = now.plusMinutes(40)
        assertEquals(soon.toString(), fixture.retime(profile, night, soon)!!.night.wakeAt)
    }

}
