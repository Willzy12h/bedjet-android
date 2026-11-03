package io.github.willzy12h.nocturne.night

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import io.github.willzy12h.nocturne.App
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.GraphLifecycle
import io.github.willzy12h.nocturne.core.link.BedJetLink
import io.github.willzy12h.nocturne.core.link.DemoBedJet
import io.github.willzy12h.nocturne.core.link.DeviceId
import io.github.willzy12h.nocturne.core.link.SourcedStatus
import io.github.willzy12h.nocturne.core.link.StatusReceiptOrder
import io.github.willzy12h.nocturne.core.night.ActiveNight
import io.github.willzy12h.nocturne.core.plan.Profile
import io.github.willzy12h.nocturne.core.protocol.Codec
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.store.BackupCodec
import io.github.willzy12h.nocturne.core.store.deviceId
import io.github.willzy12h.nocturne.data.Repository
import kotlinx.coroutines.flow.map
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.time.Clock
import java.time.ZoneOffset
import java.time.ZonedDateTime

/** Real command owner and persistent repository; only the byte-speaking device is simulated. */
internal class ProgrammeOwnerFixture(graphs: GraphLifecycle) {
    val app = RuntimeEnvironment.getApplication() as App
    val target = DeviceId.ble("AA:BB:CC:DD:EE:01")
    val started = ZonedDateTime.parse("2026-10-29T22:00:00Z")
    val graph: Graph
    val sent = mutableListOf<ByteArray>()
    var afterSend: suspend (ByteArray) -> Unit = {}
    @Volatile var beforeFileWrite: ((File, ByteArray) -> Unit)? = null
    private fun time() = graph.now()
    val device = DemoBedJet(now = ::time)
    val link = object : BedJetLink by device {
        override val sourcedStatus = device.sourcedStatus.map { it?.copy(source = target) }
        override fun latestSourcedStatus() = device.latestSourcedStatus()?.copy(source = target)
        override suspend fun send(command: ByteArray) {
            sent += command
            device.send(command)
            afterSend(command)
        }
        override suspend fun sendGuarded(command: ByteArray, beforeWrite: () -> Unit) { beforeWrite(); send(command) }
    }

    init {
        graph = graphs.fresh { Graph(app, link) { scope, now ->
            Repository(app, scope, writeFile = { file, bytes ->
                beforeFileWrite?.invoke(file, bytes)
                Repository.writeDurably(file, bytes)
            }, now = now)
        } }
        ReflectionHelpers.setField(app, "graph", graph)
        graph.clock = Clock.fixed(started.toInstant(), ZoneOffset.UTC)
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        shadowOf(app.getSystemService(BluetoothManager::class.java).adapter).setState(BluetoothAdapter.STATE_ON)
    }

    suspend fun initialise(profile: Profile? = null) {
        graph.repository.updateSettings { it.copy(demoMode = false, targetDevice = target, workingSlot = 2) }
        assertFalse(graph.repository.isDemoSession)
        assertEquals(target, graph.repository.settings.value.deviceId())
        if (profile != null) graph.repository.saveProfile(profile)
    }

    suspend fun start(profile: Profile? = null) = graph.actions.execute(
        graph.actions.capture(NightActions.Command.START_NIGHT, profile?.id))

    fun at(time: ZonedDateTime) {
        graph.clock = Clock.fixed(time.toInstant(), ZoneOffset.UTC)
        device.tick()
    }

    suspend fun observe(report: SourcedStatus, addressedTarget: String? = target) = graph.actions.observeStatus(
        report.status, addressedTarget, graph.now(), report.source,
        NightActions.StatusReceipt(StatusReceiptOrder.processId, report.receiptOrder))

    suspend fun remoteOff() {
        device.send(Codec.setMode(Mode.STANDBY)!!)
        observe(link.latestSourcedStatus()!!)
    }

    fun starts() = sent.count { it.contentEquals(Codec.startProgram(2)) }
    fun diskNight(): ActiveNight = BackupCodec.json.decodeFromString(ActiveNight.serializer(),
        File(app.filesDir, "active-night.json").readText())
}
