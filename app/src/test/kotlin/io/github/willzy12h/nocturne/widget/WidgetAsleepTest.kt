package io.github.willzy12h.nocturne.widget

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.preferencesOf
import androidx.glance.appwidget.compose
import io.github.willzy12h.nocturne.graph
import io.github.willzy12h.nocturne.night.WidgetRead
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** The asleep widget: grey buttons only reconnect, Off still works, and what wakes it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WidgetAsleepTest : io.github.willzy12h.nocturne.platform.PlatformGraphFixture() {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val readAt = Instant.parse("2026-10-26T01:42:00Z").toEpochMilli()

    @Before fun chooseARealBedJet() {
        context.graph.clock = Clock.fixed(Instant.parse("2026-10-26T07:30:00Z"), ZoneOffset.UTC)
        runBlocking { context.graph.repository.updateSettings { it.copy(demoMode = false, targetDevice = "ble:AA:BB:CC:DD:EE:FF") } }
        context.getSharedPreferences("widget_refresh", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private suspend fun rendered(presets: String, width: Int = 292, height: Int = 184): View =
        QuickControlsWidget().compose(context, size = DpSize(width.dp, height.dp),
            state = preferencesOf(QuickControlsWidget.KEY_PRESETS to presets)).apply(context, FrameLayout(context))

    private fun texts(root: View) = widgetDescendants(root).filterIsInstance<TextView>().map { it.text.toString() }

    private fun lastCallback() = shadowOf(RuntimeEnvironment.getApplication()).broadcastIntents.lastOrNull {
        it.component?.className == "androidx.glance.appwidget.action.ActionCallbackBroadcastReceiver"
    }?.getStringExtra("ActionCallbackBroadcastReceiver:callbackClass")

    @Test fun neverConnectedShowsTheAsleepLookAndAGreyTapOnlyReconnects() = runBlocking {
        val root = rendered("COOL,HEAT")
        assertTrue(texts(root).toString(), "Not connected" in texts(root))
        widgetControl(root, "Cool")!!.performClick()
        assertEquals(WidgetReconnectCallback::class.java.name, lastCallback())
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
        assertTrue(widgetDescription(widgetControl(root, "Cool")!!).contains("not connected"))
    }

    @Test fun offKeepsItsOwnPathWhileAsleep() = runBlocking {
        val root = rendered("COOL")
        widgetControl(root, "Off")!!.performClick()
        assertEquals(WidgetActionCallback::class.java.name, lastCallback())
        assertTrue(widgetDescription(widgetControl(root, "Off")!!).contains("reconnects, then turns the BedJet off"))
    }

    @Test fun theHeaderSaysWhenItLastReadAndReconnects() = runBlocking {
        WidgetLink.markRead(context, readAt)
        WidgetLink.recordMiss(context, readAt + 60_000)
        val root = rendered("COOL")
        assertTrue(texts(root).toString(), "Last read 01:42" in texts(root))
        val header = widgetControls(root).first { widgetDescription(it).startsWith("Not connected") }
        header.performClick()
        assertEquals(WidgetReconnectCallback::class.java.name, lastCallback())
    }

    @Test fun anAwakeWidgetSendsAsBefore() = runBlocking {
        WidgetLink.markRead(context, readAt)
        val root = rendered("COOL")
        assertFalse("Not connected" in texts(root))
        widgetControl(root, "Cool")!!.performClick()
        assertEquals(WidgetActionCallback::class.java.name, lastCallback())
    }

    @Test fun fourByTwoHasAReconnectButtonAndTwoByOneSaysAsleep() = runBlocking {
        val wide = rendered("TURBO,HEAT,COOL,BOOST,DRY", width = 360, height = 192)
        assertTrue(texts(wide).toString(), "Reconnect" in texts(wide))
        val narrow = rendered("COOL", width = 176, height = 92)
        assertTrue(texts(narrow).toString(), "Asleep" in texts(narrow))
    }

    @Test fun reconnectOutcomes() = runBlocking {
        WidgetLink.beginConnecting(context, System.currentTimeMillis())
        assertEquals(WidgetLinkState.CONNECTING, WidgetLink.state(context))
        WidgetReconnect.apply(context, WidgetRead.NOT_REACHED, readAt)
        assertEquals(WidgetLinkState.MISSING, WidgetLink.state(context))
        val missing = rendered("COOL", width = 360, height = 192).let(::texts)
        assertTrue(missing.toString(), "Not in range" in missing && "Checked 01:42" in missing && "Try again" in missing)
        WidgetReconnect.apply(context, WidgetRead.READ, readAt)
        assertEquals(WidgetLinkState.AWAKE, WidgetLink.state(context))
        WidgetLink.beginConnecting(context, System.currentTimeMillis())
        WidgetReconnect.apply(context, WidgetRead.STEPPED_ASIDE, readAt)
        assertEquals(WidgetLinkState.AWAKE, WidgetLink.state(context))
    }

    @Test fun aGreyTapWhileTheAppHasAFreshReadWakesTheWidget() {
        // Nocturne is open and connected, so the widget's own try steps aside; the app's report is the read.
        context.graph.statusReadAt.value = context.graph.now().minusSeconds(30)
        WidgetLink.beginConnecting(context, System.currentTimeMillis())
        WidgetReconnect.apply(context, WidgetRead.STEPPED_ASIDE, readAt)
        assertEquals(WidgetLinkState.AWAKE, WidgetLink.state(context))
    }

    @Test fun anOldReadDoesNotWakeTheWidget() {
        context.graph.statusReadAt.value = context.graph.now().minusMinutes(10)
        WidgetLink.beginConnecting(context, System.currentTimeMillis())
        WidgetReconnect.apply(context, WidgetRead.STEPPED_ASIDE, readAt)
        assertEquals(WidgetLinkState.ASLEEP, WidgetLink.state(context))
    }

    @Test fun clearingAnExpiredTryStillRedrawsTheWidget() {
        // "Connecting…" already ran out by the clock, so the look is the same before and after; the stored
        // change must still redraw, or a running widget keeps showing "Looking…".
        WidgetLink.beginConnecting(context, System.currentTimeMillis() - 2 * WidgetLinkRules.CONNECTING_MS)
        assertEquals(WidgetLinkState.ASLEEP, WidgetLink.state(context))
        val before = WidgetLink.changes.value
        WidgetLink.stopConnecting(context)
        assertTrue("A stored change must bump the redraw signal", WidgetLink.changes.value > before)
    }

    @Test fun backgroundReadsStopAfterFourMissesAndOpeningTheAppWakesThem() {
        WidgetLink.markRead(context, readAt)
        repeat(4) { WidgetRefresh.record(context, WidgetRead.NOT_REACHED, readAt + it * 60_000L) }
        assertTrue(WidgetLink.facts(context).sleeping)
        assertFalse(WidgetRefresh.due(context, readAt + 10 * 3_600_000L))
        WidgetLink.opened(context)
        assertFalse(WidgetLink.facts(context).sleeping)
        assertTrue(WidgetRefresh.due(context, readAt + 10 * 3_600_000L))
    }

    @Test fun aForceStopPutsItToSleepOnceUntilTheAppOpens() {
        WidgetLink.markRead(context, readAt)
        val manager = context.getSystemService(ActivityManager::class.java)
        shadowOf(manager).addApplicationExitInfo(org.robolectric.shadows.ShadowActivityManager.ApplicationExitInfoBuilder.newBuilder()
            .setProcessName(context.packageName).setReason(ApplicationExitInfo.REASON_USER_REQUESTED).setTimestamp(readAt + 1).build())
        WidgetLink.checkForceStop(context)
        assertTrue(WidgetLink.facts(context).sleeping)
        WidgetLink.opened(context)
        WidgetLink.checkForceStop(context)
        assertFalse("The same force stop is handled once", WidgetLink.facts(context).sleeping)
        assertNotNull(manager)
    }
}
