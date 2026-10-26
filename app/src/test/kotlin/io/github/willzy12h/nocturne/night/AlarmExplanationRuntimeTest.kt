package io.github.willzy12h.nocturne.night

import io.github.willzy12h.nocturne.GraphLifecycle
import io.github.willzy12h.nocturne.core.night.ActiveNight
import io.github.willzy12h.nocturne.core.night.AppliedProgram
import io.github.willzy12h.nocturne.core.night.ChangeKind
import io.github.willzy12h.nocturne.core.night.Confidence
import io.github.willzy12h.nocturne.core.night.NightIdentity
import io.github.willzy12h.nocturne.core.night.PendingChange
import io.github.willzy12h.nocturne.core.night.WakeOrigin
import io.github.willzy12h.nocturne.core.plan.PlanCompiler
import io.github.willzy12h.nocturne.core.plan.Presets
import io.github.willzy12h.nocturne.core.store.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.ZonedDateTime

/** Pending evidence is scoped to the saved night and target, including before the status flow catches up. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AlarmExplanationRuntimeTest {
    @get:Rule val graphs = GraphLifecycle()
    private val start = ZonedDateTime.parse("2026-10-22T22:30:00Z")
    private val wake = ZonedDateTime.parse("2026-10-23T07:00:00Z")
    private val profile = Presets.all.first()
    private val settings = Settings(demoMode = true)
    private val alarm = AlarmSource.NextAlarm(ZonedDateTime.parse("2026-10-23T06:30:00Z"),
        "com.sec.android.app.clockpackage", true)

    private fun saved(): ActiveNight {
        val plan = PlanCompiler.compile(profile, start, wake)
        return ActiveNight("night", profile.id, start.toString(), wake.toString(), true, 2,
            plan.wakeUpStart.toString(), plan.end.toString(), "demo", profile, revision = 3,
            applied = AppliedProgram.from(plan, revision = 3), confidence = Confidence.CONFIRMED)
    }

    private fun explain(night: ActiveNight, runtime: RuntimeState,
        status: NightStatus = NightStatus(Confidence.CONFIRMED, null),
        nextAlarm: AlarmSource.NextAlarm? = alarm) = AlarmExplanation.resolve(settings, profile,
        start.plusHours(2), nextAlarm, night, runtime, status)

    @Test fun matchingRetimeKeepsItsRequestedWakeSeparateFromTheCurrentAlarm() {
        val night = saved()
        val requested = "2026-10-23T06:15:00Z"
        val result = explain(night, RuntimeState(retime = PendingRetime("request", night.identity, "demo",
            requested, alarm.creator, "2026-10-23T06:00:00Z")))
        assertEquals(WakeExplanationState.PENDING, result.state)
        assertEquals(wake, result.wakeAt)
        assertEquals(ZonedDateTime.parse(requested), result.requestedWakeAt)
        assertEquals(alarm.time, result.alarm!!.time)
        assertFalse(result.matchesSavedWake)
    }

    @Test fun obsoleteRetimeDoesNotUndoTheSavedNightsConfidence() {
        val night = saved()
        val result = explain(night, RuntimeState(retime = PendingRetime("old", NightIdentity(night.recordId, 2),
            "demo", alarm.time.toString(), alarm.creator, "2026-10-23T06:00:00Z")))
        assertEquals(WakeExplanationState.SAVED, result.state)
        assertNull(result.requestedWakeAt)
    }

    @Test fun matchingUnreadObservationIsPendingWithoutInventingARequestedTime() {
        val night = saved()
        val result = explain(night, RuntimeState(alarmInbox = AlarmObservation("observation", night.recordId,
            "demo", start.plusHours(1).toString())))
        assertEquals(WakeExplanationState.PENDING, result.state)
        assertNull(result.requestedWakeAt)
    }

    @Test fun observationForAnotherDeviceDoesNotMakeThisWakePending() {
        val night = saved()
        val result = explain(night, RuntimeState(alarmInbox = AlarmObservation("other", night.recordId,
            "ble:11:22:33:44:55:66", start.plusHours(1).toString())))
        assertEquals(WakeExplanationState.SAVED, result.state)
    }

    @Test fun runningNightUsesItsOriginalWindowAfterMidnight() {
        val next = alarm.copy(time = ZonedDateTime.parse("2026-10-23T18:00:00Z"))
        val result = AlarmExplanation.resolve(settings, profile, ZonedDateTime.parse("2026-10-23T04:30:00Z"), next,
            saved(), status = NightStatus(Confidence.CONFIRMED, null))
        assertEquals(AlarmReason.OUTSIDE_WINDOW, result.reason)
        assertEquals(start, result.nightStart)
        assertEquals(wake, result.wakeAt)
    }

    @Test fun liveProfileEditsDoNotChangeTheSavedWakePolicy() {
        val savedProfile = profile.copy(wakeSource = profile.wakeSource.copy(useAlarm = false))
        val night = saved().copy(profile = savedProfile, wakeOrigin = WakeOrigin.PROFILE_DEFAULT, wakeFromAlarm = false)
        val result = explain(night, RuntimeState())
        assertEquals(AlarmReason.PROFILE_ALARMS_OFF, result.reason)
        assertEquals(WakeExplanationState.SAVED, result.state)
        assertEquals(WakeOrigin.PROFILE_DEFAULT, result.wakeOrigin)
        assertEquals(wake, result.wakeAt)
    }

    @Test fun unknownManualAdjustmentDoesNotClaimAConfirmedManualProgramme() {
        val night = saved().copy(trackingManual = true, confidence = Confidence.UNKNOWN)
        val result = explain(night, RuntimeState())
        assertEquals(WakeExplanationState.UNKNOWN, result.state)
        assertEquals("Saved wake 07:00 — BedJet wake unknown · Next alarm 06:30 from Samsung Clock",
            result.description(RuntimeEnvironment.getApplication()))
    }

    @Test fun pendingCommandOnAManualNightKeepsItsPendingBoundary() {
        val previous = saved().copy(trackingManual = true)
        val night = previous.copy(confidence = Confidence.PENDING,
            pending = PendingChange("manual", ChangeKind.MANUAL, previous.wakeAt, start.toString(),
                start.plusMinutes(10).toString(), previous.identity))
        val result = explain(night, RuntimeState())
        assertEquals(WakeExplanationState.PENDING, result.state)
        assertEquals("Saved wake 07:00 — change pending · Next alarm 06:30 from Samsung Clock",
            result.description(RuntimeEnvironment.getApplication()))
    }

    @Test fun runtimeUnknownStatusDoesNotClaimAConfirmedManualProgramme() {
        val result = explain(saved().copy(trackingManual = true), RuntimeState(), NightStatus(Confidence.UNKNOWN, null))
        assertEquals(WakeExplanationState.UNKNOWN, result.state)
        assertEquals("Saved wake 07:00 — BedJet wake unknown · Next alarm 06:30 from Samsung Clock",
            result.description(RuntimeEnvironment.getApplication()))
    }

    @Test fun runtimePendingStatusDoesNotClaimAConfirmedManualProgramme() {
        val result = explain(saved().copy(trackingManual = true), RuntimeState(), NightStatus(Confidence.PENDING, null))
        assertEquals(WakeExplanationState.PENDING, result.state)
        assertEquals("Saved wake 07:00 — change pending · Next alarm 06:30 from Samsung Clock",
            result.description(RuntimeEnvironment.getApplication()))
    }

    @Test fun matchingRetimeOnAManualNightKeepsItsPendingBoundary() {
        val night = saved().copy(trackingManual = true)
        val requested = "2026-10-23T06:15:00Z"
        val result = explain(night, RuntimeState(retime = PendingRetime("request", night.identity, "demo",
            requested, alarm.creator, "2026-10-23T06:00:00Z")))
        assertEquals(WakeExplanationState.PENDING, result.state)
        assertEquals(ZonedDateTime.parse(requested), result.requestedWakeAt)
        assertFalse(result.matchesSavedWake)
    }

    @Test fun matchingUnreadObservationOnAManualNightKeepsItsPendingBoundary() {
        val night = saved().copy(trackingManual = true)
        val result = explain(night, RuntimeState(alarmInbox = AlarmObservation("observation", night.recordId,
            "demo", start.plusHours(1).toString())))
        assertEquals(WakeExplanationState.PENDING, result.state)
        assertNull(result.requestedWakeAt)
    }

    @Test fun uncertainManualAttemptKeepsItsSavedOverridePolicy() {
        val night = saved().copy(trackingManual = true, confidence = Confidence.UNKNOWN,
            wakeOrigin = WakeOrigin.OVERRIDE, wakeFromAlarm = false)
        val result = explain(night, RuntimeState())
        assertEquals(AlarmReason.OVERRIDE, result.reason)
        assertEquals(WakeExplanationState.UNKNOWN, result.state)
        assertEquals("Saved wake 07:00 — BedJet wake unknown · Alarm 06:30 ignored: tonight-only wake set — tap to change",
            result.description(RuntimeEnvironment.getApplication()))
    }

    @Test fun savedOverrideWithoutAnAlarmDoesNotSuggestAProfileFallback() {
        val night = saved().copy(wakeOrigin = WakeOrigin.OVERRIDE, wakeFromAlarm = false)
        val result = explain(night, RuntimeState(), nextAlarm = null)
        assertEquals(AlarmReason.OVERRIDE, result.reason)
        assertEquals(WakeExplanationState.SAVED, result.state)
        assertEquals(AlarmSettingsTarget.TONIGHT_WAKE, result.target)
        assertEquals("Saved tonight-only wake 07:00", result.description(RuntimeEnvironment.getApplication()))
    }

    @Test fun pendingOverrideWithoutAnAlarmStillExplainsThePendingChange() {
        val previous = saved().copy(wakeOrigin = WakeOrigin.OVERRIDE, wakeFromAlarm = false)
        val requested = "2026-10-23T06:45:00Z"
        val requestedAt = start.plusHours(2)
        val night = previous.copy(confidence = Confidence.PENDING,
            pending = PendingChange("override", ChangeKind.RETIME, requested, requestedAt.toString(),
                requestedAt.plusMinutes(10).toString(), previous.identity))
        val result = explain(night, RuntimeState(), nextAlarm = null)
        assertEquals(AlarmReason.OVERRIDE, result.reason)
        assertEquals(WakeExplanationState.PENDING, result.state)
        assertEquals(ZonedDateTime.parse(requested), result.requestedWakeAt)
        assertEquals(AlarmSettingsTarget.ALARM_SOURCES, result.target)
        assertEquals("Saved wake 07:00 — change pending", result.description(RuntimeEnvironment.getApplication()))
    }

    @Test fun unknownManualOverrideWithoutAnAlarmStillExplainsTheUncertainty() {
        val night = saved().copy(wakeOrigin = WakeOrigin.OVERRIDE, wakeFromAlarm = false,
            trackingManual = true, confidence = Confidence.UNKNOWN)
        val result = explain(night, RuntimeState(), nextAlarm = null)
        assertEquals(AlarmReason.OVERRIDE, result.reason)
        assertEquals(WakeExplanationState.UNKNOWN, result.state)
        assertEquals(AlarmSettingsTarget.ALARM_SOURCES, result.target)
        assertEquals("Saved wake 07:00 — BedJet wake unknown", result.description(RuntimeEnvironment.getApplication()))
    }
}
