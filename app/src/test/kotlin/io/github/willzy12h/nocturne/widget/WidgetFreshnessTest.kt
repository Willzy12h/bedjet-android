package io.github.willzy12h.nocturne.widget
import android.content.Context
import io.github.willzy12h.nocturne.graph
import io.github.willzy12h.nocturne.core.night.*
import io.github.willzy12h.nocturne.platform.DeviceObservation
import io.github.willzy12h.nocturne.night.NightActions
import io.github.willzy12h.nocturne.night.OutcomeKind
import io.github.willzy12h.nocturne.platform.PlatformActionActivity
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.*
@RunWith(RobolectricTestRunner::class) @Config(sdk=[34])
class WidgetFreshnessTest : io.github.willzy12h.nocturne.platform.PlatformGraphFixture() {
    @Test fun backgroundNightRevisionRefreshesWidgetWhileOldOffStillResolvesTheTarget() = runBlocking {
        val context: Context = RuntimeEnvironment.getApplication()
        val graph = context.graph
        graph.clock = Clock.fixed(Instant.parse("2026-10-22T22:30:00Z"), ZoneOffset.UTC)
        graph.repository.updateSettings { it.copy(demoMode = true) }
        val night = ActiveNight("n", graph.repository.profiles.value.first().id,
            "2026-10-22T22:30Z", "2026-10-23T07:00Z", false, 2,
            "2026-10-23T06:50Z", "2026-10-23T07:30Z", confidence = Confidence.CONFIRMED)
        graph.repository.setActiveNight(night)
        val rendered = Channel<io.github.willzy12h.nocturne.night.CommandRequest>(Channel.UNLIMITED)
        val observer = launch(Dispatchers.Default) {
            WidgetUpdater.observeUpdates(context) {
                rendered.send(widgetRequest(context, WidgetAction("Off", NightActions.Command.OFF)))
            }
        }
        try {
            val old = withTimeout(5_000) { rendered.receive() }
            assertTrue(old.requireIdentity)
            assertEquals(night.identity, old.expected)

            // The app remains in the background: only the repository publication drives this refresh.
            val revised = night.copy(revision = night.revision + 1)
            graph.repository.setActiveNight(revised)
            val fresh = withTimeout(5_000) { rendered.receive() }
            assertEquals(revised.identity, fresh.expected)
            assertNotEquals(PlatformActionActivity.intent(context, old).data, PlatformActionActivity.intent(context, fresh).data)
            assertEquals(OutcomeKind.CONFIRMED, graph.actions.execute(old).kind)
            assertNull(graph.repository.activeNight.value)
            assertEquals(io.github.willzy12h.nocturne.core.protocol.Mode.STANDBY, graph.link.status.value?.mode)
        } finally {
            observer.cancelAndJoin()
        }
    }

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
