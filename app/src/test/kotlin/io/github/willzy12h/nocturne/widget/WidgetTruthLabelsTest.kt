package io.github.willzy12h.nocturne.widget

import io.github.willzy12h.nocturne.core.night.*
import io.github.willzy12h.nocturne.core.plan.*
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.graph
import io.github.willzy12h.nocturne.night.SlotWrite
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WidgetTruthLabelsTest : io.github.willzy12h.nocturne.platform.PlatformGraphFixture() {
    private val app = RuntimeEnvironment.getApplication()
    private fun night(confidence: Confidence): ActiveNight {
        app.graph.clock = Clock.fixed(Instant.parse("2026-10-26T22:30:00Z"), ZoneOffset.UTC)
        val start = app.graph.now()
        val profile = app.graph.repository.profiles.value.first().copy(start = listOf(Phase(Mode.HEAT, 70, 40, 1)))
        val plan = PlanCompiler.compile(profile, start, start.plusHours(8))
        return ActiveNight("truth", profile.id, start.toString(), plan.wake.toString(), false, 2,
            plan.wakeUpStart.toString(), plan.end.toString(), deviceId = "demo", profile = profile,
            applied = AppliedProgram.from(plan), confidence = confidence)
    }
    @Test fun anUnknownWakeIsUnconfirmedRatherThanStillPending() {
        assertEquals("Next wake unconfirmed", widgetWake(app, night(Confidence.UNKNOWN)))
    }
    @Test fun aFailedWakeIsFailedRatherThanStillPending() {
        assertEquals("Next wake failed", widgetWake(app, night(Confidence.FAILED)))
    }
    @Test fun aPendingWakeRetainsItsPendingLabel() {
        assertEquals("Next wake pending", widgetWake(app, night(Confidence.PENDING)))
    }
    @Test fun aPlannedPhaseCarriesItsAbsoluteTimeSpanForAFrozenHostView() {
        val text = widgetPhase(app, night(Confidence.CONFIRMED))
        assertTrue("A cached planned phase must include its date and time range: $text", text.contains("26 Oct 22:30–22:31"))
    }

    @Test fun anUnresolvedSlotWriteForThisNightMakesItsWakeAndPhaseUnconfirmed() = runBlocking {
        val current = night(Confidence.CONFIRMED)
        app.graph.repository.updateRuntime { it.copy(slotWrite = SlotWrite("demo", 2, null, Confidence.UNKNOWN,
            "Restore", displaced = current.identity)) }
        assertEquals("Next wake unconfirmed", widgetWake(app, current))
        assertEquals("Phase unconfirmed", widgetPhase(app, current))
    }
    @Test fun aPendingSlotWriteForThisNightDoesNotKeepTheOldConfirmedWake() = runBlocking {
        val current = night(Confidence.CONFIRMED)
        app.graph.repository.updateRuntime { it.copy(slotWrite = SlotWrite("demo", 2, null, Confidence.PENDING,
            "Restore", displaced = current.identity)) }
        assertEquals("Next wake pending", widgetWake(app, current))
        assertEquals("Phase unconfirmed", widgetPhase(app, current))
    }
    @Test fun anUnresolvedWriteForAnotherNightDoesNotColourThisNight() = runBlocking {
        val current = night(Confidence.CONFIRMED)
        app.graph.repository.updateRuntime { it.copy(slotWrite = SlotWrite("demo", 1, null, Confidence.UNKNOWN,
            "Restore", displaced = NightIdentity("some-other-night", 1))) }
        assertTrue(widgetWake(app, current).startsWith("Next wake Tue 06:30"))
        assertTrue(widgetPhase(app, current).startsWith("Planned phase: Start"))
    }
}
