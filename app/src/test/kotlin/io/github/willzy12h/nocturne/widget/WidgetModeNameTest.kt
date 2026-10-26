package io.github.willzy12h.nocturne.widget

import android.content.Context
import io.github.willzy12h.nocturne.graph
import io.github.willzy12h.nocturne.platform.DeviceObservation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** P5: the widget names the BedJet's mode in words, from resources, never as the raw enum. */
@RunWith(RobolectricTestRunner::class) @Config(sdk = [34])
class WidgetModeNameTest : io.github.willzy12h.nocturne.platform.PlatformGraphFixture() {
    private val context: Context = RuntimeEnvironment.getApplication()

    private fun caption(mode: String): String = runBlocking {
        val graph = context.graph
        graph.clock = Clock.fixed(Instant.parse("2026-10-22T22:30:00Z"), ZoneOffset.UTC)
        graph.repository.updateSettings { it.copy(demoMode = true) }
        widgetMode(context, DeviceObservation("demo", "BEDJET-DEMO", mode, true, "2026-10-22T22:25:00Z"))
    }

    @Test fun gentleHeatIsNamedInWords() {
        val text = caption("EXTENDED_HEAT")
        assertTrue(text, text.startsWith("Last known Gentle heat"))
        assertFalse(text, text.contains("EXTENDED_HEAT"))
    }

    @Test fun standbyIsOff() = assertTrue(caption("STANDBY").startsWith("Last known Off"))

    @Test fun anUnknownStoredModeIsUnknownNotRaw() {
        val text = caption("SOMETHING_NEW")
        assertFalse(text, text.contains("SOMETHING_NEW"))
        assertEquals(context.getString(io.github.willzy12h.nocturne.R.string.feature_unknown), text)
    }
}
