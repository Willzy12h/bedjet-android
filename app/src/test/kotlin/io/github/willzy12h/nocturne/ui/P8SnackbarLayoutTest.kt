package io.github.willzy12h.nocturne.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.night.CommandOutcome
import io.github.willzy12h.nocturne.night.OutcomeKind
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class P8SnackbarLayoutTest {
    private val ui = U06Fixture(U06Look.MIDNIGHT)
    @get:Rule val rules = ui.rules

    @Test fun longFeedbackKeepsTheUnderlyingControlReachableAndCanBeReadAndDismissed() {
        ui.tab(ui.s(R.string.tab_settings))
        ui.compose.openSettingsPage("night")
        val message = "Alarm 22:35 ignored: too soon — normal nights need at least 1 hour — tap to change. Profile wake 07:00 is the fallback. " +
            "The complete explanation remains available even when the text is large."
        ui.compose.runOnIdle { ui.model.report(CommandOutcome(OutcomeKind.LOCAL, message)) }
        ui.waitUntil { ui.compose.onAllNodesWithText(message).fetchSemanticsNodes().isNotEmpty() }
        ui.compose.mainClock.advanceTimeBy(300)
        ui.control(ui.s(R.string.recovery_recheck_alarm)).assertIsDisplayed()
        ui.compose.onNodeWithText(message).assertExists()
        ui.compose.onNodeWithTag("snackbar-message-scroll").assert(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollBy))
            .performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 1000f) }
        ui.compose.onNodeWithText(message).assertExists()
        ui.checkControl(ui.compose.onNodeWithTag("snackbar-dismiss"), "Dismiss feedback", scroll = false).assertHasClickAction().performClick()
        ui.waitUntil { ui.compose.onAllNodesWithText(message).fetchSemanticsNodes().isEmpty() }
        ui.control(ui.s(R.string.recovery_recheck_alarm)).assertIsDisplayed()
    }
}
