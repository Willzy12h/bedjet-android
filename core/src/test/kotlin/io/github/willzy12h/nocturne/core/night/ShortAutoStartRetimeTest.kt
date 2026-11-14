package io.github.willzy12h.nocturne.core.night

import io.github.willzy12h.nocturne.core.link.BedJetController
import io.github.willzy12h.nocturne.core.link.DemoBedJet
import io.github.willzy12h.nocturne.core.plan.Presets
import io.github.willzy12h.nocturne.core.plan.TonightOverride
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.ZonedDateTime

/** An auto-start set 2 hours before the alarm keeps that short window when the alarm moves during the night. */
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

    @Test fun `a moved alarm inside the agreed short window is followed`() = runTest {
        val night = night(shortest = 120)
        val ready = runner.prepareRetime(night, night.identity, alarm.plusMinutes(15), alarmOrigin = clock) as Preparation.Ready
        assertEquals(alarm.plusMinutes(15).toString(), ready.change.intended.wakeAt)
        assertEquals(WakeOrigin.ALARM, ready.change.intended.wakeOrigin)
        assertEquals(120, ready.change.intended.shortAlarmNightMinutes, "Later changes keep the same window")
    }

    @Test fun `an alarm before the agreed window is still ignored`() = runTest {
        val night = night(shortest = 120)
        val result = runner.prepareRetime(night, night.identity, alarm.minusMinutes(30), alarmOrigin = clock)
        assertFalse(result is Preparation.Ready && result.change.intended.wakeOrigin == WakeOrigin.ALARM)
    }

    @Test fun `any other night keeps the usual three hours`() = runTest {
        val night = night(shortest = null)
        val result = runner.prepareRetime(night, night.identity, alarm.plusMinutes(15), alarmOrigin = clock)
        assertFalse(result is Preparation.Ready && result.change.intended.wakeOrigin == WakeOrigin.ALARM)
    }
}
