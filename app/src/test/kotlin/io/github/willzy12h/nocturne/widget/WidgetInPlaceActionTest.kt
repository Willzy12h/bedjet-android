package io.github.willzy12h.nocturne.widget

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.appwidget.compose
import io.github.willzy12h.nocturne.core.night.ActiveNight
import io.github.willzy12h.nocturne.core.night.Confidence
import io.github.willzy12h.nocturne.graph
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** Exercise the actual RemoteViews click target before checking the command delivery separately. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WidgetInPlaceActionTest : io.github.willzy12h.nocturne.platform.PlatformGraphFixture() {
    private val context: Context = RuntimeEnvironment.getApplication()
    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    private suspend fun click(page: String, label: String) {
        context.graph.clock = Clock.fixed(Instant.parse("2026-10-26T22:30:00Z"), ZoneOffset.UTC)
        context.graph.repository.updateSettings { it.copy(demoMode = true) }
        val remote = QuickControlsWidget().compose(context, size = DpSize(280.dp, 180.dp),
            state = preferencesOf(stringPreferencesKey("widget_page") to page))
        val view = remote.apply(context, FrameLayout(context))
        val button = descendants(view).filterIsInstance<Button>().firstOrNull { it.text.toString() == label }
        assertNotNull("$page must expose a real $label control", button)
        button!!.performClick()
    }

    @Test fun anOrdinaryControlSendsACallbackWithoutOpeningAnyActivity() = runBlocking {
        click("quick:0", "Cool")
        assertNull("A confirmation-free widget action must stay on the launcher", shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
        assertTrue("The real button must dispatch a Glance callback", shadowOf(RuntimeEnvironment.getApplication()).broadcastIntents.any {
            it.component?.className == "androidx.glance.appwidget.action.ActionCallbackBroadcastReceiver"
        })
    }

    @Test fun startWithNoTrackedNightStaysInPlace() = runBlocking {
        click("night:0", "Start tonight")
        assertNull("Start needs no replacement confirmation without a tracked night", shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
    }

    @Test fun offWithNoTrackedNightStaysInPlace() = runBlocking {
        click("device:0", "Off")
        assertNull("Off needs no confirmation without a tracked night", shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
    }

    @Test fun onlyTheExplicitOpenAppAreaTargetsMainActivity() = runBlocking {
        click("app:0", "Open app")
        assertEquals("io.github.willzy12h.nocturne.ui.MainActivity",
            shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity?.component?.className)
    }

    @Test fun theRealChevronButtonsCarryInPlacePageCallbacks() = runBlocking {
        for ((glyph, destination) in listOf("›" to "night:2", "‹" to "app:0")) {
            click("night:0", glyph)
            assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
            val intent = shadowOf(RuntimeEnvironment.getApplication()).broadcastIntents.last {
                it.component?.className == "androidx.glance.appwidget.action.ActionCallbackBroadcastReceiver"
            }
            assertEquals(WidgetPageCallback::class.java.name, intent.getStringExtra("ActionCallbackBroadcastReceiver:callbackClass"))
            assertEquals(destination, intent.getBundleExtra("ActionCallbackBroadcastReceiver:parameters")?.getString("widget_page"))
        }
    }
}
