package io.github.willzy12h.nocturne.core.plan

import io.github.willzy12h.nocturne.core.night.WakeOrigin
import io.github.willzy12h.nocturne.core.store.Settings
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.ZonedDateTime

class AlarmWindowUxTest {
    private val start = ZonedDateTime.parse("2026-10-26T22:30:00Z")

    @Test fun `Samsung Google and unknown alarms are accepted through eighteen hours by default`() {
        for (source in listOf("com.sec.android.app.clockpackage", "com.google.android.deskclock", null)) {
            val preview = NightPreview.forStartNow(Presets.springAutumn, start, start.plusHours(18), alarmOrigin = source)
            assertEquals(WakeOrigin.ALARM, preview.wake.origin, "Source $source")
            assertEquals(start.plusHours(18), preview.wake.time)
            assertNotNull(preview.plan)
        }
    }

    @Test fun `normal five minute alarm uses the profile wake and never relaxes the minimum`() {
        val preview = NightPreview.forStartNow(Presets.springAutumn, start, start.plusMinutes(5))
        assertEquals(WakeOrigin.PROFILE_DEFAULT, preview.wake.origin)
        assertTrue(preview.wake.time >= start.plusHours(1))
        assertFalse(AlarmPolicy().accept(start.plusMinutes(59), null, start))
        assertTrue(AlarmPolicy().accept(start.plusHours(1), null, start))
    }

    @Test fun `later than eighteen hours and explicitly denied unknown sources use fallback`() {
        assertFalse(AlarmPolicy().accept(start.plusHours(18).plusNanos(1), null, start))
        assertFalse(AlarmPolicy(Settings(acceptUnknownAlarmSource = false)).accept(start.plusHours(8), null, start))
        assertEquals(WakeOrigin.PROFILE_DEFAULT, NightPreview.forStartNow(Presets.springAutumn,
            start, start.plusHours(18).plusMinutes(1)).wake.origin)
    }

    @Test fun `compiler supports the same eighteen hour maximum as selection`() {
        assertEquals(start.plusHours(18), PlanCompiler.compile(Presets.springAutumn, start, start.plusHours(18)).wake)
        assertThrows(PlanException::class.java) {
            PlanCompiler.compile(Presets.springAutumn, start, start.plusHours(18).plusMinutes(1))
        }
    }
}
