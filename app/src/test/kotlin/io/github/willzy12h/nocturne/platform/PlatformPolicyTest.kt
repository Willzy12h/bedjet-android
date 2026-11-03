package io.github.willzy12h.nocturne.platform

import org.junit.Assert.*
import org.junit.Test
import java.time.ZonedDateTime

class PlatformPolicyTest {
    @Test fun everySuppressionAndThreeHourMinimum() {
        val good = ChargerEligibility()
        assertTrue(PlatformPolicy.eligible(good))
        listOf(good.copy(enabled=false), good.copy(charging=false), good.copy(selected=false),
            good.copy(validPlan=false), good.copy(minutes=179), good.copy(inWindow=false),
            good.copy(active=true), good.copy(manual=true), good.copy(uncertain=true),
            good.copy(takeover=true), good.copy(prerequisites=false), good.copy(alreadyAttempted=true))
            .forEach { assertFalse("Must suppress $it", PlatformPolicy.eligible(it)) }
    }
    @Test fun midnightWindowKeepsPreviousBedtimeDateAndHardExpiry() {
        val now = ZonedDateTime.parse("2026-10-23T00:15:00+01:00[Europe/London]")
        val window = PlatformPolicy.window(23*60+30, now)!!
        assertEquals("bedjet:2026-10-22", window.key("bedjet"))
        assertTrue(window.contains(window.expires.minusNanos(1)))
        assertFalse(window.contains(window.expires))
        assertNull(PlatformPolicy.window(23*60+30, window.expires))
    }
    @Test fun repeatedHourUsesOneBedtimeAndOneNightKey() {
        val first = ZonedDateTime.parse("2026-10-25T01:15:00+01:00[Europe/London]")
        val second = first.withLaterOffsetAtOverlap()
        val a = PlatformPolicy.window(90, first)!!
        val b = PlatformPolicy.window(90, second)!!
        assertEquals(a.bedtime.toInstant(), b.bedtime.toInstant())
        assertEquals(a.key("bedjet"), b.key("bedjet"))
        assertFalse(b.contains(a.expires))
    }
}
