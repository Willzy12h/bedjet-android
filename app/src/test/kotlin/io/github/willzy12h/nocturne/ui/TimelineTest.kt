package io.github.willzy12h.nocturne.ui

import io.github.willzy12h.nocturne.core.plan.Block
import io.github.willzy12h.nocturne.core.plan.Plan
import io.github.willzy12h.nocturne.core.plan.PlannedStep
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.protocol.ProgramStep
import io.github.willzy12h.nocturne.core.store.BackupCodec
import io.github.willzy12h.nocturne.core.store.Settings
import io.github.willzy12h.nocturne.core.store.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.ZonedDateTime

/** Simple view's merging of plan steps, and the new appearance settings loading from older saves. */
class TimelineTest {
    private val start = ZonedDateTime.parse("2026-10-21T23:10:00+01:00")

    private fun plan(vararg steps: Triple<Block, Mode, Pair<Int, Int>>, minutes: Int = 60): Plan {
        var at = start
        val planned = steps.map { (block, mode, fanAndTemp) ->
            val (fan, temp) = fanAndTemp
            PlannedStep(block, ProgramStep(mode, fan / 5 - 1, temp, minutes / 60, minutes % 60), at, minutes).also { at = at.plusMinutes(minutes.toLong()) }
        }
        return Plan(start, at, planned)
    }

    @Test
    fun aRampInOneBlockBecomesOneLine() {
        val p = plan(
            Triple(Block.MIDDLE, Mode.COOL, 75 to 44),
            Triple(Block.MIDDLE, Mode.COOL, 60 to 44),
            Triple(Block.MIDDLE, Mode.COOL, 40 to 44),
            minutes = 140,
        )
        val entries = timelineEntries(p)
        assertEquals(1, entries.size)
        val line = entries.single()
        assertEquals(start, line.startsAt)
        assertEquals(420, line.minutes)
        assertEquals("Cool, easing off", line.title)
        assertEquals("7 h · fan 75% → 40%", line.detail(fahrenheit = false))
    }

    @Test
    fun aNewBlockOrModeStartsANewLine() {
        val p = plan(
            Triple(Block.START, Mode.HEAT, 40 to 64),
            Triple(Block.MIDDLE, Mode.HEAT, 40 to 64),
            Triple(Block.MIDDLE, Mode.COOL, 40 to 44),
            Triple(Block.WAKE, Mode.HEAT, 30 to 60),
            Triple(Block.WAKE, Mode.HEAT, 30 to 68),
        )
        val entries = timelineEntries(p)
        assertEquals(listOf(Block.START, Block.MIDDLE, Block.MIDDLE, Block.WAKE), entries.map { it.block })
        assertEquals("Heat", entries[0].title)
        assertEquals("1 h · 32°C · fan 40%", entries[0].detail(fahrenheit = false))
        assertEquals("Heat, warming up", entries[3].title)
        assertEquals("2 h · 30°C → 34°C · fan 30%", entries[3].detail(fahrenheit = false))
    }

    @Test
    fun settingsSavedBeforeAppearanceOptionsStillLoad() {
        val old = """{"workingSlot":1,"fahrenheit":true}"""
        val settings = BackupCodec.json.decodeFromString(Settings.serializer(), old)
        assertEquals(ThemeMode.SYSTEM, settings.themeMode)
        assertFalse(settings.simpleView)
        assertEquals(1, settings.workingSlot)
        val round = BackupCodec.json.decodeFromString(Settings.serializer(), BackupCodec.json.encodeToString(Settings.serializer(), settings.copy(themeMode = ThemeMode.LIGHT, simpleView = true)))
        assertEquals(ThemeMode.LIGHT, round.themeMode)
        assertEquals(true, round.simpleView)
    }
}
