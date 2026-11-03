package io.github.willzy12h.nocturne.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.test.*
import io.github.willzy12h.nocturne.core.plan.Block
import io.github.willzy12h.nocturne.core.plan.Plan
import io.github.willzy12h.nocturne.core.plan.PlannedStep
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.protocol.ProgramStep
import io.github.willzy12h.nocturne.core.store.Settings
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZonedDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NightCurveAlternativeTest : ComposeActivityFixture() {
    @Test fun timelineEmphasisesCurrentStepAndWakeInDarkPalette() {
        val start = ZonedDateTime.parse("2026-10-22T22:30Z")
        val plan = Plan(start, start.plusHours(1), listOf(
            PlannedStep(Block.MIDDLE, ProgramStep(Mode.COOL, 7, 44, 1, 0), start, 60)))
        setContent { NocturneTheme(io.github.willzy12h.nocturne.core.store.ThemeMode.DARK) {
            NightTimeline(plan, io.github.willzy12h.nocturne.core.night.WakeOrigin.PROFILE_DEFAULT, false, now = start.plusMinutes(10))
        } }
        fun layout(text: String): TextLayoutResult {
            val results = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(text, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
            return results.single()
        }
        assertEquals(FontWeight.SemiBold, layout("Cool").layoutInput.style.fontWeight)
        val wake = layout("Wake time").layoutInput.style
        assertEquals(FontWeight.SemiBold, wake.fontWeight)
        assertEquals(io.github.willzy12h.nocturne.core.store.Palette.MIDNIGHT_INK.tokens.accent, wake.color)
    }
    @Test fun temporaryPrefixIsNamedInTheCurveAlternative() {
        val start = ZonedDateTime.parse("2026-10-22T22:30Z")
        val plan = Plan(start, start.plusMinutes(20), listOf(
            PlannedStep(Block.TEMPORARY, ProgramStep(Mode.HEAT, 5, 64, 0, 5), start, 5),
            PlannedStep(Block.MIDDLE, ProgramStep(Mode.COOL, 7, 44, 0, 15), start.plusMinutes(5), 15)))
        setContent { NocturneTheme { NightCurve(plan) } }
        compose.onNode(hasContentDescription("22:30 Temporary Heat, 32°C", substring = true)).assertExists()
    }

    @Test fun simpleViewNamesTemporaryStepsWithoutRenamingTheUnderlyingSchedule() {
        val start = ZonedDateTime.parse("2026-10-22T22:30Z")
        val plan = Plan(start, start.plusMinutes(20), listOf(
            PlannedStep(Block.TEMPORARY, ProgramStep(Mode.HEAT, 5, 64, 0, 5), start, 5),
            PlannedStep(Block.MIDDLE, ProgramStep(Mode.COOL, 7, 44, 0, 15), start.plusMinutes(5), 15)))
        setContent { NocturneTheme { NightTimeline(plan, io.github.willzy12h.nocturne.core.night.WakeOrigin.PROFILE_DEFAULT, false) } }
        compose.onNodeWithText("Temporary · Heat").assertExists()
        compose.onNodeWithText("Cool").assertExists()
    }
    @Test fun everyStepAnnouncesItsTimeModeAndTemperatureWithUnits() {
        val start = ZonedDateTime.parse("2026-10-22T22:30Z")
        val steps = listOf(
            PlannedStep(Block.MIDDLE, ProgramStep(Mode.COOL, 7, 44, 1, 0), start, 60),
            PlannedStep(Block.WAKE, ProgramStep(Mode.HEAT, 5, 61, 0, 30), start.plusHours(1), 30))
        setContent { NocturneTheme { NightCurve(Plan(start, start.plusMinutes(90), steps)) } }
        compose.onNode(hasContentDescription("22:30 Middle Cool, 22°C", substring = true)).assertExists()
        compose.onNode(hasContentDescription("23:30 Wake-up Heat, 30.5°C", substring = true)).assertExists()
    }
    @Test fun everyStepRespectsTheFahrenheitPreferenceInItsTextAlternative() {
        val start = ZonedDateTime.parse("2026-10-22T22:30Z")
        val steps = listOf(
            PlannedStep(Block.MIDDLE, ProgramStep(Mode.COOL, 7, 44, 1, 0), start, 60),
            PlannedStep(Block.WAKE, ProgramStep(Mode.HEAT, 5, 61, 0, 30), start.plusHours(1), 30))
        setContent {
            NocturneTheme(Settings(fahrenheit = true), start) {
                NightCurve(Plan(start, start.plusMinutes(90), steps))
            }
        }
        compose.onNode(hasContentDescription("22:30 Middle Cool, 72°F", substring = true)).assertExists()
        compose.onNode(hasContentDescription("23:30 Wake-up Heat, 87°F", substring = true)).assertExists()
    }

    @Test fun modesOnlyAnnounceTheirAvailableControlsInTheCelsiusAlternative() {
        assertNonTemperatureModes(fahrenheit = false)
    }

    @Test fun modesOnlyAnnounceTheirAvailableControlsInTheFahrenheitAlternative() {
        assertNonTemperatureModes(fahrenheit = true)
    }

    private fun assertNonTemperatureModes(fahrenheit: Boolean) {
        val start = ZonedDateTime.parse("2026-10-22T22:30Z")
        val steps = listOf(
            PlannedStep(Block.START, ProgramStep(Mode.STANDBY, 0, 80, 0, 1), start, 1),
            PlannedStep(Block.MIDDLE, ProgramStep(Mode.TURBO, 19, 86, 0, 10), start.plusMinutes(1), 10),
            PlannedStep(Block.WAKE, ProgramStep(Mode.WAIT, 0, 80, 0, 2), start.plusMinutes(11), 2),
        )
        setContent {
            NocturneTheme(Settings(fahrenheit = fahrenheit), start) {
                NightCurve(Plan(start, start.plusMinutes(13), steps))
            }
        }
        val description = compose.onNode(hasContentDescription("Night plan from 22:30 to 22:43", substring = true))
            .fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single()
        assertEquals(
            "Night plan from 22:30 to 22:43; wake 22:43. " +
                "22:30 Start Off, 1 minute; " +
                "22:31 Middle Turbo, fan 100%, 10 minutes; " +
                "22:41 Wake-up Pause, 2 minutes",
            description,
        )
    }

}
