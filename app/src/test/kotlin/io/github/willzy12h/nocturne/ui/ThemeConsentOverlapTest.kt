package io.github.willzy12h.nocturne.ui

import android.content.Intent
import android.os.Looper
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.*
import io.github.willzy12h.nocturne.core.store.ThemeMode
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** B4: “until tonight” ends at the next real dark period, including a repeated local hour. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-notnight-xhdpi")
class ThemeConsentOverlapTest {
    private val zone = ZoneId.of("Europe/London")
    private val ui = U06Fixture(U06Look.MIDNIGHT, widthDp = 411, fontScale = 1f) { f, _ ->
        // 01:45 BST: the first occurrence of the 01:30–02:00 dark period.
        f.graph.clock = Clock.fixed(Instant.parse("2026-10-25T00:45:00Z"), zone)
        runBlocking { f.graph.repository.updateSettings { it.copy(
            themeMode = ThemeMode.SCHEDULED, scheduledDarkMinute = 90, scheduledLightMinute = 120,
        ) } }
    }
    @get:Rule val rules = ui.rules

    private fun moveClock(instant: String) {
        ui.compose.runOnUiThread {
            ui.graph.clock = Clock.fixed(Instant.parse(instant), zone)
            ui.compose.activity.sendBroadcast(Intent(Intent.ACTION_TIME_CHANGED))
        }
        repeat(3) {
            Snapshot.sendApplyNotifications()
            ui.compose.mainClock.advanceTimeByFrame()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
        }
        ui.compose.waitForIdle()
    }

    @Test fun darkHoldExpiresAtSecondDarkBoundaryInRepeatedHour() {
        // 01:15 GMT: the clock has repeated the light interval.
        moveClock("2026-10-25T01:15:00Z")
        ui.compose.onNodeWithText("Light theme is due").assertIsDisplayed()
        ui.compose.onNodeWithText("Stay dark until tonight").performClick()

        // The second 01:30 is the next dark period and must release the hold.
        moveClock("2026-10-25T01:30:01Z")
        ui.compose.onNodeWithText("Light theme is due").assertDoesNotExist()

        // After that dark period ends, the normal light-transition choice is due again.
        moveClock("2026-10-25T02:00:01Z")
        ui.compose.onNodeWithText("Light theme is due").assertIsDisplayed()
    }
}
