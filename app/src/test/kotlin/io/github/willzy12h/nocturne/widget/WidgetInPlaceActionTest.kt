package io.github.willzy12h.nocturne.widget

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.preferencesOf
import androidx.glance.appwidget.compose
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

    private suspend fun rendered(presets: String, demo: Boolean = true): View {
        context.graph.clock = Clock.fixed(Instant.parse("2026-10-26T22:30:00Z"), ZoneOffset.UTC)
        context.graph.repository.updateSettings { it.copy(demoMode = demo, targetDevice = null) }
        val remote = QuickControlsWidget().compose(context, size = DpSize(292.dp, 184.dp),
            state = preferencesOf(QuickControlsWidget.KEY_PRESETS to presets))
        return remote.apply(context, FrameLayout(context))
    }

    private suspend fun click(presets: String, label: String) {
        val control = widgetControl(rendered(presets), label)
        assertNotNull("Controls must expose a real $label control", control)
        control!!.performClick()
    }

    private fun lastCallback() = shadowOf(RuntimeEnvironment.getApplication()).broadcastIntents.lastOrNull {
        it.component?.className == "androidx.glance.appwidget.action.ActionCallbackBroadcastReceiver"
    }

    @Test fun anOrdinaryControlSendsACallbackWithoutOpeningAnyActivity() = runBlocking {
        click("COOL", "Cool")
        assertNull("A confirmation-free widget action must stay on the launcher", shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
        val intent = lastCallback()
        assertNotNull("The real control must dispatch a Glance callback", intent)
        assertEquals(WidgetActionCallback::class.java.name, intent!!.getStringExtra("ActionCallbackBroadcastReceiver:callbackClass"))
        assertEquals("COOL", intent.getBundleExtra("ActionCallbackBroadcastReceiver:parameters")?.getString("widget_command"))
    }

    @Test fun heatAndDrySendTheirOwnSavedPresets() = runBlocking {
        for ((key, label, mode) in listOf(Triple("HEAT", "Heat", "HEAT"), Triple("DRY", "Dry", "DRY"))) {
            click(key, label)
            val parameters = lastCallback()!!.getBundleExtra("ActionCallbackBroadcastReceiver:parameters")!!
            assertEquals("WARM", parameters.getString("widget_command"))
            assertTrue("$label carries its own preset: ${parameters.getString("widget_preset")}",
                parameters.getString("widget_preset").orEmpty().contains("\"$mode\""))
        }
    }

    @Test fun startWithNoTrackedNightStaysInPlace() = runBlocking {
        click("START", "Start tonight")
        assertNull("Start needs no replacement confirmation without a tracked night", shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
    }

    @Test fun offIsAlwaysThereAndStaysInPlaceWithNoTrackedNight() = runBlocking {
        click("", "Off")
        assertNull("Off needs no confirmation without a tracked night", shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
    }

    @Test fun onlyTheTemperatureAndStatusAreaOpensTheApp() = runBlocking {
        val root = rendered("TURBO,HEAT,COOL")
        val header = widgetControls(root).single { widgetDescription(it).endsWith("Opens Nocturne.") }
        header.performClick()
        assertEquals("io.github.willzy12h.nocturne.ui.MainActivity",
            shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity?.component?.className)
        for (label in listOf("Turbo", "Heat", "Cool", "Off")) {
            widgetControl(root, label)!!.performClick()
            assertNull("$label must not open the app", shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
        }
    }

    @Test fun withNoBedJetChosenTheControlsSayChooseABedJetAndOpenTheApp() = runBlocking {
        val root = rendered("COOL", demo = false)
        assertNotNull(widgetDescendants(root).filterIsInstance<android.widget.TextView>().firstOrNull { it.text.toString() == "Choose a BedJet" })
        widgetControl(root, "Cool")!!.performClick()
        assertEquals("io.github.willzy12h.nocturne.ui.MainActivity",
            shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity?.component?.className)
    }
}
