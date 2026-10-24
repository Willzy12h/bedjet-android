package io.github.willzy12h.nocturne.notify

import android.app.Notification
import android.app.NotificationManager
import io.github.willzy12h.nocturne.App
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.GraphLifecycle
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.link.DemoBedJet
import io.github.willzy12h.nocturne.core.night.ActiveNight
import io.github.willzy12h.nocturne.core.night.Confidence
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.store.Settings
import io.github.willzy12h.nocturne.night.OutcomeKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** A2: an old or unconfirmed timestamp must not become a promised manual end in a notification. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ManualWakeTitleTest {
    @get:Rule val graphs = GraphLifecycle()
    private val app = RuntimeEnvironment.getApplication() as App
    private lateinit var graph: Graph
    private lateinit var demo: DemoBedJet
    private val night = ActiveNight("manual-title-night", "profile", "2026-10-22T22:30Z", "2026-10-23T07:00Z",
        false, 2, "2026-10-23T06:30Z", "2026-10-23T07:30Z", deviceId = "demo", confidence = Confidence.CONFIRMED)

    @Before fun setup() = runBlocking {
        demo = DemoBedJet(now = { graph.now() })
        graph = graphs.fresh { Graph(app, demo) }
        graph.clock = Clock.fixed(Instant.parse("2026-10-23T06:30:00Z"), ZoneOffset.UTC)
        graph.actions.confirmMs = 1
        graph.actions.freshStatusMs = 100
        ReflectionHelpers.setField(app, "graph", graph)
        graph.repository.updateSettings { it.copy(demoMode = true) }
    }

    private fun notification(): Notification {
        val posted = shadowOf(app.getSystemService(NotificationManager::class.java)).getNotification(2)
        assertNotNull("The wake/manual notification must remain available", posted)
        return posted!!
    }

    private fun title() = notification().extras.getString(Notification.EXTRA_TITLE)

    @Test fun anUnknownManualRunDoesNotClaimItsUnconfirmedEnd() {
        val unknown = night.copy(trackingManual = true, confidence = Confidence.UNKNOWN, endsAt = "2026-10-23T06:45Z")
        val status = app.getString(R.string.runtime_sent_unconfirmed, app.getString(R.string.runtime_snooze_warm))
        Notifications.showWakeUp(app, unknown, Settings(), status, logNotice = false)
        assertEquals("Manual run not confirmed", title())
        assertFalse(title()!!.contains("06:45"))
        assertEquals("The existing command status stays in the body", status,
            notification().extras.getString(Notification.EXTRA_TEXT))
    }

    @Test fun aRemoteAdjustCannotReuseThePreviousManualEndInANewNotification() = runBlocking {
        demo.connect()
        graph.controller().runManual(Mode.HEAT, 64, 5, 15)
        val kept = night.copy(trackingManual = true, endsAt = "2026-10-23T06:45Z")
        graph.repository.setActiveNight(kept)
        val result = graph.actions.remote("Temperature set", affectsProgramme = true, expected = kept.identity,
            confirms = { it.targetTemperature == 70 }) { it.setTemperature(70) }
        assertEquals(OutcomeKind.CONFIRMED, result.kind)
        val adjusted = graph.repository.activeNight.value!!
        assertEquals(Confidence.UNKNOWN, adjusted.confidence)
        assertTrue(adjusted.trackingManual)
        assertEquals("A temperature change did not establish a new end", kept.endsAt, adjusted.endsAt)
        Notifications.showWakeUp(app, adjusted, graph.repository.settings.value, result.message, logNotice = false)
        assertEquals("Manual run not confirmed", title())
        assertFalse("The retained end is not this adjustment's confirmed plan", title()!!.contains("06:45"))
    }

    @Test fun aConfirmedManualRunOffersItsCapturedEnd() {
        val kept = night.copy(trackingManual = true, endsAt = "2026-10-23T06:45Z")
        Notifications.showWakeUp(app, kept, Settings(), logNotice = false)
        assertEquals(app.getString(R.string.feature_manual_until, "06:45"), title())
    }

    @Test fun anOrdinaryNightKeepsItsWakeTitle() {
        Notifications.showWakeUp(app, night, Settings(), logNotice = false)
        assertEquals("Waking you at 07:00", title())
        assertEquals("Your BedJet is getting you ready to wake up.",
            notification().extras.getString(Notification.EXTRA_TEXT))
    }
}
