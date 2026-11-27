package io.github.willzy12h.nocturne.ui

import io.github.willzy12h.nocturne.core.night.ActiveNight
import io.github.willzy12h.nocturne.core.night.AppliedProgram
import io.github.willzy12h.nocturne.core.night.ChangeKind
import io.github.willzy12h.nocturne.core.night.Confidence
import io.github.willzy12h.nocturne.core.night.PendingChange
import io.github.willzy12h.nocturne.core.plan.Block
import io.github.willzy12h.nocturne.core.plan.Plan
import io.github.willzy12h.nocturne.core.plan.PlannedStep
import io.github.willzy12h.nocturne.core.protocol.DeviceStatus
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.protocol.ProgramStep
import io.github.willzy12h.nocturne.night.NightStatus
import io.github.willzy12h.nocturne.night.PendingRetime
import io.github.willzy12h.nocturne.night.RuntimeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZonedDateTime

class TonightRunningEvidenceTest {
    private val start = ZonedDateTime.parse("2026-11-27T23:30:00Z")
    private val now = start.plusMinutes(10)
    private val wake = start.plusHours(7)
    private val target = "ble:AA:BB:CC:DD:EE:FF"
    private val cool = ProgramStep(Mode.COOL, 7, 44, 1, 0)
    private val plan = Plan(start, wake, listOf(
        PlannedStep(Block.MIDDLE, cool, start, 60),
        PlannedStep(Block.MIDDLE, cool.copy(fanStep = 5, hours = 6), start.plusHours(1), 360),
        PlannedStep(Block.AFTER_WAKE, cool.copy(hours = 0, minutes = 10), wake, 10),
    ))
    private val night = ActiveNight("record", "profile", start.toString(), wake.toString(), true, 0,
        wake.toString(), plan.end.toString(), deviceId = target, applied = AppliedProgram.from(plan), confidence = Confidence.CONFIRMED)
    private val reported = DeviceStatus(3000, 45, 44, Mode.COOL, 7, 719, 38, 86, 46, 0,
        DeviceStatus.Extra(true, false, 1, 0))

    @Test fun aFreshMatchingReceiptCanPlaceTheCurrentMarker() {
        val evidence = tonightRunningEvidence(night, Confidence.CONFIRMED, target, reported, now)
        assertEquals(0, evidence.stepIndex)
        assertEquals(reported, evidence.currentReading)
        assertEquals(now, evidence.markerAt)
        assertEquals(plan, evidence.confirmedPlan)
    }

    @Test fun aSavedPlanAloneCannotPlaceTheCurrentMarker() {
        val evidence = tonightRunningEvidence(night, Confidence.CONFIRMED, target, null, now)
        assertNull(evidence.stepIndex)
        assertNull(evidence.currentReading)
        assertNull(evidence.markerAt)
        assertEquals(plan, evidence.confirmedPlan)
    }

    @Test fun changedFanOrActiveStepCannotBePresentedAsThisNight() {
        val mismatches = listOf(reported.copy(fanStep = 8), reported.copy(extra = reported.extra?.copy(programStep = 2)))
        for (report in mismatches) {
            val evidence = tonightRunningEvidence(night, Confidence.CONFIRMED, target, report, now)
            assertNull(evidence.stepIndex)
            assertNull(evidence.currentReading)
        }
    }

    @Test fun anotherTargetOrPendingWriteCannotSupplyAConfirmedPlan() {
        assertNull(tonightRunningEvidence(night, Confidence.CONFIRMED, "ble:11:22:33:44:55:66", reported, now).confirmedPlan)
        val pending = night.copy(confidence = Confidence.PENDING)
        assertNull(tonightRunningEvidence(pending, Confidence.PENDING, target, reported, now).confirmedPlan)
    }

    @Test fun aPendingAlarmShowsTheRequestedWakeSeparatelyFromTheLastConfirmedPlan() {
        val moved = wake.minusMinutes(30)
        val queued = PendingRetime("change", night.identity, target, moved.toString(), "clock", wake.toString())
        val pending = pendingTonightWakeChange(night, NightStatus(Confidence.PENDING, null),
            RuntimeState(retime = queued), target, moved)
        assertEquals(moved, pending?.requestedWake)
        assertEquals(true, pending?.isPhoneAlarm)
        assertEquals(wake, pending?.lastConfirmedPlan?.wake)
        assertEquals(plan.end, pending?.lastConfirmedPlan?.end)
    }

    @Test fun retimeInFlightRetainsTheAppliedSnapshotAndRejectsObsoleteRequests() {
        val moved = wake.minusMinutes(30)
        val requesting = night.copy(confidence = Confidence.PENDING, pending = PendingChange("change", ChangeKind.RETIME,
            moved.toString(), now.toString(), wake.toString(), night.identity))
        val status = NightStatus(Confidence.PENDING, null)
        assertEquals(plan, pendingTonightWakeChange(requesting, status, RuntimeState(), target, moved)?.lastConfirmedPlan)
        val obsolete = PendingRetime("change", night.identity.copy(revision = night.revision + 1), target,
            moved.toString(), "clock", wake.toString())
        assertNull(pendingTonightWakeChange(night, status, RuntimeState(retime = obsolete), target, moved))
    }

    @Test fun aFreshStartDoesNotInheritAConfirmedProgramme() {
        val starting = night.copy(applied = AppliedProgram(), confidence = Confidence.PENDING,
            pending = PendingChange("start", ChangeKind.START, wake.toString(), now.toString(), wake.toString()))
        val status = NightStatus(Confidence.PENDING, null)
        assertNull(pendingTonightWakeChange(starting, status, RuntimeState(), target, wake))
        assertNull(tonightRunningEvidence(starting, status.confidence, target, reported, now).confirmedPlan)
    }
}
