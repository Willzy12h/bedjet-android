package io.github.willzy12h.nocturne.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import androidx.glance.appwidget.compose
import androidx.datastore.preferences.core.Preferences
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import io.github.willzy12h.nocturne.graph
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WidgetPagingTest : io.github.willzy12h.nocturne.platform.PlatformGraphFixture() {
    @Test fun everySizeKeepsEveryCommandReachableAndPersistsPageChangesWithoutAnActivityOrConnection() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val info = AppWidgetProviderInfo().apply { provider = ComponentName(app, QuickControlsReceiver::class.java) }
        shadowOf(AppWidgetManager.getInstance(app)).addBoundWidget(75, info)
        val id = GlanceAppWidgetManager(app).getGlanceIdBy(75)
        val before = app.graph.link.state.value
        for (capacity in 1..3) {
            val pages = widgetPages(app, capacity)
            assertEquals(widgetActions(app).map { it.command to it.profileId }.toSet(),
                pages.flatMap { it.actions }.map { it.command to it.profileId }.toSet())
            assertTrue(pages.all { it.actions.size <= capacity })
            for (page in pages) {
                WidgetPageCallback().onAction(app, id, actionParametersOf(WidgetPageCallback.PAGE to page.key))
                val prefs: Preferences = getAppWidgetState(app, PreferencesGlanceStateDefinition, id)
                assertEquals(page.key, prefs[QuickControlsWidget.KEY_PAGE])
                assertEquals(page.key, pages[selectedWidgetPage(pages, prefs[QuickControlsWidget.KEY_PAGE])].key)
                fun buttons(view: View): List<String> = (if (view is Button) listOf(view.text.toString()) else emptyList()) +
                    if (view is ViewGroup) (0 until view.childCount).flatMap { buttons(view.getChildAt(it)) } else emptyList()
                val size = WidgetLayout.entries.first { it.capacity == capacity }.size
                val rendered = QuickControlsWidget().compose(app, id = id, size = size, state = prefs).apply(app, FrameLayout(app))
                val controls = buttons(rendered).filterNot { it in listOf("‹", "›") }
                assertEquals("The persisted page really changes RemoteViews controls at $size",
                    if (page.opensApp) listOf("Open app") else page.actions.map { it.label }, controls)
            }
        }
        assertEquals(before, app.graph.link.state.value)
        assertNull(app.graph.link.status.value)
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test fun resizingPreservesTheCurrentGroupAndTheNearestAction() {
        val app = RuntimeEnvironment.getApplication()
        val pages = widgetPages(app, 3)
        assertEquals("night:0", pages[selectedWidgetPage(pages, "night:2")].key)
        assertEquals("quick:0", pages[selectedWidgetPage(pages, "quick:1")].key)
    }
}
