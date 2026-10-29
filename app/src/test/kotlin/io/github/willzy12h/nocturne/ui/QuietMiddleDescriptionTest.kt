package io.github.willzy12h.nocturne.ui

import androidx.compose.ui.test.*
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.plan.*
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.protocol.ProgramStep
import io.github.willzy12h.nocturne.core.night.WakeOrigin
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZonedDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class QuietMiddleDescriptionTest {
    private val ui = U06Fixture(U06Look.MIDNIGHT, widthDp = 411, fontScale = 1f)
    @get:Rule val rules = ui.rules

    @Test fun profileSummaryNamesAllThreeQuietFanSpeeds() {
        val original = ui.graph.repository.profiles.value.first()
        val quiet = QuietFan(50, 30, 25, 45, 40)
        runBlocking { ui.graph.repository.saveProfile(original.copy(middle = original.middle.copy(mode = Mode.COOL, fanStart = 50, fanEnd = 45, quietFan = quiet))) }
        ui.compose.onNode(hasText(ui.s(R.string.tab_profiles)) and hasClickAction()).performClick()
        ui.compose.onNodeWithTag("profile-timeline-${original.id}", useUnmergedTree = true)
            .assertContentDescriptionContains("settle 50%", substring = true)
            .assertContentDescriptionContains("asleep 25%", substring = true)
            .assertContentDescriptionContains("rise 45%", substring = true)
    }

}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QuietMiddleTimelineDescriptionTest : ComposeActivityFixture() {
    @Test fun timelineDoesNotDiscardTheMiddleFanSpeedActuallyInThePlan() {
        val start = ZonedDateTime.parse("2026-10-21T22:30Z")
        val steps = listOf(50, 25, 45).mapIndexed { i, fan ->
            PlannedStep(Block.MIDDLE, ProgramStep(Mode.COOL, fan / 5 - 1, 44, 0, 30), start.plusMinutes(i * 30L), 30)
        }
        val plan = Plan(start, start.plusMinutes(90), steps)
        setContent { NocturneTheme { NightTimeline(plan, WakeOrigin.PROFILE_DEFAULT, false) } }
        compose.onNode(hasText("50%", substring = true) and hasText("25%", substring = true) and hasText("45%", substring = true)).assertExists()
    }
    @Test fun capturedQuietShapeLabelsOnlyStagesPresentInAppliedSteps() {
        val start = ZonedDateTime.parse("2026-10-21T22:30Z")
        val middle = Middle(Mode.COOL, 50, 45, 44, 44, quietFan = QuietFan(50, 30, 25, 45, 40))
        val steps = listOf(25, 45).mapIndexed { i, fan ->
            PlannedStep(Block.MIDDLE, ProgramStep(Mode.COOL, fan / 5 - 1, 44, 0, 30), start.plusMinutes(i * 30L), 30)
        }
        val plan = Plan(start, start.plusMinutes(60), steps)
        setContent { NocturneTheme { NightTimeline(plan, WakeOrigin.PROFILE_DEFAULT, false, middle = middle) } }
        compose.onNode(hasText("asleep 25%", substring = true) and hasText("rise 45%", substring = true)).assertExists()
        compose.onAllNodes(hasText("settle 50%", substring = true)).assertCountEquals(0)
    }

    @Test fun noCapturedShapeDoesNotInventQuietStageLabels() {
        val start = ZonedDateTime.parse("2026-10-21T22:30Z")
        val steps = listOf(50, 25, 45).mapIndexed { i, fan ->
            PlannedStep(Block.MIDDLE, ProgramStep(Mode.COOL, fan / 5 - 1, 44, 0, 30), start.plusMinutes(i * 30L), 30)
        }
        val plan = Plan(start, start.plusMinutes(90), steps)
        setContent { NocturneTheme { NightTimeline(plan, WakeOrigin.PROFILE_DEFAULT, false) } }
        compose.onAllNodes(hasText("settle", substring = true)).assertCountEquals(0)
        compose.onAllNodes(hasText("asleep", substring = true)).assertCountEquals(0)
    }

}
