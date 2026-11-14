package io.github.willzy12h.nocturne.platform

import android.Manifest
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.core.link.DemoBedJet
import io.github.willzy12h.nocturne.core.store.*
import io.github.willzy12h.nocturne.ui.features.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class) @Config(sdk = [34])
class PlatformSettingsUiTest : PlatformGraphFixture() {
    @get:Rule val compose = createComposeRule()
    private fun setup(demo: Boolean = false): Graph {
        val context: Context = RuntimeEnvironment.getApplication()
        val graph = fresh { Graph(context, DemoBedJet()) }
        graph.clock = Clock.fixed(Instant.parse("2026-10-26T20:00:00Z"), ZoneOffset.UTC)
        runBlocking { graph.repository.updateSettings { it.copy(demoMode = demo, targetDevice = "ble:AA:BB:CC:DD:EE:FF") } }
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        PlatformNotifications.channel(context)
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(false)
        return graph
    }
    private fun content(graph: Graph) = compose.setContent {
        MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            AutoStartSettingsSection(graph)
        } }
    }

    @Test fun automaticStartOffersFixedAndRelativeTimingWithChargingOffByDefault() {
        val graph = setup(); content(graph)
        compose.onNodeWithContentDescription("Auto-start").assertIsOff().performClick()
        compose.waitUntil(5_000) { graph.repository.settings.value.platform.autoStart?.enabled == true }
        compose.onNodeWithText("Fixed time").assertExists()
        compose.onNodeWithText("Before wake time").performClick()
        compose.waitUntil(5_000) { graph.repository.settings.value.platform.autoStart?.mode == AutoStartMode.BEFORE_WAKE }
        compose.onNodeWithContentDescription("Only if the phone is charging").performScrollTo().assertIsOff()
        compose.onNodeWithText("profile fallback", substring = true).assertExists()
        compose.onNodeWithText("15-minute window", substring = true).assertExists()
        assertEquals(graph.repository.profiles.value.first().id, graph.repository.settings.value.platform.autoStart!!.profileId)
    }

    @Test fun demoCannotRequestExternalPermissionsOrExportRealHistoryFiles() {
        val graph = setup(demo = true)
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            AutoStartSettingsSection(graph); HistoryExportButton(graph)
        } } }
        compose.onNodeWithContentDescription("Auto-start").assertIsNotEnabled()
        compose.onNodeWithText("Export history CSV").performScrollTo().assertIsNotEnabled()
    }

    @Test fun editingTimingOnAnotherSelectedBedJetDoesNotRebindEnabledAutoStart() {
        val graph = setup()
        val bound = "ble:AA:BB:CC:DD:EE:FF"
        runBlocking { graph.repository.updateSettings { it.copy(platform = it.platform.copy(autoStart = AutoStartSettings(
            enabled = true, profileId = graph.repository.profiles.value.first().id, deviceId = bound))) } }
        content(graph)
        runBlocking { graph.repository.updateSettings { it.copy(targetDevice = "ble:11:22:33:44:55:66") } }
        compose.onNodeWithText("Before wake time").performClick()
        compose.waitUntil(5_000) { graph.repository.settings.value.platform.autoStart?.mode == AutoStartMode.BEFORE_WAKE }
        assertEquals("An edit must preserve the explicitly authorised device binding", bound,
            graph.repository.settings.value.platform.autoStart!!.deviceId)
        assertNull(AutoStartSchedule.next(graph))
    }

    @Test fun deletingTheChosenProfileDoesNotPreventTurningAutoStartOff() {
        val graph = setup()
        val selected = graph.repository.profiles.value.first().id
        runBlocking {
            graph.repository.updateSettings { it.copy(platform = it.platform.copy(autoStart = AutoStartSettings(
                enabled = true, profileId = selected, deviceId = it.targetDevice))) }
            graph.repository.deleteProfile(selected)
        }
        content(graph)
        compose.onNodeWithContentDescription("Auto-start").assertIsOn().assertIsEnabled().performClick()
        compose.waitUntil(5_000) { graph.repository.settings.value.platform.autoStart?.enabled == false }
        assertNull(AutoStartSchedule.next(graph))
    }

    @Test fun aPausedScheduleCanBeDisabledWithoutSelectingAnotherBedJet() {
        val graph = setup()
        runBlocking { graph.repository.updateSettings { it.copy(targetDevice = null,
            platform = it.platform.copy(autoStart = AutoStartSettings(enabled = true,
                profileId = graph.repository.profiles.value.first().id, deviceId = "ble:AA:BB:CC:DD:EE:FF"))) } }
        content(graph)
        compose.onNodeWithContentDescription("Auto-start").assertIsOn().assertIsEnabled().performClick()
        compose.waitUntil(5_000) { graph.repository.settings.value.platform.autoStart?.enabled == false }
        assertNull(AutoStartSchedule.next(graph))
    }

}
