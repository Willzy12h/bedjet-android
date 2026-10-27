package io.github.willzy12h.nocturne.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.lifecycle.Lifecycle
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.store.Settings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real screen, repository and owner: order/collapse survive leaving Tonight; progress never shifts controls. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class TonightLayoutTest {
    private val ui = U06Fixture(U06Look.MIDNIGHT, widthDp = 411, fontScale = 1f)
    @get:Rule val rules = ui.rules
    private fun tab(id: Int) {
        ui.compose.onNode(hasText(ui.s(id)) and hasClickAction()).performClick()
        ui.compose.waitForIdle()
    }
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private fun saved() = json.encodeToString(Settings.serializer(), ui.graph.repository.settings.value)

    @Test fun quickControlsComeBeforeThePlanAndCollapsePersists() {
        val quick = ui.compose.onNode(hasText(ui.s(R.string.tonight_quick)) and hasClickAction())
        quick.assertIsDisplayed().performClick()
        ui.waitUntil { saved().contains("\"quickControlsCollapsed\":true") }
        ui.compose.onNodeWithTag("quick-mode-HEAT").assertDoesNotExist()
        tab(R.string.tab_settings); tab(R.string.tab_tonight)
        ui.compose.onNodeWithTag("quick-mode-HEAT").assertDoesNotExist()
        quick.performScrollTo().performClick()
        ui.waitUntil { !ui.graph.repository.settings.value.quickControlsCollapsed }
        val warm = ui.compose.onNodeWithTag("quick-mode-HEAT")
        warm.performScrollTo().assertIsDisplayed()
        val section = ui.compose.onNodeWithTag("tonight-section-night").getUnclippedBoundsInRoot()
        assertTrue(warm.getUnclippedBoundsInRoot().bottom < section.top)
    }

    @Test fun wakeStaysAtTheTopRightWhenTheSectionsScroll() {
        val wake = ui.compose.onNodeWithTag("tonight-fixed-wake")
        val before = wake.getUnclippedBoundsInRoot()
        assertTrue("Wake occupies the right side", (before.left.value + before.right.value) / 2 > 205f)
        ui.compose.onNodeWithTag("tonight-content").performTouchInput { swipeUp() }
        val after = wake.getUnclippedBoundsInRoot()
        assertEquals(before, after)
        wake.assertIsDisplayed()
    }

    @Test fun accessibleMoveActionsPersistTheOrder() {
        ui.compose.onNodeWithContentDescription("Move Night down").performScrollTo().performClick()
        ui.waitUntil { saved().contains("\"tonightSectionOrder\":[\"wake\",\"night\",\"connection\"]") }
        tab(R.string.tab_settings); tab(R.string.tab_tonight)
        val wake = ui.compose.onNodeWithTag("tonight-section-wake").getUnclippedBoundsInRoot()
        val night = ui.compose.onNodeWithTag("tonight-section-night").getUnclippedBoundsInRoot()
        assertTrue(wake.top < night.top)
        ui.compose.onNodeWithContentDescription("Move Night up").performScrollTo().performClick()
        ui.waitUntil { saved().contains("\"tonightSectionOrder\":[\"night\",\"wake\",\"connection\"]") }
    }

    @Test fun draggingASectionActuallyChangesTheSavedOrder() {
        ui.compose.onNodeWithText("Reorder sections").performScrollTo().performClick()
        val source = ui.compose.onNodeWithTag("drag-night")
        val target = ui.compose.onNodeWithTag("drag-wake")
        val distance = target.fetchSemanticsNode().boundsInRoot.center.y - source.fetchSemanticsNode().boundsInRoot.center.y
        source.performTouchInput {
            down(center)
            advanceEventTime(600)
            moveBy(Offset(0f, distance), delayMillis = 250)
            up()
        }
        ui.waitUntil { saved().contains("\"tonightSectionOrder\":[\"wake\",\"night\",\"connection\"]") }
        ui.compose.onNodeWithText("Done reordering").performClick()
        assertTrue(ui.compose.onNodeWithTag("tonight-section-wake").getUnclippedBoundsInRoot().top <
            ui.compose.onNodeWithTag("tonight-section-night").getUnclippedBoundsInRoot().top)
    }

    @Test fun malformedStoredOrderKeepsEverySectionExactlyOnce() {
        val malformed = json.decodeFromString(Settings.serializer(), saved().dropLast(1) +
            ",\"tonightSectionOrder\":[\"connection\",\"connection\",\"unknown\"]}")
        runBlocking { ui.graph.repository.updateSettings { malformed } }
        tab(R.string.tab_settings); tab(R.string.tab_tonight)
        ui.compose.onAllNodesWithTag("tonight-section-connection").assertCountEquals(1)
        ui.compose.onAllNodesWithTag("tonight-section-night").assertCountEquals(1)
        ui.compose.onAllNodesWithTag("tonight-section-wake").assertCountEquals(1)
        assertTrue(ui.compose.onNodeWithTag("tonight-section-connection").getUnclippedBoundsInRoot().top <
            ui.compose.onNodeWithTag("tonight-section-night").getUnclippedBoundsInRoot().top)
    }

    @Test fun queuedWorkDoesNotMoveTheQuickControls() {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = ui.graph.scope.launch { ui.graph.actions.exclusive { entered.complete(Unit); release.await() } }
        ui.waitUntil { entered.isCompleted }
        val quick = ui.compose.onNodeWithText(ui.s(R.string.tonight_quick))
        quick.performScrollTo()
        val before = quick.getUnclippedBoundsInRoot()
        try {
            ui.compose.runOnUiThread { ui.model.run(io.github.willzy12h.nocturne.night.NightActions.Command.WARM) }
            ui.waitUntil { ui.graph.actions.busy.value.queuedCount > 0 }
            assertEquals(before, quick.getUnclippedBoundsInRoot())
        } finally { release.complete(Unit); holder.cancel() }
    }

    @Test fun reducedMotionDoesNotInventACompletionPercentageForBusyWork() {
        android.provider.Settings.Global.putFloat(ui.compose.activity.contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        val gate = CompletableDeferred<Unit>()
        ui.compose.runOnUiThread { ui.graph.scope.launch { ui.graph.actions.remote("Lights test") { gate.await() } } }
        try {
            ui.waitUntil { ui.graph.actions.busy.value.runningLabel == "Lights test" }
            ui.compose.onNode(hasProgressBarRangeInfo(androidx.compose.ui.semantics.ProgressBarRangeInfo.Indeterminate)).assertExists()
            ui.compose.onNodeWithText(ui.s(R.string.busy_running, "Lights test")).assertIsDisplayed()
        } finally { gate.complete(Unit) }
        ui.waitUntil { ui.graph.actions.busy.value.runningLabel == null }
    }

    @Test fun aCurveLongPressUsesTheLatestProfileWhenItsProgrammeIsUnchanged() {
        val selected = requireNotNull(ui.model.tonightsProfile())
        val renamed = selected.copy(name = "Renamed current profile")
        ui.compose.runOnUiThread { runBlocking { ui.graph.repository.saveProfile(renamed) } }
        ui.compose.onNode(hasText(renamed.name) and hasClickAction()).assertExists()
        ui.compose.onNodeWithTag("night-curve").performScrollTo().performTouchInput {
            longClick(Offset(width * .03f, height / 2f))
        }
        ui.compose.onNode(hasText(ui.s(R.string.editor_name)) and hasSetTextAction()).assertTextContains(renamed.name)
    }

    @Test fun pausedTonightDoesNotRunItsThirtySecondRefreshLoop() {
        ui.compose.waitForIdle()
        val model = ui.model
        assertNull(model.nextAlarm.value)
        val activity = ui.compose.activity
        val manager = activity.getSystemService(android.app.AlarmManager::class.java)
        val pending = android.app.PendingIntent.getBroadcast(activity, 771, android.content.Intent("ux.alarm.refresh"), android.app.PendingIntent.FLAG_IMMUTABLE)
        org.robolectric.Shadows.shadowOf(pending).setCreatorPackage("com.sec.android.app.clockpackage")
        ui.compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        manager.setAlarmClock(android.app.AlarmManager.AlarmClockInfo(ui.graph.now().plusHours(2).toInstant().toEpochMilli(), pending), pending)
        try {
            ui.compose.mainClock.advanceTimeBy(60_001)
            assertNull("A paused Tonight must not poll AlarmManager", model.nextAlarm.value)
        } finally { ui.compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED) }
        ui.waitUntil { model.nextAlarm.value != null }
    }
}
