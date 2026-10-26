package io.github.willzy12h.nocturne.ui

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import io.github.willzy12h.nocturne.GraphLifecycle
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import java.time.Duration

/** A fully attached host for standalone Compose content, with both Android and Compose frames settled. */
abstract class ComposeActivityFixture {
    protected val compose = createEmptyComposeRule()
    @get:Rule val rules: TestRule = RuleChain.outerRule(GraphLifecycle()).around(compose)
    private var controller: ActivityController<ComponentActivity>? = null

    @Before fun openComposeActivity() {
        controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().windowFocusChanged(true)
    }

    @After fun closeComposeActivity() {
        controller?.pause()?.stop()?.destroy()
        controller = null
    }

    protected fun setContent(content: @Composable () -> Unit) {
        compose.runOnUiThread {
            controller!!.get().setContent(content = content)
            controller!!.visible().windowFocusChanged(true)
        }
        // Native callbacks and Compose frames use separate queues in Robolectric. Deliver global snapshot
        // notifications as the normal Android snapshot manager does, before waiting for composition to idle.
        repeat(3) {
            Snapshot.sendApplyNotifications()
            compose.mainClock.advanceTimeByFrame()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
        }
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
    }
}
