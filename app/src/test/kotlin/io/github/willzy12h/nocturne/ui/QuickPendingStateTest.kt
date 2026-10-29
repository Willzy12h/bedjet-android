package io.github.willzy12h.nocturne.ui

import androidx.compose.ui.test.*
import android.os.Looper
import org.robolectric.Shadows.shadowOf
import java.time.Duration
import io.github.willzy12h.nocturne.core.protocol.Mode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Pending belongs to the submitted operation, even when its busy emissions fit between UI frames. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class QuickPendingStateTest {
    private val ui = U06Fixture(U06Look.MIDNIGHT, widthDp = 411, fontScale = 1f)
    @get:Rule val rules = ui.rules
    @After fun restoreClock() { ui.compose.mainClock.autoAdvance = true }

    @Test fun completedPresetClearsSendingWithoutWaitingForAnUnrelatedTimer() {
        val heat = ui.compose.onNodeWithTag("quick-mode-HEAT").performScrollTo()
        ui.compose.mainClock.autoAdvance = false
        heat.performClick()
        ui.waitUntil { ui.graph.link.status.value?.mode == Mode.HEAT &&
            ui.graph.actions.busy.value.runningLabel == null && !ui.model.busy.value }
        // Finish the model -> snapshot -> layout frame hand-off, without advancing a 15-second fallback.
        repeat(3) { ui.compose.mainClock.advanceTimeByFrame(); ui.compose.waitForIdle() }
        ui.compose.onNodeWithTag("quick-sending-HEAT", useUnmergedTree = true).assertDoesNotExist()
        ui.compose.onNodeWithTag("quick-active-HEAT", useUnmergedTree = true).assertExists()
    }

    @Test fun queuedPresetRemainsSendingUntilItsOwnOperationFinishes() {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = ui.graph.scope.launch { ui.graph.actions.exclusive { entered.complete(Unit); release.await() } }
        try {
            ui.waitUntil { entered.isCompleted }
            ui.compose.onNodeWithTag("quick-mode-HEAT").performScrollTo().performClick()
            ui.waitUntil { ui.graph.actions.busy.value.queuedCount > 0 }
            ui.compose.onNodeWithTag("quick-sending-HEAT", useUnmergedTree = true).assertExists()
            ui.compose.mainClock.autoAdvance = false
            ui.compose.mainClock.advanceTimeBy(15_001)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(15_001))
            ui.compose.mainClock.advanceTimeByFrame()
            assertTrue("The command is still queued behind the held owner", ui.graph.actions.busy.value.queuedCount > 0)
            ui.compose.onNodeWithTag("quick-sending-HEAT", useUnmergedTree = true).assertExists()
        } finally { release.complete(Unit); holder.cancel() }
        ui.waitUntil { ui.graph.actions.busy.value.runningLabel == null && !ui.model.busy.value }
    }
    @Test fun aCompletedSubmissionCannotClearBusyWhileAnotherSubmissionOwnsTheDevice() {
        val enteredFirst = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val enteredSecond = CompletableDeferred<Unit>()
        val releaseSecond = CompletableDeferred<Unit>()
        val firstFeedback = ui.graph.scope.async(start = CoroutineStart.UNDISPATCHED) { ui.model.messages.first() }
        try {
            ui.model.remote("First", confirms = { it.mode == Mode.HEAT }) {
                enteredFirst.complete(Unit); releaseFirst.await(); it.setMode(Mode.HEAT)
            }
            ui.waitUntil { enteredFirst.isCompleted }
            ui.model.remote("Second", confirms = { it.mode == Mode.COOL }) {
                enteredSecond.complete(Unit); releaseSecond.await(); it.setMode(Mode.COOL)
            }
            ui.waitUntil { ui.graph.actions.busy.value.queuedCount > 0 }
            releaseFirst.complete(Unit)
            ui.waitUntil { firstFeedback.isCompleted && enteredSecond.isCompleted }
            assertTrue("The second submitted operation still owns the device", ui.model.busy.value)
        } finally { releaseFirst.complete(Unit); releaseSecond.complete(Unit); firstFeedback.cancel() }
        ui.waitUntil { ui.graph.actions.busy.value.runningLabel == null && !ui.model.busy.value }
    }

}
