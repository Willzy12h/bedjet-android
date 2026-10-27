package io.github.willzy12h.nocturne.platform
import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertIsOff
import io.github.willzy12h.nocturne.App
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.ble.DeviceNames
import io.github.willzy12h.nocturne.core.link.DemoBedJet
import io.github.willzy12h.nocturne.graph
import io.github.willzy12h.nocturne.night.CommandOrigin
import io.github.willzy12h.nocturne.night.CommandOutcome
import io.github.willzy12h.nocturne.night.OutcomeKind
import io.github.willzy12h.nocturne.ui.features.*
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicReference
@RunWith(RobolectricTestRunner::class) @Config(sdk=[34])
class FeatureUiTest : io.github.willzy12h.nocturne.platform.PlatformGraphFixture() {
    @get:Rule val compose=createComposeRule()
    @Test fun settingsExplainsAutoStartTimingAndOptionalHealthExport() {
        val graph=(RuntimeEnvironment.getApplication() as Context).graph
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(false)
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            PlatformSettingsSection(graph)
        } } }
        compose.onNodeWithText("Auto-start").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Auto-start").assertIsDisplayed().assertIsOff()
        compose.onNodeWithText("Skip if a night is already running", substring=true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Only if the phone is charging").performScrollTo().assertIsDisplayed().assertIsOff()
        compose.onNodeWithText("15-minute window", substring=true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Export BedJet programmes").performScrollTo().assertIsDisplayed().assertIsOff()
        compose.onNodeWithText("not verified sleep", substring=true).performScrollTo().assertIsDisplayed()
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
