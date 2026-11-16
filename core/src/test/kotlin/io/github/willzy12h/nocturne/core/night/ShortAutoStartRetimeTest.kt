package io.github.willzy12h.nocturne.core.night

import io.github.willzy12h.nocturne.core.link.BedJetController
import io.github.willzy12h.nocturne.core.link.DemoBedJet
import io.github.willzy12h.nocturne.core.plan.Presets
import io.github.willzy12h.nocturne.core.plan.TonightOverride
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.ZonedDateTime

/** An auto-start saved by 1.1.0-beta.3 or beta.4 with an agreed window (2 hours here) keeps following a moved alarm after
 * the upgrade, and that stored window never makes it stricter than the usual hour from the start. */
class ShortAutoStartRetimeTest {
    private val clock = "com.google.android.deskclock"
    private var now = ZonedDateTime.parse("2026-10-27T04:00:00Z")
    private val alarm = ZonedDateTime.parse("2026-10-27T06:00:00Z")
    private val link = DemoBedJet { now }
    private val controller = BedJetController(link) { now }
    private val runner = NightRunner(controller) { now }

    private suspend fun night(shortest: Int?): ActiveNight {
        link.connect()
        val started = NightFixture(runner, controller) { now }.start(Presets.springAutumn, null, 2, emptyList(), "demo",
            TonightOverride(alarm.toString(), now.toString())).night
        now = now.plusMinutes(30)
        return started.copy(wakeOrigin = WakeOrigin.AUTO_START, wakeFromAlarm = true, shortAlarmNightMinutes = shortest)
    }

    @Test fun `a moved alarm inside the agreed window is followed`() = runTest {
        val night = night(shortest = 120)
        val ready = runner.prepareRetime(night, night.identity, alarm.plusMinutes(15), alarmOrigin = clock) as Preparation.Ready
        assertEquals(alarm.plusMinutes(15).toString(), ready.change.intended.wakeAt)
        assertEquals(WakeOrigin.ALARM, ready.change.intended.wakeOrigin)
        assertEquals(120, ready.change.intended.shortAlarmNightMinutes, "Later changes keep the same window")
    }

    @Test fun `a stored window never refuses an alarm the usual hour accepts`() = runTest {
        val night = night(shortest = 120)
        val moved = now.minusMinutes(30).plusMinutes(75)
        val ready = runner.prepareRetime(night, night.identity, moved, alarmOrigin = clock) as Preparation.Ready
        assertEquals(moved.toString(), ready.change.intended.wakeAt)
        assertEquals(WakeOrigin.ALARM, ready.change.intended.wakeOrigin)
    }

    @Test fun `an alarm under an hour after the start is still ignored`() = runTest {
        val night = night(shortest = 120)
        val result = runner.prepareRetime(night, night.identity, now.plusMinutes(20), alarmOrigin = clock)
        assertFalse(result is Preparation.Ready && result.change.intended.wakeOrigin == WakeOrigin.ALARM)
    }

    @Test fun `any other night follows an alarm an hour after its start`() = runTest {
        val night = night(shortest = null)
        val moved = now.minusMinutes(30).plusMinutes(75)
        val ready = runner.prepareRetime(night, night.identity, moved, alarmOrigin = clock) as Preparation.Ready
        assertEquals(WakeOrigin.ALARM, ready.change.intended.wakeOrigin)
    }
}
