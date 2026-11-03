package io.github.willzy12h.nocturne.platform

import io.github.willzy12h.nocturne.operationIntent
import android.app.AlarmManager
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.link.DemoBedJet
import io.github.willzy12h.nocturne.core.store.AutoStartSettings
import io.github.willzy12h.nocturne.ui.features.AutoStartSettingsSection
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

@RunWith(RobolectricTestRunner::class) @Config(sdk = [34])
class AutoStartPreviewTest : PlatformGraphFixture() {
    @get:Rule val compose = createComposeRule()

    @Test fun claimingTonightUpdatesTheVisibleNextAttemptToTheActualFollowingNightAlarm() {
        val context: Context = RuntimeEnvironment.getApplication()
        val graph = fresh { Graph(context, DemoBedJet()) }
        val device = "ble:AA:BB:CC:DD:EE:FF"
        graph.clock = Clock.fixed(Instant.parse("2026-10-26T22:00:00Z"), ZoneOffset.UTC)
        runBlocking { graph.repository.updateSettings { it.copy(demoMode = false, targetDevice = device,
            platform = it.platform.copy(autoStart = AutoStartSettings(enabled = true, fixedMinute = 23 * 60,
                profileId = graph.repository.profiles.value.first().id, deviceId = device))) } }
        fun label(at: String) = context.getString(R.string.auto_start_next,
            ZonedDateTime.parse(at).format(DateTimeFormatter.ofPattern("EEE HH:mm")))
        ChargerJobs.sync(graph)
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            AutoStartSettingsSection(graph)
        } } }
        compose.onNodeWithText(label("2026-10-26T23:00Z")).performScrollTo().assertIsDisplayed()
        // A durable attempted-night claim can arrive while Settings stays on screen.
        runBlocking { graph.repository.claimAutomation("charger:$device:2026-10-26") }
        ChargerJobs.sync(graph)
        val actual = shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.filter {
            shadowOf(it.operationIntent).savedIntent.component?.className == AutoStartReceiver::class.java.name
        }.single()
        assertEquals(Instant.parse("2026-10-27T23:00:00Z").toEpochMilli(), actual.triggerAtMs)
        compose.waitForIdle()
        compose.onNodeWithText(label("2026-10-27T23:00Z")).performScrollTo().assertIsDisplayed()
    }
}
