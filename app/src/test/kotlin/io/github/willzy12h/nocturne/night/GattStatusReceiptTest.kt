package io.github.willzy12h.nocturne.night

import android.app.NotificationManager
import android.content.Context
import android.os.Looper
import io.github.willzy12h.nocturne.App
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.GraphLifecycle
import io.github.willzy12h.nocturne.ble.Attr
import io.github.willzy12h.nocturne.ble.FakeRadio
import io.github.willzy12h.nocturne.ble.GattLink
import io.github.willzy12h.nocturne.ble.Status
import io.github.willzy12h.nocturne.core.link.DeviceId
import io.github.willzy12h.nocturne.core.night.ActiveNight
import io.github.willzy12h.nocturne.core.night.AppliedProgram
import io.github.willzy12h.nocturne.core.night.Confidence
import io.github.willzy12h.nocturne.core.plan.Block
import io.github.willzy12h.nocturne.core.plan.Plan
import io.github.willzy12h.nocturne.core.plan.PlannedStep
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.protocol.ProgramStep
import io.github.willzy12h.nocturne.core.store.EventKind
import io.github.willzy12h.nocturne.core.store.NightRecord
import io.github.willzy12h.nocturne.data.Repository
import io.github.willzy12h.nocturne.graph
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/** The status head owns its receipt even when the unit supplies programme/settings data later. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GattStatusReceiptTest {
    @get:Rule val graphs = GraphLifecycle()

    @Test fun repeatedIdenticalGattPacketsWithAFrozenCountdownKeepAConfirmedNightQuiet() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as App
        val target = DeviceId.ble("AA:BB:CC:DD:EE:FF")
        // A full report for the first step, with 7 h 50 m remaining. Every later packet is byte-for-byte equal.
        val frozenPacket = Status.full(programStep = 1).also {
            it[4] = 7; it[5] = 50; it[8] = 50; it[9] = Mode.HEAT.code.toByte(); it[10] = 9
        }
        val radio = FakeRadio().also { it.behaviour = FakeRadio.Behaviour(firstStatus = frozenPacket) }
        val link = GattLink(radio.radio, { target }, GattLink.Timings(operationMs = 5_000), clock = { 0 })
        val graph = graphs.fresh { Graph(app, link) { scope, now -> Repository(app, scope, now = now) } }
        ReflectionHelpers.setField(app, "graph", graph)
        graph.clock = Clock.fixed(Instant.parse("2026-10-21T22:30:00Z"), ZoneOffset.UTC)
        try {
            graph.repository.updateSettings { it.copy(demoMode = false, targetDevice = target) }
            val profile = graph.repository.profiles.value.first()
            val started = graph.now().minusMinutes(10)
            val wake = started.plusHours(7)
            val plan = Plan(started, wake, listOf(
                PlannedStep(Block.MIDDLE, ProgramStep(Mode.HEAT, 9, 50, 8, 0), started, 480),
            ))
            val night = ActiveNight("frozen-countdown", profile.id, started.toString(), wake.toString(),
                false, 2, wake.toString(), started.plusHours(8).toString(), deviceId = target,
                profile = profile, applied = AppliedProgram.from(plan), confidence = Confidence.CONFIRMED)
            graph.repository.addRecord(NightRecord(night.recordId, profile.id, profile.name, night.startedAt,
                night.wakeAt, false, deviceId = target))
            graph.repository.setActiveNight(night)
            link.connect()
            val port = radio.ports.single()
            val first = withTimeout(2_000) { link.sourcedStatus.filterNotNull().first() }
            graph.actions.observeStatus(first.status, target, graph.now(), first.source)
            assertEquals(28_200, first.status.remainingSeconds)
            assertEquals(1, first.status.extra?.programStep)
            var receipt = first.receiptOrder

            repeat(10) {
                graph.clock = Clock.offset(graph.clock, Duration.ofMinutes(1))
                port.notify(frozenPacket.copyOf())
                val report = withTimeout(2_000) {
                    link.sourcedStatus.filterNotNull().first { it.receiptOrder > receipt }
                }
                assertEquals("The report must retain its real GATT target provenance", target, report.source)
                assertEquals("Only receipt order changes; the countdown and programme fields stay frozen", first.status, report.status)
                graph.actions.observeStatus(report.status, target, graph.now(), report.source)
                assertEquals("Repeated equal reports are not evidence of a physical takeover", night, graph.repository.activeNight.value)
                receipt = report.receiptOrder
            }

            // Flush the main-thread notice launcher and its serial writer before asserting silence.
            shadowOf(Looper.getMainLooper()).idle()
            graph.repository.updateRuntime { it }
            assertNull(graph.repository.runtime.value.reason)
            assertTrue(graph.repository.history.value.single().events.none { it.kind == EventKind.REMOTE_CHANGE })
            assertTrue("Frozen countdown reports must not produce logged notices", graph.repository.notices.value.isEmpty())
            assertTrue("Frozen countdown reports must not post notifications",
                shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications.isEmpty())
        } finally { link.shutdown() }
    }

    @Test fun aPostOffExtensionCannotRenewAPreOffStandbyHeadButANewEqualHeadCanConfirm() = runBlocking {
        val context: Context = RuntimeEnvironment.getApplication()
        val target = DeviceId.ble("AA:BB:CC:DD:EE:FF")
        val radio = FakeRadio()
        radio.behaviour = FakeRadio.Behaviour(firstStatus = Status.head())
        val link = GattLink(radio.radio, { target }, GattLink.Timings(operationMs = 5_000),
            clock = { 0 })
        val graph = graphs.fresh { Graph(context, link) { scope, now -> Repository(context, scope, now = now) } }
        graph.clock = Clock.fixed(Instant.parse("2026-10-21T22:30:00Z"), ZoneOffset.UTC)
        context.graph.clock = graph.clock
        try {
            graph.repository.updateSettings { it.copy(demoMode = false, targetDevice = target) }
            link.connect()
            val port = radio.ports.single()
            port.notify(Status.head())
            withTimeout(2_000) { while (Attr.STATUS !in port.reads) yield() }
            val head = link.latestSourcedStatus()!!
            assertFalse(head.status.isRunning)
            assertNull(head.status.extra)
            val profile = graph.repository.profiles.value.first()
            val now = graph.now()
            val night = ActiveNight("pending-off", profile.id, now.toString(), now.plusHours(8).toString(),
                false, 2, now.plusHours(7).toString(), now.plusHours(9).toString(),
                deviceId = target, profile = profile, confidence = Confidence.UNKNOWN)
            graph.repository.setActiveNight(night)
            graph.repository.addRecord(NightRecord(night.recordId, profile.id, profile.name, night.startedAt,
                night.wakeAt, false, deviceId = target))
            // The durable request boundary is after this head but before its extension arrives.
            val requested = graph.actions.statusReceipt()
            graph.repository.updateRuntime { it.copy(endRequested = night.identity, endRequestedLabel = "BedJet off.",
                endRequestedAt = now.toString(), endRequestedEpoch = requested.epoch, endRequestedSequence = requested.sequence) }
            assertTrue(Attr.STATUS in port.reads)
            port.answerStatusRead(Status.remainder(programStep = 0, notification = 1))
            val extended = withTimeout(2_000) { link.sourcedStatus.filterNotNull().first { it.status.extra?.notification == 1 } }

            graph.actions.observeStatus(extended.status, target)

            assertEquals("an extension contains no new Standby observation", night.identity, graph.repository.activeNight.value?.identity)
            assertEquals(night.identity, graph.repository.runtime.value.endRequested)
            assertTrue(graph.repository.history.value.single().events.none { it.kind == EventKind.STOPPED })

            // The next identical head really is a new observation, even though DeviceStatus is unchanged.
            port.notify(Status.head())
            radio.drain()
            graph.actions.observeStatus(link.latestSourcedStatus()!!.status, target)
            assertNull("the newly received equal head may confirm Off", graph.repository.activeNight.value)
            assertNull(graph.repository.runtime.value.endRequested)
            assertEquals(1, graph.repository.history.value.single().events.count { it.kind == EventKind.STOPPED })
        } finally { link.shutdown() }
    }
}
