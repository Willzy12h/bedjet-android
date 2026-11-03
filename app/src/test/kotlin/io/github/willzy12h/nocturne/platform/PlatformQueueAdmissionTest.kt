package io.github.willzy12h.nocturne.platform

import io.github.willzy12h.nocturne.App
import io.github.willzy12h.nocturne.GraphLifecycle
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
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlatformQueueAdmissionTest {
    @get:Rule val graphs = GraphLifecycle()

    @Test fun nativeRequestWaitingForUiDispatchAlreadyExcludesImportAndCancellationReleasesIt() {
        val app = RuntimeEnvironment.getApplication() as App
        val graph = app.graph
        val dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        var activity: org.robolectric.android.controller.ActivityController<PlatformActionActivity>? = null
        try {
            activity = Robolectric.buildActivity(PlatformActionActivity::class.java,
                PlatformActionActivity.intent(app, graph.actions.capture(Command.STOP_TRACKING))).create()
            val restore = graph.repository.operationAdmission.tryImport()
            restore?.close()
            assertNull("A submitted native command must exclude restore before its coroutine starts", restore)
        } finally {
            try {
                activity?.destroy()
                dispatcher.scheduler.runCurrent()
            } finally {
                try { org.robolectric.shadows.ShadowDialog.getLatestDialog()?.dismiss() }
                finally { Dispatchers.resetMain() }
            }
        }
        assertTrue("Cancelling the waiting activity releases command admission", graph.repository.operationAdmission.isIdle())
    }
}
