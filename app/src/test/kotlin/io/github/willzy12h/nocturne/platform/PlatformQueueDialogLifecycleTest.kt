package io.github.willzy12h.nocturne.platform

import android.app.Dialog
import android.os.Looper
import android.widget.TextView
import io.github.willzy12h.nocturne.App
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.GraphLifecycle
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.night.NightActions.Command
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.util.ReflectionHelpers

/** Destruction must also close UI created before a cancellable coroutine is dispatched. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlatformQueueDialogLifecycleTest {
    @get:Rule val graphs = GraphLifecycle()

    @Test fun destroyingBeforeCommandDispatchDismissesWorkingDialog() {
        val app = RuntimeEnvironment.getApplication() as App
        val graph = graphs.fresh { Graph(app) }
        ReflectionHelpers.setField(app, "graph", graph)
        val dispatcher = StandardTestDispatcher()
        var activity: ActivityController<PlatformActionActivity>? = null
        var working: Dialog? = null
        Dispatchers.setMain(dispatcher)
        try {
            activity = Robolectric.buildActivity(PlatformActionActivity::class.java,
                PlatformActionActivity.intent(app, graph.actions.capture(Command.STOP_TRACKING)))
            activity.create()
            working = requireNotNull(ShadowDialog.getLatestDialog())
            assertTrue("The command shows Working before its launch is dispatched", working.isShowing)
            assertEquals(app.getString(R.string.feature_working),
                working.findViewById<TextView>(android.R.id.message).text.toString())

            activity.destroy()
            activity = null
            dispatcher.scheduler.runCurrent()
            shadowOf(Looper.getMainLooper()).idle()

            assertTrue("The cancelled native command releases admission", graph.repository.operationAdmission.isIdle())
            assertFalse("A destroyed Activity must not retain its Working dialog when the launch never started",
                working.isShowing)
        } finally {
            // Always restore the dispatcher, even if setup or a failing lifecycle assertion throws.
            // Dismiss after the assertion so this RED cannot poison later UI tests with its window.
            try {
                activity?.let { if (!it.get().isDestroyed) it.destroy() }
                dispatcher.scheduler.runCurrent()
            } finally {
                try {
                    (working ?: ShadowDialog.getLatestDialog())?.dismiss()
                    shadowOf(Looper.getMainLooper()).idle()
                } finally {
                    Dispatchers.resetMain()
                }
            }
        }
    }
}
