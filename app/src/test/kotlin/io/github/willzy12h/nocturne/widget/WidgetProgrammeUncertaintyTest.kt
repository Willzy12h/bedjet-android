package io.github.willzy12h.nocturne.widget

import android.content.Context
import android.os.Looper
import android.service.quicksettings.Tile
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.preferencesOf
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.compose
import io.github.willzy12h.nocturne.GraphLifecycle
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.link.DeviceId
import io.github.willzy12h.nocturne.core.night.ActiveNight
import io.github.willzy12h.nocturne.core.night.Confidence
import io.github.willzy12h.nocturne.core.store.BackupCodec
import io.github.willzy12h.nocturne.night.NightStatus
import io.github.willzy12h.nocturne.night.OutcomeKind
import io.github.willzy12h.nocturne.night.ProgrammeOwnerFixture
import io.github.willzy12h.nocturne.platform.NightTileService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/** A known physical Off remains relevant when storage cannot replace the old CONFIRMED file. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetProgrammeUncertaintyTest {
    @get:Rule val graphs = GraphLifecycle()

    private suspend fun awaitConfidence(f: ProgrammeOwnerFixture, confidence: Confidence) {
        withTimeout(4_000) {
            while (f.graph.actions.status.value.confidence != confidence) {
                shadowOf(Looper.getMainLooper()).idle()
                delay(10)
            }
        }
    }

    private suspend fun unavailableUnknownSave(): ProgrammeOwnerFixture {
        val f = ProgrammeOwnerFixture(graphs)
        f.initialise()
        var observedOff = false
        val failedWrites = AtomicInteger()
        f.beforeFileWrite = { file, bytes ->
            if (file.name == "active-night.json") {
                val confidence = BackupCodec.json.decodeFromString(ActiveNight.serializer(), bytes.toString(Charsets.UTF_8)).confidence
                if (confidence == Confidence.CONFIRMED && !observedOff) {
                    observedOff = true
                    // The real owner still holds the command; its handoff must retain this exact receipt.
                    runBlocking { f.remoteOff() }
                } else if (confidence == Confidence.UNKNOWN) {
                    failedWrites.incrementAndGet()
                    throw IOException("The local unknown-state write is unavailable")
                }
            }
        }
        try {
            assertEquals(OutcomeKind.UNKNOWN, f.start().kind)
            awaitConfidence(f, Confidence.UNKNOWN)
            assertTrue("The contradiction came from an actual device report", observedOff)
            assertTrue("The UNKNOWN persistence attempt really failed", failedWrites.get() > 0)
            assertEquals("The widget cannot rely on the old in-memory repository value", Confidence.CONFIRMED,
                f.graph.repository.activeNight.value!!.confidence)
            assertEquals("The widget cannot rely on the old durable file", Confidence.CONFIRMED, f.diskNight().confidence)
            return f
        } catch (failure: Throwable) {
            f.beforeFileWrite = null
            throw failure
        }
    }

    private suspend fun renderedLabels(f: ProgrammeOwnerFixture, widget: GlanceAppWidget): List<String> {
        val remote = widget.compose(f.app, size = DpSize(340.dp, 240.dp), state = preferencesOf())
        val root = remote.apply(f.app, FrameLayout(f.app))
        fun descendants(view: View): List<View> = listOf(view) +
            if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
        return descendants(root).filterIsInstance<TextView>().map { it.text.toString() }
    }

    private fun tileService(f: ProgrammeOwnerFixture): NightTileService = NightTileService().also { service ->
        // ShadowTileService is not a ShadowService, so Robolectric's generic ServiceController cannot attach it.
        // This synchronous listener owns no service resources; GraphLifecycle closes the application's jobs.
        ReflectionHelpers.callInstanceMethod<Void>(service, "attachBaseContext",
            ReflectionHelpers.ClassParameter.from(Context::class.java, f.app))
        service.onCreate()
    }

    private suspend fun assertUnconfirmedWidget(widget: GlanceAppWidget) {
        val f = unavailableUnknownSave()
        try {
            val commands = f.sent.size
            val labels = renderedLabels(f, widget)
            assertTrue("${widget.javaClass.simpleName} must show the known uncertainty: $labels",
                f.app.getString(R.string.widget_phase_unknown) in labels)
            // Controls shows the plan with its short wake; a one-cell tile has room only for the phase.
            if (widget is QuickControlsWidget) assertTrue("${widget.javaClass.simpleName} must not retain the old confirmed wake: $labels",
                f.app.getString(R.string.widget_wake_unknown_short) in labels || f.app.getString(R.string.widget_wake_unknown) in labels)
            assertEquals("Rendering must not send device commands", commands, f.sent.size)
        } finally { f.beforeFileWrite = null }
    }

    @Test fun quickControlsShowUnconfirmedPhaseAndWakeWhenTheUnknownSaveFails() = runBlocking {
        assertUnconfirmedWidget(QuickControlsWidget())
    }

    @Test fun singleActionShowsUnconfirmedPhaseAndWakeWhenTheUnknownSaveFails() = runBlocking {
        assertUnconfirmedWidget(SingleActionWidget())
    }

    @Test fun quickSettingsTileDoesNotCallTheOldFileRunningAfterAKnownOff() = runBlocking {
        val f = unavailableUnknownSave()
        val service = tileService(f)
        try {
            val commands = f.sent.size
            service.onStartListening()
            val tile = requireNotNull(service.qsTile)
            assertEquals("The selected BedJet is known to have contradicted the tracked programme",
                f.app.getString(R.string.feature_unknown), tile.subtitle.toString())
            assertEquals("A failed local save cannot keep the tile active", Tile.STATE_INACTIVE, tile.state)
            assertEquals("Listening remains navigation-only", commands, f.sent.size)
        } finally { f.beforeFileWrite = null }
    }

    @Test fun ownerStatusAloneTriggersABackgroundWidgetRedraw() = runBlocking {
        val f = ProgrammeOwnerFixture(graphs)
        f.initialise()
        assertEquals(OutcomeKind.CONFIRMED, f.start().kind)
        awaitConfidence(f, Confidence.CONFIRMED)
        // Control only the public owner publication to isolate this trigger from repository/link changes.
        val ownerStatus = MutableStateFlow(f.graph.actions.status.value)
        ReflectionHelpers.setField(f.graph.actions, "status", ownerStatus)
        val rendered = Channel<NightStatus>(Channel.UNLIMITED)
        val observer = launch(Dispatchers.Default) {
            WidgetUpdater.observeUpdates(f.app) { rendered.send(ownerStatus.value) }
        }
        try {
            assertEquals(Confidence.CONFIRMED, withTimeout(5_000) { rendered.receive() }.confidence)
            val night = f.graph.repository.activeNight.value
            val runtime = f.graph.repository.runtime.value
            val automation = f.graph.repository.automation.value
            val commands = f.sent.size
            ownerStatus.value = NightStatus(Confidence.UNKNOWN, f.app.getString(R.string.runtime_takeover))
            val update = withTimeoutOrNull(3_000) { rendered.receive() }
            assertEquals("An owner-only UNKNOWN publication must redraw without waiting for another stored change",
                Confidence.UNKNOWN, update?.confidence)
            assertEquals(night, f.graph.repository.activeNight.value)
            assertEquals(runtime, f.graph.repository.runtime.value)
            assertEquals(automation, f.graph.repository.automation.value)
            assertEquals(commands, f.sent.size)
        } finally { observer.cancelAndJoin() }
    }

    @Test fun retainedUncertaintyDoesNotColourAnotherIdentityOrTargetOrHidePending() = runBlocking {
        val f = unavailableUnknownSave()
        try {
            val current = f.graph.repository.activeNight.value!!
            for (unrelated in listOf(current.copy(revision = current.revision + 1),
                current.copy(deviceId = DeviceId.ble("AA:BB:CC:DD:EE:02")))) {
                assertNotEquals("The retained receipt belongs to its exact night and target",
                    f.app.getString(R.string.widget_wake_unknown), widgetWake(f.app, unrelated))
                assertNotEquals(f.app.getString(R.string.widget_phase_unknown), widgetPhase(f.app, unrelated))
            }
            assertEquals("An in-flight replacement keeps its pending label", f.app.getString(R.string.feature_pending_wake),
                widgetWake(f.app, current.copy(confidence = Confidence.PENDING)))
        } finally { f.beforeFileWrite = null }
    }

    @Test fun staleConfirmedOwnerStatusCannotUpgradeAPersistedUnknownNight() = runBlocking {
        val f = ProgrammeOwnerFixture(graphs)
        f.initialise()
        assertEquals(OutcomeKind.CONFIRMED, f.start().kind)
        awaitConfidence(f, Confidence.CONFIRMED)
        val stale = MutableStateFlow(f.graph.actions.status.value)
        ReflectionHelpers.setField(f.graph.actions, "status", stale)
        val unknown = f.graph.repository.activeNight.value!!.copy(confidence = Confidence.UNKNOWN)
        f.graph.repository.setActiveNight(unknown)
        assertEquals(f.app.getString(R.string.widget_wake_unknown), widgetWake(f.app, unknown))
        assertEquals(f.app.getString(R.string.widget_phase_unknown), widgetPhase(f.app, unknown))
        val service = tileService(f)
        service.onStartListening()
        val tile = requireNotNull(service.qsTile)
        assertEquals(f.app.getString(R.string.feature_unknown), tile.subtitle.toString())
        assertEquals(Tile.STATE_INACTIVE, tile.state)
    }
}
