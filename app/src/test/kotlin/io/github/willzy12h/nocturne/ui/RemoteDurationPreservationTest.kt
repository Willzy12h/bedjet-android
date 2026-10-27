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
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList

/** C6: changing a timer must not silently clamp a reviewed live setpoint while leaving a programme. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RemoteDurationPreservationTest : PlatformGraphFixture() {
    private val app = RuntimeEnvironment.getApplication() as App
    private lateinit var graph: Graph
    private val writes = CopyOnWriteArrayList<ByteArray>()
    private val reports = MutableStateFlow<SourcedStatus?>(null)
    private val statuses = MutableStateFlow<DeviceStatus?>(null)

    @Before fun setup() = runBlocking {
        val demo = DemoBedJet(now = { graph.now() })
        fun receive(report: SourcedStatus?) {
            // A physical remote can change a reported value beyond Nocturne's safe replay range.
            val shown = report?.let { if (it.status.mode == Mode.HEAT)
                it.copy(status = it.status.copy(maxTemperature = 86)) else it }
            reports.value = shown
            statuses.value = shown?.status
        }
        val transport = object : BedJetLink by demo {
            override val sourcedStatus = reports
            override val status = statuses
            override fun latestSourcedStatus(): SourcedStatus? = reports.value
            override suspend fun connect() { demo.connect(); receive(demo.latestSourcedStatus()) }
            override suspend fun prepareForStart() { demo.prepareForStart(); receive(demo.latestSourcedStatus()) }
            override suspend fun send(command: ByteArray) {
                writes += command.copyOf()
                demo.send(command)
                receive(demo.latestSourcedStatus())
            }
            override suspend fun sendGuarded(command: ByteArray, beforeWrite: () -> Unit) { beforeWrite(); send(command) }
        }
        graph = fresh { Graph(app, transport) }
        ReflectionHelpers.setField(app, "graph", graph)
        graph.clock = Clock.fixed(Instant.parse("2026-10-26T22:30:00Z"), ZoneOffset.UTC)
        graph.actions.confirmMs = 20
        graph.actions.freshStatusMs = 20
        graph.repository.updateSettings { it.copy(demoMode = true) }
        val profile = graph.repository.profiles.value.first()
        graph.repository.saveProfile(profile.copy(start = listOf(Phase(Mode.HEAT, 64, 30, 30))))
        assertEquals(OutcomeKind.CONFIRMED, graph.actions.execute(graph.actions.capture(Command.START_NIGHT)).kind)
    }

    private suspend fun refuseClamping(observedOnly: Boolean) {
        if (observedOnly) {
            assertEquals(OutcomeKind.LOCAL, graph.actions.execute(graph.actions.capture(Command.STOP_TRACKING)).kind)
            assertNull(graph.repository.activeNight.value)
        }
        // This is the existing live Remote temperature route: it sends the value chosen within reported limits.
        val temperature = graph.actions.remote(app.getString(R.string.remote_temperature_set), affectsProgramme = true,
            confirms = { it.targetTemperature == 82 }) { it.setTemperature(82) }
        assertEquals(OutcomeKind.CONFIRMED, temperature.kind)
        // Fixture-only physical-remote input must survive the owner's reconnect/status refresh.
        graph.link.send(io.github.willzy12h.nocturne.core.protocol.Codec.setTemperature(82))
        val before = graph.link.latestSourcedStatus()!!
        assertEquals(82, before.status.targetTemperature)
        assertEquals(86, before.status.maxTemperature)
        assertTrue(before.status.extra!!.programStep > 0)
        if (!observedOnly) assertTrue(graph.repository.activeNight.value!!.trackingManual)
        val night = graph.repository.activeNight.value
        val runtime = graph.repository.runtime.value
        val request = captureDuration(graph, before.status, 20).request.copy(origin = CommandOrigin.APP)
        writes.clear()

        val outcome = graph.actions.remoteDuration(app.getString(R.string.remote_timer_set), request)

        assertEquals("The reviewed setpoint cannot be preserved by runManual; refuse before any hardware write", 0, writes.size)
        assertEquals(OutcomeKind.REFUSED, outcome.kind)
        assertEquals(app.getString(R.string.remote_custom_temperature), outcome.message)
        assertEquals(CommandOrigin.APP, outcome.origin)
        assertEquals("Refusal leaves the tracked night untouched", night, graph.repository.activeNight.value)
        assertEquals("Refusal cannot advance generation or begin an ending intention", runtime, graph.repository.runtime.value)
        assertEquals(82, graph.link.latestSourcedStatus()!!.status.targetTemperature)
    }

    @Test fun endingATrackedNightRefusesAClampedLiveTemperatureWithoutWriting() = runBlocking { refuseClamping(observedOnly = false) }

    @Test fun anObservedProgrammeAlsoRefusesAClampedLiveTemperatureWithoutWriting() = runBlocking { refuseClamping(observedOnly = true) }
}
