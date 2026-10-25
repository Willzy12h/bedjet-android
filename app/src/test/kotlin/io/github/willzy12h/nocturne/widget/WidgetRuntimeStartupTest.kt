package io.github.willzy12h.nocturne.widget

import io.github.willzy12h.nocturne.App
import io.github.willzy12h.nocturne.platform.PlatformGraphFixture
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicBoolean

/** Guards the real Application -> Graph.startRuntime -> WidgetUpdater.start wiring. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WidgetRuntimeStartupTest : PlatformGraphFixture() {
    // There is no production test hook: inspect the updater's real idempotent-start latch.
    private fun started(): AtomicBoolean = WidgetUpdater::class.java.getDeclaredField("started").let {
        it.isAccessible = true
        it.get(WidgetUpdater) as AtomicBoolean
    }

    @Test fun applicationStartupStartsTheWidgetUpdater() {
        val app = RuntimeEnvironment.getApplication() as App
        assertTrue("App.onCreate must start WidgetUpdater through Graph.startRuntime", started().get())
        graphs.finish()
        started().set(false)
        fresh { app.onCreate(); app.graph }
        assertTrue("A fresh application runtime must start the updater", started().get())
    }

    @Test fun startingRuntimeStartsTheActualUpdaterWithoutCallingItsObserverDirectly() {
        val app = RuntimeEnvironment.getApplication() as App
        started().set(false)
        app.graph.startRuntime()
        assertTrue("Graph.startRuntime must invoke WidgetUpdater.start", started().get())
    }
}
