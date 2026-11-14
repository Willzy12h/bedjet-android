package io.github.willzy12h.nocturne.ui

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Looper
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import io.github.willzy12h.nocturne.App
import io.github.willzy12h.nocturne.GraphLifecycle
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.night.Confidence
import io.github.willzy12h.nocturne.core.night.WakeOrigin
import io.github.willzy12h.nocturne.core.store.ThemeMode
import io.github.willzy12h.nocturne.graph
import io.github.willzy12h.nocturne.night.OutcomeKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime

/** Real screen actions, lifecycle refresh and command-owner invariants. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class TonightScreenActionsTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val fixture = TestRule { base, _ -> object : Statement() {
        override fun evaluate() {
            val app = RuntimeEnvironment.getApplication() as App
            shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS)
            app.graph.clock = Clock.fixed(Instant.parse("2026-10-22T22:30:00Z"), ZoneOffset.UTC)
            runBlocking {
                app.graph.repository.setActiveNight(null)
                app.graph.repository.updateSettings { it.copy(demoMode = true, themeMode = ThemeMode.DARK, tonightChoice = null, tonightOverride = null) }
            }
            base.evaluate()
        }
    } }
    @get:Rule val rules: TestRule = RuleChain.outerRule(GraphLifecycle()).around(fixture).around(compose)
    private val graph get() = compose.activity.graph
    private val model get() = ViewModelProvider(compose.activity)[AppViewModel::class.java]
    private fun s(id: Int) = compose.activity.getString(id)
    private fun waitUntil(condition: () -> Boolean) = compose.waitUntil(10_000) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50)); condition()
    }
    private fun has(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    private fun settings(change: (io.github.willzy12h.nocturne.core.store.Settings) -> io.github.willzy12h.nocturne.core.store.Settings) {
        compose.runOnUiThread { runBlocking { graph.repository.updateSettings(change) } }
        compose.waitForIdle()
    }

    @Test fun selectingATargetWithoutOptingIntoRealModePreservesDemoMode() {
        val target = "ble:AA:BB:CC:DD:EE:FF"
        val outcome = runBlocking { graph.actions.selectTarget(target) }
        assertEquals(OutcomeKind.LOCAL, outcome.kind)
        assertEquals(target, graph.repository.settings.value.targetDevice)
        assertTrue(graph.repository.settings.value.demoMode)
    }

    @Test fun invalidRealTargetIsRefusedWithoutChangingAnySettings() {
        settings { it.copy(targetDevice = "ble:AA:BB:CC:DD:EE:FF", setupFanCheckedDevice = "ble:AA:BB:CC:DD:EE:FF") }
        val before = graph.repository.settings.value
        val outcome = runBlocking { graph.actions.selectTarget("ble:not-an-address", useRealDevice = true) }
        assertEquals(OutcomeKind.REFUSED, outcome.kind)
        assertEquals(before, graph.repository.settings.value)
    }

    @Test fun grantingNearbyPermissionWhilePausedRemovesItsMissingSetupCheckOnResume() {
        shadowOf(compose.activity.application).denyPermissions(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        settings { it.copy(demoMode = false, targetDevice = "ble:AA:BB:CC:DD:EE:FF") }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        val missing = s(R.string.setup_checks_nearby)
        waitUntil { has(missing) }
        compose.onNodeWithText(missing).performScrollTo().assertIsDisplayed()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        shadowOf(compose.activity.application).grantPermissions(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        // The setup list's Bluetooth item covers both the permission and Bluetooth being on.
        shadowOf(compose.activity.getSystemService(android.bluetooth.BluetoothManager::class.java).adapter).setEnabled(true)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        waitUntil { !has(missing) }
        compose.onNodeWithText(missing).assertDoesNotExist()
    }

    private fun chooseAlternative(): String {
        val scheduled = graph.repository.settings.value.resolveProfile(graph.repository.profiles.value, graph.now())!!
        val other = graph.repository.profiles.value.first { it.id != scheduled.id }
        compose.onNodeWithTag("tonight-change").performScrollTo().performClick()
        compose.onNodeWithTag("tonight-choose-${other.id}").performScrollTo().performClick()
        waitUntil { graph.repository.settings.value.tonightChoice?.profileId == other.id }
        compose.onNode(hasText(other.name, substring = true, ignoreCase = true) and hasAnyAncestor(hasTestTag("tonight-card")), useUnmergedTree = true).assertExists()
        return scheduled.name
    }

    @Test fun followScheduleClearsTheTonightOnlyChoiceAndHidesTheButton() {
        val scheduledName = chooseAlternative()
        compose.onNodeWithText(s(R.string.tonight_follow_schedule)).performScrollTo().performClick()
        waitUntil { graph.repository.settings.value.tonightChoice == null }
        compose.onNodeWithText(s(R.string.tonight_follow_schedule)).assertDoesNotExist()
        compose.onNode(hasText(scheduledName, substring = true, ignoreCase = true) and hasAnyAncestor(hasTestTag("tonight-card")), useUnmergedTree = true).assertExists()
    }

    @Test fun heldOpenTonightReturnsItsChipToTheScheduleAfterNoonAndARefreshTick() {
        chooseAlternative()
        val expiry = ZonedDateTime.parse(graph.repository.settings.value.tonightChoice!!.expiresAt)
        compose.runOnUiThread { graph.clock = Clock.fixed(expiry.plusSeconds(1).toInstant(), ZoneOffset.UTC) }
        // Keep the same activity and composition: only the real screen's refresh delay is advanced.
        compose.mainClock.advanceTimeBy(30_001)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(31))
        compose.waitForIdle()
        val scheduled = graph.repository.settings.value.resolveProfile(graph.repository.profiles.value, graph.now())!!
        compose.onNode(hasText(scheduled.name, substring = true, ignoreCase = true) and hasAnyAncestor(hasTestTag("tonight-card")), useUnmergedTree = true).assertExists()
        compose.onNodeWithText(s(R.string.tonight_follow_schedule)).assertDoesNotExist()
    }

    @Test fun sixOClockTonightWakeWinsOverSevenOClockAlarmInHeadlineAndStart() {
        val graph = graph
        val now = graph.now()
        val wake = ZonedDateTime.parse("2026-10-23T06:00:00Z")
        val alarm = wake.plusHours(1)
        val fire = PendingIntent.getBroadcast(compose.activity, 17, Intent("gaps-alarm"), PendingIntent.FLAG_IMMUTABLE)
        compose.activity.getSystemService(AlarmManager::class.java).setAlarmClock(AlarmManager.AlarmClockInfo(alarm.toInstant().toEpochMilli(), io.github.willzy12h.nocturne.clockAlarmShow(compose.activity)), fire)
        settings { it.setWakeOverride(wake, now) }
        compose.runOnUiThread { model.refreshAlarm() }
        assertEquals(alarm.toInstant(), model.nextAlarm.value!!.time.toInstant())
        assertTrue("The 07:00 alarm must qualify before the tonight-only wake takes precedence", model.nextAlarm.value!!.counts)
        compose.onNodeWithTag("tonight-fixed-wake").assertTextContains("06:00", substring = true)
            .assertTextContains(s(R.string.gpt12_wake_override), substring = true)
        compose.onNodeWithText(s(R.string.tonight_start_now)).performScrollTo().performClick()
        waitUntil { graph.repository.activeNight.value?.confidence == Confidence.CONFIRMED }
        val running = graph.repository.activeNight.value!!
        assertEquals(wake.toInstant(), ZonedDateTime.parse(running.wakeAt).toInstant())
        assertEquals(WakeOrigin.OVERRIDE, running.wakeOrigin)
    }

    @Test fun outOfRangeWakeExplainsWhyAndOffersToChooseAWake() {
        val now = graph.now()
        val wake = now.plusMinutes(30)
        settings { it.setWakeOverride(wake, now) }
        val reason = compose.activity.getString(R.string.tonight_wake_out_of_range, wake.format(java.time.format.DateTimeFormatter.ofPattern("EEE HH:mm")))
        compose.onNodeWithText(reason).performScrollTo().assertIsDisplayed()
        // Start stays available: it asks for a wake that fits, and nothing starts until one is chosen.
        compose.onNodeWithText(s(R.string.tonight_start_now)).assertDoesNotExist()
        compose.onNodeWithText(s(R.string.tonight_choose_wake_and_start)).performScrollTo().assertIsEnabled()
        assertNull(graph.repository.activeNight.value)
    }
}
