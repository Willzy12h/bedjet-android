package io.github.willzy12h.nocturne.ui

import android.content.Intent
import android.content.ContextWrapper
import android.content.ActivityNotFoundException
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.compose.setContent
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import io.github.willzy12h.nocturne.core.store.Palette
import androidx.compose.ui.test.*
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.store.EventKind
import io.github.willzy12h.nocturne.core.store.HistoryEvent
import io.github.willzy12h.nocturne.core.store.NightRecord
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowToast
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w320dp-h891dp-notnight-xhdpi")
class P6SettingsHistoryTest {
    private val ui = U06Fixture(U06Look.SIMPLE_MIDNIGHT)
    @get:Rule val rules = ui.rules

    @Test fun sourceCodeRowOpensTheExactRepositoryInBrowser() {
        ui.tab("Settings")
        ui.compose.openSettingsPage("about")
        ui.control("Nocturne on GitHub").performClick()
        val intent = shadowOf(ui.compose.activity).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("https://github.com/Willzy12h/bedjet-android", intent.dataString)
    }

    @Test fun missingOrBlockedBrowserIsHandledWithoutThrowing() {
        val base = ui.compose.activity
        val missing = object : ContextWrapper(base) {
            override fun startActivity(intent: Intent) { throw ActivityNotFoundException() }
        }
        val blocked = object : ContextWrapper(base) {
            override fun startActivity(intent: Intent) { throw SecurityException() }
        }
        ShadowToast.reset()
        assertFalse(openSourceCode(missing))
        assertEquals(base.getString(R.string.settings_source_unavailable), ShadowToast.getTextOfLatestToast())
        ShadowToast.reset()
        assertFalse(openSourceCode(blocked))
        assertEquals(base.getString(R.string.settings_source_unavailable), ShadowToast.getTextOfLatestToast())
    }

    @Test fun historyIssueChipUsesWarnInBothPalettes() {
        val record = NightRecord("p6-warning", "p", "Problem night", "2026-10-22T22:30Z", "2026-10-23T07:00Z", false,
            events = listOf(HistoryEvent("2026-10-23T01:00Z", EventKind.ERROR, "Outcome unknown")))
        ui.compose.runOnUiThread { runBlocking { ui.graph.repository.addRecord(record) } }
        val palette = mutableStateOf(Palette.MIDNIGHT_INK)
        ui.compose.runOnUiThread { ui.compose.activity.setContent {
            val settings by ui.graph.repository.settings.collectAsState()
            NocturneTheme(settings, ui.graph.now(), paletteOverride = palette.value) {
                Surface(color = palette.value.tokens.ground) { HistoryScreen(ui.model, Modifier) }
            }
        } }
        for (choice in listOf(Palette.MIDNIGHT_INK, Palette.OVERCAST)) {
            ui.compose.runOnIdle { palette.value = choice }
            val chip = ui.compose.onNodeWithTag("history-issues-p6-warning", useUnmergedTree = true).performScrollTo()
            ui.compose.onNodeWithText("1 issue").assertExists()
            val bounds = chip.fetchSemanticsNode().boundsInRoot
            val view = ui.compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            ui.compose.runOnUiThread { view.draw(Canvas(bitmap)) }
            var matches = 0
            for (y in bounds.top.toInt().coerceAtLeast(0) until bounds.bottom.toInt().coerceAtMost(bitmap.height))
                for (x in bounds.left.toInt().coerceAtLeast(0) until bounds.right.toInt().coerceAtMost(bitmap.width))
                    if (bitmap.getPixel(x, y) == choice.tokens.warn.toArgb()) matches++
            bitmap.recycle()
            assertTrue("$choice issue chip should draw the warning token", matches > 0)
        }
    }

    @Test fun unknownHistoryErrorDoesNotClaimTheCommandWasNotSent() {
        val record = NightRecord("p6-unknown", "p", "Uncertain night", "2026-10-22T22:30Z", "2026-10-23T07:00Z", false,
            events = listOf(HistoryEvent("2026-10-23T01:00Z", EventKind.ERROR, "Outcome unknown")))
        ui.compose.runOnUiThread { runBlocking { ui.graph.repository.addRecord(record) } }
        ui.tab("History")
        ui.compose.onNodeWithText("Uncertain night", substring = true).performClick()
        ui.compose.onNodeWithText("Outcome unknown", substring = true).assertExists()
        ui.compose.onAllNodesWithText("didn't send", substring = true).assertCountEquals(0)
    }
}
