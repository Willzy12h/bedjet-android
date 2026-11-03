package io.github.willzy12h.nocturne.core.plan

import io.github.willzy12h.nocturne.core.protocol.Mode

/** Starting points for each time of year. Temperatures are half °C: 44 = 22 °C, 64 = 32 °C, 80 = 40 °C. */
object Presets {
    val summerCool = Profile(
        id = "preset-summer", name = "Summer Cool", season = Season.SUMMER,
        start = listOf(Phase(Mode.COOL, 44, 70, 30)),
        middle = Middle(Mode.COOL, fanStart = 50, fanEnd = 25, tempStart = 44, tempEnd = 44, rampSteps = 3),
        wake = listOf(Phase(Mode.HEAT, 60, 30, 20)),
        afterWake = listOf(Phase(Mode.COOL, 44, 40, 20)),
    )

    val winterWarm = Profile(
        id = "preset-winter", name = "Winter Warm", season = Season.WINTER,
        start = listOf(Phase(Mode.TURBO, 86, 100, 10), Phase(Mode.HEAT, 70, 50, 30)),
        middle = Middle(Mode.EXTENDED_HEAT, fanStart = 30, fanEnd = 20, tempStart = 58, tempEnd = 54, rampSteps = 3),
        wake = listOf(Phase(Mode.HEAT, 68, 40, 30)),
        afterWake = listOf(Phase(Mode.HEAT, 64, 30, 30)),
    )

    val springAutumn = Profile(
        id = "preset-spring-autumn", name = "Spring/Autumn", season = Season.SPRING_AUTUMN,
        start = listOf(Phase(Mode.HEAT, 64, 40, 20)),
        middle = Middle(Mode.COOL, fanStart = 35, fanEnd = 20, tempStart = 44, tempEnd = 44, rampSteps = 3),
        wake = listOf(Phase(Mode.HEAT, 64, 35, 25)),
    )

    val hotSleeper = Profile(
        id = "preset-hot-sleeper", name = "Hot Sleeper", season = Season.SUMMER,
        start = listOf(Phase(Mode.COOL, 44, 100, 45)),
        middle = Middle(Mode.COOL, fanStart = 50, fanEnd = 45, tempStart = 44, tempEnd = 44, rampSteps = 3,
            quietFan = QuietFan(settle = 50, settleMinutes = 30, asleep = 25, rise = 45, riseMinutes = 40)),
        wake = listOf(Phase(Mode.COOL, 44, 30, 20)),
        afterWake = listOf(Phase(Mode.COOL, 44, 40, 30)),
    )

    val warmStartCoolNight = Profile(
        id = "preset-warm-start", name = "Warm Start, Cool Night",
        start = listOf(Phase(Mode.TURBO, 86, 100, 10), Phase(Mode.HEAT, 68, 40, 20)),
        middle = Middle(Mode.COOL, fanStart = 40, fanEnd = 20, tempStart = 44, tempEnd = 44, rampSteps = 3),
        wake = listOf(Phase(Mode.HEAT, 66, 35, 30)),
    )

    val all: List<Profile> = listOf(summerCool, winterWarm, springAutumn, hotSleeper, warmStartCoolNight)
}
