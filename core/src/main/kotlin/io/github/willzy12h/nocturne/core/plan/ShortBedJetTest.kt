package io.github.willzy12h.nocturne.core.plan

import io.github.willzy12h.nocturne.core.protocol.Mode
import java.time.Duration
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/** An explicit equipment test, separate from normal night/alarm selection. No saved profile is changed. */
object ShortBedJetTest {
    const val MINUTES = 5
    fun profile(name: String) = Profile("equipment-test", name,
        start = listOf(Phase(Mode.COOL, 44, 30, 1)),
        middle = Middle(Mode.COOL, 30, 30, 44, 44),
        wake = listOf(Phase(Mode.HEAT, 60, 30, 2)),
        wakeSource = WakeSource(useAlarm = false))

    fun plan(profile: Profile, at: ZonedDateTime): Plan {
        val minute = at.truncatedTo(ChronoUnit.MINUTES)
        val compiled = PlanCompiler.compile(profile, minute, minute.plusMinutes(MINUTES.toLong()))
        // All test steps use durations, so retain the precise start instead of claiming an earlier finish.
        val offset = Duration.between(minute, at)
        return compiled.copy(start = at, wake = at.plusMinutes(MINUTES.toLong()),
            steps = compiled.steps.map { it.copy(startsAt = it.startsAt.plus(offset)) })
    }
}
