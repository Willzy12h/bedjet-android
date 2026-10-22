package io.github.willzy12h.nocturne.night

import android.content.Context
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.ble.FakeRadio
import io.github.willzy12h.nocturne.ble.GattLink
import io.github.willzy12h.nocturne.ble.GattLinkTest
import io.github.willzy12h.nocturne.core.link.BedJetLink
import io.github.willzy12h.nocturne.core.link.DeviceId
import io.github.willzy12h.nocturne.core.store.deviceId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Switching BedJets through [NightActions] over the real connection logic and a fake adapter (IR-02). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TargetSwitchTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val radio = FakeRadio()
    private lateinit var graph: Graph
    private lateinit var gatt: GattLink
    /** Set to stand in for a screen reconnect that lands between the switch's disconnect and its save. */
    @Volatile private var racingReconnect = false

    @Before fun setup() = runBlocking {
        gatt = GattLink(radio.radio, { graph.repository.settings.value.deviceId() }, GattLinkTest.TIMINGS)
        val link = object : BedJetLink by gatt {
            override suspend fun disconnect() {
                gatt.disconnect()
                if (racingReconnect) runCatching { gatt.connect() }
            }
        }
        graph = Graph(context, link)
        graph.repository.updateSettings { it.copy(demoMode = false, targetDevice = A) }
    }

    @After fun close() = gatt.shutdown()

    @Test fun afterASwitchTheBackupOfTheNewBedJetNeverReadsTheOldOne() = runBlocking {
        gatt.connect()
        val old = radio.ports.single()
        racingReconnect = true
        assertEquals(OutcomeKind.LOCAL, graph.actions.selectTarget(B).kind)
        racingReconnect = false
        val queriesBefore = radio.ports.filter { it.address == GattLinkTest.ADDRESS }.sumOf { queries(it) }
        runCatching { graph.sessions.withConnection { graph.actions.backup(B, 2) } }
        val oldQueries = radio.ports.filter { it.address == GattLinkTest.ADDRESS }.sumOf { queries(it) }
        assertEquals("backup(B) read through a session on A", queriesBefore, oldQueries)
        assertTrue(radio.ports.filter { it.address == GattLinkTest.OTHER }.any { queries(it) > 0 })
        assertTrue(old.closed)
        assertFalse(radio.ports.filter { it.address == GattLinkTest.ADDRESS }.any { !it.closed })
    }

    private fun queries(port: FakeRadio.FakePort) = port.writes.count { it[0].toInt() == 0x41 }

    private companion object {
        val A = DeviceId.ble(GattLinkTest.ADDRESS)
        val B = DeviceId.ble(GattLinkTest.OTHER)
    }
}
