package io.github.willzy12h.nocturne.core.plan

import io.github.willzy12h.nocturne.core.night.WakeOrigin
import io.github.willzy12h.nocturne.core.store.Settings
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.ZonedDateTime

class WakePolicyHardeningTest {
    private val now = ZonedDateTime.parse("2026-10-21T22:30:00Z")
    @Test fun `alarm policy includes one and sixteen hours but nothing beyond`() {
        val p = AlarmPolicy(Settings())
        assertFalse(p.accept(now.plusMinutes(59), "com.google.android.deskclock", now))
        assertTrue(p.accept(now.plusHours(1), "com.google.android.deskclock", now))
        assertTrue(p.accept(now.plusHours(16), "com.sec.android.app.clockpackage", now))
        assertFalse(p.accept(now.plusHours(16).plusMinutes(1), "com.google.android.deskclock", now))
        assertFalse(p.accept(now.plusHours(8), "calendar", now))
    }
    @Test fun `unknown attribution obeys preference`() {
        assertTrue(AlarmPolicy(Settings()).accept(now.plusHours(8), null, now))
        assertFalse(AlarmPolicy(Settings(acceptUnknownAlarmSource = false)).accept(now.plusHours(8), null, now))
    }
    @Test fun `override wins survives restart and expires at wake`() {
        val settings = Settings().setWakeOverride(now.plusHours(7), now)
        val restored = Json.decodeFromString<Settings>(Json.encodeToString(settings))
        val preview = NightPreview.forStartNow(Presets.springAutumn, now, now.plusHours(8), restored.resolveWakeOverride(now))
        assertEquals(now.plusHours(7), preview.wake.time)
        assertEquals(WakeOrigin.OVERRIDE, preview.wake.origin)
        assertNull(restored.resolveWakeOverride(now.plusHours(7)))
        assertNull(restored.clearWakeOverride().tonightOverride)
    }
    @Test fun `six thirty four default wake refuses a twenty four hour night`() {
        val preview = NightPreview.forStartNow(Presets.springAutumn, now.withHour(6).withMinute(34), null, null)
        assertNull(preview.plan)
        assertTrue(preview.refusalReason!!.contains("tonight-only"))
    }
    @Test fun `temporary choice expires to schedule and follow schedule clears it`() {
        val scheduled = Presets.springAutumn.copy(days = setOf(4))
        val profiles = listOf(Presets.winterWarm, scheduled)
        val settings = Settings().chooseTonight(Presets.winterWarm.id, now)
        assertEquals(Presets.winterWarm, settings.resolveProfile(profiles, now))
        assertEquals(scheduled, settings.resolveProfile(profiles, now.plusDays(1).withHour(12)))
        assertEquals(scheduled, settings.followSchedule().resolveProfile(profiles, now.plusHours(3)))
    }
    @Test fun `legacy selection migrates to an expiring choice when resolved`() {
        val legacy = Json.decodeFromString<Settings>("""{"selectedProfileId":"preset-winter"}""")
        val migrated = legacy.migrateTonightChoice(now)
        assertEquals("preset-winter", migrated.tonightChoice!!.profileId)
        assertNull(migrated.selectedProfileId)
        assertNull(migrated.resolveTonightChoice(now.plusDays(1).withHour(12)))
    }
    @Test fun `validation accounts for turbo chaining at sixteen hour extreme`() {
        val p = Presets.springAutumn.copy(start = emptyList(), wake = emptyList(), middle = Middle(io.github.willzy12h.nocturne.core.protocol.Mode.TURBO,100,100,86,86), turboChain = TurboChain.REPEAT_TURBO)
        assertTrue(ProfileValidation.validate(p).any { it.contains("32") })
    }
}
