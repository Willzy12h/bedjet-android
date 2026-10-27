package io.github.willzy12h.nocturne.ui

import io.github.willzy12h.nocturne.App
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.link.BedJetLink
import io.github.willzy12h.nocturne.core.link.DemoBedJet
import io.github.willzy12h.nocturne.core.link.SourcedStatus
import io.github.willzy12h.nocturne.core.plan.Phase
import io.github.willzy12h.nocturne.core.protocol.DeviceStatus
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.night.CommandOrigin
import io.github.willzy12h.nocturne.night.NightActions.Command
import io.github.willzy12h.nocturne.night.OutcomeKind
import io.github.willzy12h.nocturne.platform.PlatformGraphFixture
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/** C6: the receipt's timer counts down while the write/notification is in flight. No real-time sleep is needed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RemoteDurationReceiptTest : PlatformGraphFixture() {
    private val app = RuntimeEnvironment.getApplication() as App
    private lateinit var graph: Graph
    private var elapsed = 0L
    private var receiptDelaySeconds = 0
    private var runtimeWrites = 0
    private val reports = MutableStateFlow<SourcedStatus?>(null)
    private val statuses = MutableStateFlow<DeviceStatus?>(null)

    @Before fun setup() = runBlocking {
        val demo = DemoBedJet(now = { graph.now() })
        fun receive(report: SourcedStatus?) {
            reports.value = report
            statuses.value = report?.status
        }
        val transport = object : BedJetLink by demo {
            override val sourcedStatus = reports
            override val status = statuses
            override fun latestSourcedStatus(): SourcedStatus? = reports.value
            override suspend fun connect() { demo.connect(); receive(demo.latestSourcedStatus()) }
            override suspend fun prepareForStart() { demo.prepareForStart(); receive(demo.latestSourcedStatus()) }
            override suspend fun send(command: ByteArray) {
                demo.send(command)
                if (command.firstOrNull()?.toInt() == 0x02) {
                    runtimeWrites++
                    if (receiptDelaySeconds > 0) {
                        elapsed += receiptDelaySeconds * 1_000L
                        graph.clock = Clock.offset(graph.clock, Duration.ofSeconds(receiptDelaySeconds.toLong()))
                        demo.tick()
                    }
                }
                receive(demo.latestSourcedStatus())
            }
            override suspend fun sendGuarded(command: ByteArray, beforeWrite: () -> Unit) { beforeWrite(); send(command) }
        }
        graph = fresh { Graph(app, transport) }
        ReflectionHelpers.setField(app, "graph", graph)
        graph.clock = Clock.fixed(Instant.parse("2026-10-26T22:30:00Z"), ZoneOffset.UTC)
        graph.actions.elapsedMs = { elapsed }
        graph.actions.confirmMs = 20
        graph.actions.freshStatusMs = 20
        graph.repository.updateSettings { it.copy(demoMode = true) }
        assertEquals(OutcomeKind.CONFIRMED, graph.actions.execute(graph.actions.capture(Command.WARM)).kind)
    }

    private suspend fun setDuration(tracked: Boolean) {
        if (tracked) {
            val profile = graph.repository.profiles.value.first()
            graph.repository.saveProfile(profile.copy(start = listOf(Phase(Mode.HEAT, 64, 30, 30))))
            assertEquals(OutcomeKind.CONFIRMED, graph.actions.execute(graph.actions.capture(Command.START_NIGHT)).kind)
            assertNotNull(graph.repository.activeNight.value)
        }
        val before = graph.link.latestSourcedStatus()!!
        val writesBefore = runtimeWrites
        val requestedMinutes = if (tracked) 20 else 195
        assertTrue("The fixture's live programme limit must permit this requested duration",
            before.status.maxRuntimeMinutes >= requestedMinutes)
        val request = captureDuration(graph, before.status, requestedMinutes).request.copy(origin = CommandOrigin.APP)
        receiptDelaySeconds = 6
        val outcome = graph.actions.remoteDuration(app.getString(R.string.remote_timer_set), request)
        val receipt = graph.link.latestSourcedStatus()!!
        assertEquals(writesBefore + 1, runtimeWrites)
        assertTrue("Confirmation must use a new receipt, not the pre-request report", receipt.receiptOrder > before.receiptOrder)
        assertEquals(requestedMinutes * 60 - 6, receipt.status.remainingSeconds)
        assertEquals("A matching countdown receipt remains valid after six seconds of delivery latency", OutcomeKind.CONFIRMED, outcome.kind)
        assertEquals(CommandOrigin.APP, outcome.origin)
        if (tracked) {
            assertEquals("The receipt proves that the manual run replaced the programme", 0, receipt.status.extra?.programStep)
            assertNull("Only this matching receipt may finalise End tonight", graph.repository.activeNight.value)
        }
    }

    @Test fun aNewCountdownReceiptAfterSixSecondsConfirmsTheCustomDuration() = runBlocking { setDuration(tracked = false) }

    @Test fun endingATrackedNightAcceptsTheElapsedCountdownReceipt() = runBlocking { setDuration(tracked = true) }
}
