package io.github.willzy12h.nocturne.ui.features

import io.github.willzy12h.nocturne.platform.SleepStage
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class SleepLaneMinutesTest {
    private val t0 = Instant.parse("2026-10-30T22:00:00Z")
    private fun stage(from: Long, to: Long, code: Int) = SleepStage(t0.plusSeconds(from * 60), t0.plusSeconds(to * 60), code)

    @Test fun totalsFollowLaneOrderAndFoldOutOfBedIntoAwake() {
        val totals = sleepLaneMinutes(listOf(stage(0, 20, 4), stage(20, 50, 5), stage(50, 60, 1), stage(60, 70, 3), stage(70, 95, 6), stage(95, 110, 4)))
        assertEquals(listOf(SleepLane.AWAKE to 20, SleepLane.REM to 25, SleepLane.LIGHT to 35, SleepLane.DEEP to 30), totals)
    }

    @Test fun unknownStagesAndEmptyLanesAreLeftOut() {
        assertEquals(emptyList<Pair<SleepLane, Int>>(), sleepLaneMinutes(listOf(stage(0, 30, 0), stage(30, 30, 4))))
    }
}
