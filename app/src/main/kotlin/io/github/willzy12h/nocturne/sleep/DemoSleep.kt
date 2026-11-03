package io.github.willzy12h.nocturne.sleep

import io.github.willzy12h.nocturne.platform.SleepStage
import java.time.LocalDate
import java.time.ZoneId
import kotlin.random.Random

/**
 * Sample nights for Demo, so the Sleep page can be explored without Health Connect. The same date always
 * gives the same night. Nothing here is read from or written to Health Connect.
 */
object DemoSleep {
    fun nights(today: LocalDate, days: Int, zone: ZoneId): List<SleepNight> = (0 until days).map { back ->
        val date = today.minusDays(back.toLong())
        val random = Random(date.toEpochDay())
        val start = date.minusDays(1).atTime(22, 30).plusMinutes(random.nextLong(0, 50)).atZone(zone).toInstant()
        var at = start
        val stages = mutableListOf<SleepStage>()
        fun add(stage: Int, minutes: Int) { val end = at.plusSeconds(minutes * 60L); stages += SleepStage(at, end, stage); at = end }
        add(SleepInsights.AWAKE, random.nextInt(6, 22))
        // Four or five cycles: deep sleep early in the night, longer REM towards morning.
        val cycles = random.nextInt(4, 6)
        repeat(cycles) { cycle ->
            add(SleepInsights.LIGHT, random.nextInt(15, 30))
            val deep = (45 - cycle * 10).coerceAtLeast(5) + random.nextInt(0, 10)
            add(SleepInsights.DEEP, deep)
            add(SleepInsights.LIGHT, random.nextInt(10, 25))
            add(SleepInsights.REM, 10 + cycle * 7 + random.nextInt(0, 8))
            if (cycle < cycles - 1 && random.nextInt(3) == 0) add(SleepInsights.AWAKE, random.nextInt(2, 9))
        }
        add(SleepInsights.LIGHT, random.nextInt(10, 25))
        add(SleepInsights.AWAKE, random.nextInt(3, 10))
        SleepNight(start, at, stages)
    }
}
