package io.github.willzy12h.nocturne.widget
import android.content.Context
import io.github.willzy12h.nocturne.graph
import io.github.willzy12h.nocturne.core.night.*
import io.github.willzy12h.nocturne.platform.DeviceObservation
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.*
@RunWith(RobolectricTestRunner::class) @Config(sdk=[34])
class WidgetFreshnessTest : io.github.willzy12h.nocturne.platform.PlatformGraphFixture() {
    @Test fun pastWakeIsNotCalledNextAndRenderingNeverConnects() {
        val context:Context=RuntimeEnvironment.getApplication();val graph=context.graph
        graph.clock=Clock.fixed(Instant.parse("2026-10-23T07:01:00Z"),ZoneOffset.UTC)
        val n=ActiveNight("n","p","2026-10-22T22:30Z","2026-10-23T07:00Z",false,2,"2026-10-23T06:50Z","2026-10-23T07:30Z",confidence=Confidence.CONFIRMED)
        val before=graph.link.state.value
        assertEquals("No confirmed next wake",widgetWake(context,n))
        assertEquals("Next wake pending",widgetWake(context,n.copy(confidence=Confidence.PENDING)))
        assertEquals(before,graph.link.state.value)
        assertNull(graph.link.status.value)
    }
}
