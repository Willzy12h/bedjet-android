package io.github.willzy12h.nocturne.ui

import android.os.Looper
import androidx.compose.ui.test.*
import androidx.lifecycle.Lifecycle
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class PinPreviewLifecycleTest {
    private val ui = U06Fixture(U06Look.MIDNIGHT, widthDp = 411, fontScale = 1f)
    @get:Rule val rules = ui.rules
    @After fun restoreClock() { ui.compose.mainClock.autoAdvance = true }

    @Test fun aStoppedPinPreviewDoesNotKeepItsFiveSecondTimer() {
        ui.compose.onNodeWithText("Profiles").performClick()
        ui.compose.onAllNodesWithText("More")[0].performScrollTo().performClick()
        ui.compose.onNodeWithText("Keep on BedJet slot…").performClick()
        ui.compose.waitForIdle()
        val base = ui.graph.clock
        val reads = AtomicInteger()
        ui.graph.clock = object : Clock() {
            override fun getZone(): ZoneId = base.zone
            override fun withZone(zone: ZoneId): Clock = base.withZone(zone)
            override fun instant(): Instant { reads.incrementAndGet(); return base.instant() }
        }
        ui.compose.mainClock.autoAdvance = false
        ui.compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        shadowOf(Looper.getMainLooper()).idle()
        val stoppedReads = reads.get()
        // Do not query a stopped UI hierarchy; observe the production clock callback itself.
        ui.compose.mainClock.advanceTimeBy(20_001)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20_001))
        assertEquals("A hidden preview must leave its expiry timer suspended", stoppedReads, reads.get())
    }
}
