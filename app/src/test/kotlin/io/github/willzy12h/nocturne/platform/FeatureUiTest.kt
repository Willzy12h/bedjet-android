package io.github.willzy12h.nocturne.platform
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import io.github.willzy12h.nocturne.graph
import io.github.willzy12h.nocturne.ui.features.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class) @Config(sdk=[34])
class FeatureUiTest : io.github.willzy12h.nocturne.platform.PlatformGraphFixture() {
    @get:Rule val compose=createComposeRule()
    @Test fun settingsExplainsBestEffortAndSupervisedAcceptance() {
        val graph=(RuntimeEnvironment.getApplication() as Context).graph
        compose.setContent { MaterialTheme { PlatformSettingsSection(graph) } }
        compose.onNodeWithText("Charger auto-start").assertIsDisplayed()
        compose.onNodeWithContentDescription("Charger auto-start").assertIsDisplayed()
        compose.onNodeWithText("Supervised acceptance pending",substring=true).assertIsDisplayed()
        compose.onNodeWithText("after restoring",substring=true).assertIsDisplayed()
    }
    @Test fun historyExportRequiresExplicitDeviceIdentifierChoice() {
        val graph=(RuntimeEnvironment.getApplication() as Context).graph
        compose.setContent { MaterialTheme { HistoryExportButton(graph) } }
        compose.onNodeWithText("Export history CSV").performClick()
        compose.onNodeWithText("Include device identifiers").assertIsDisplayed()
    }
    @Test fun manualPhoneReleaseIsVisible() {
        val graph=(RuntimeEnvironment.getApplication() as Context).graph
        compose.setContent { MaterialTheme { Column { PhoneHandoverMenuItems(graph) {} } } }
        compose.onNodeWithText("Release BedJet for another phone").assertIsDisplayed()
    }
}
