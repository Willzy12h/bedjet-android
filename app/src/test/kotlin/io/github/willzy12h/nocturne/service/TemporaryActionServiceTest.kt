package io.github.willzy12h.nocturne.service

import android.content.Intent
import io.github.willzy12h.nocturne.App
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.GraphLifecycle
import io.github.willzy12h.nocturne.core.link.BedJetLink
import io.github.willzy12h.nocturne.core.link.DemoBedJet
import io.github.willzy12h.nocturne.core.night.NightIdentity
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.store.ActionPreset
import io.github.willzy12h.nocturne.core.store.BackupCodec
import io.github.willzy12h.nocturne.night.CommandOrigin
import io.github.willzy12h.nocturne.night.NightActions
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** The Android handoff must retain what was reviewed and reject malformed temporary work before promotion. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TemporaryActionServiceTest {
    @get:Rule val graphs = GraphLifecycle()
    private val app = RuntimeEnvironment.getApplication() as App
    private lateinit var graph: Graph
    private lateinit var delivery: ServiceStartContext
    private var writes = 0
    private val preset = ActionPreset(Mode.HEAT, 64, 35, 20)

    @Before fun setup() = runBlocking {
        val demo = DemoBedJet()
        val link = object : BedJetLink by demo {
            override suspend fun send(command: ByteArray) { writes++; demo.send(command) }
            override suspend fun sendGuarded(command: ByteArray, beforeWrite: () -> Unit) { beforeWrite(); send(command) }
        }
        graph = graphs.fresh { Graph(app, link) }
        ReflectionHelpers.setField(app, "graph", graph)
        graph.repository.updateSettings { it.copy(demoMode = true) }
        delivery = ServiceStartContext(app)
    }

    private fun delivered(): Intent {
        val request = graph.actions.captureTemporary(preset).copy(expected = NightIdentity("captured-night", 7),
            origin = CommandOrigin.WIDGET)
        assertTrue(BedJetService.start(delivery, request))
        return delivery.delivered!!
    }

    @Test fun theTemporaryPresetAndOriginSurviveIntentDelivery() {
        val intent = delivered()
        val encoded = intent.getStringExtra("actionPreset")
        assertNotNull("The service must receive the captured mode, settings and duration", encoded)
        assertEquals(preset, BackupCodec.json.decodeFromString(ActionPreset.serializer(), encoded!!))
        assertEquals("captured-night", intent.getStringExtra("nightId"))
        assertEquals(7, intent.getIntExtra("revision", -1))
        assertEquals(CommandOrigin.WIDGET.name, intent.getStringExtra("commandOrigin"))
    }

    @Test fun theEndNightChoiceAlsoCarriesItsReviewedPreset() {
        val request = graph.actions.capture(NightActions.Command.WARM, requireIdentity = true)
            .copy(replacementConfirmed = true, actionPreset = preset)
        assertTrue(BedJetService.start(delivery, request))
        val encoded = delivery.delivered!!.getStringExtra("actionPreset")
        assertNotNull("End tonight must not substitute a subsequently edited saved preset", encoded)
        assertEquals(preset, BackupCodec.json.decodeFromString(ActionPreset.serializer(), encoded!!))
    }

    @Test fun missingTemporaryPayloadCannotPromoteOrRunAService() {
        assertRejected(delivered().apply { removeExtra("actionPreset") })
    }

    @Test fun malformedTemporaryPayloadCannotPromoteOrRunAService() {
        assertRejected(delivered().putExtra("actionPreset", "{broken"))
    }

    @Test fun outOfRangeTemporaryDurationCannotPromoteOrRunAService() {
        assertRejected(delivered().putExtra("actionPreset",
            BackupCodec.json.encodeToString(ActionPreset.serializer(), preset.copy(minutes = 121))))
    }

    private fun assertRejected(intent: Intent) {
        val controller = Robolectric.buildService(BedJetService::class.java).create()
        try {
            val service = controller.get()
            service.onStartCommand(intent, 0, 1)
            assertNull("Invalid captured input is refused before foreground ownership", shadowOf(service).lastForegroundNotification)
            assertTrue(shadowOf(service).isStoppedBySelf)
            assertEquals(0, writes)
        } finally { controller.destroy() }
    }
}
