package io.github.willzy12h.nocturne.ui

import androidx.compose.ui.test.*
import io.github.willzy12h.nocturne.core.plan.Block
import io.github.willzy12h.nocturne.core.plan.Plan
import io.github.willzy12h.nocturne.core.plan.PlannedStep
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.protocol.ProgramStep
import io.github.willzy12h.nocturne.core.store.Settings
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZonedDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NightCurveAlternativeTest : ComposeActivityFixture() {
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

}
