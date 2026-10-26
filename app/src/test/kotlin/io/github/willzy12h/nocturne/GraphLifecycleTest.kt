package io.github.willzy12h.nocturne

import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Only one graph runs over the application's storage at a time (see [GraphLifecycle]). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GraphLifecycleTest {
    @get:Rule val graphs = GraphLifecycle()

    @Test fun aFreshGraphIsBuiltOnlyOnceTheGraphsBeforeItHaveFinished() {
        val app = RuntimeEnvironment.getApplication() as App
        val first = app.graph
        var firstFinished = false
        val second = graphs.fresh { firstFinished = first.scope.coroutineContext[Job]!!.isCompleted; Graph(app) }
        assertTrue("The application's graph was still running when the next one was built", firstFinished)
        assertTrue(second.scope.isActive)
        var secondFinished = false
        graphs.afterFinishing { secondFinished = second.scope.coroutineContext[Job]!!.isCompleted }
        assertTrue(secondFinished)
    }
}
