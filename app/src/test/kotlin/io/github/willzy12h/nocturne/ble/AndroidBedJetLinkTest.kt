package io.github.willzy12h.nocturne.ble

import android.content.Context
import io.github.willzy12h.nocturne.core.link.BedJetError
import io.github.willzy12h.nocturne.core.link.DeviceId
import io.github.willzy12h.nocturne.core.link.LinkState
import kotlinx.coroutines.runBlocking
import io.github.willzy12h.nocturne.core.protocol.Codec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The Android adapter's own duties: forwarding to its engine, the old remembered address, forgetting, and permission checks. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidBedJetLinkTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val prefs = context.getSharedPreferences("ble", Context.MODE_PRIVATE)

    @Test fun outerAdapterForwardsFreshStatusPreparationThroughItsGattEngine() = runBlocking {
        val fake = FakeRadio().apply { behaviour = FakeRadio.Behaviour(firstStatus = Status.full(programStep = 1)) }
        val adapter = AndroidBedJetLink(context, radio = fake.radio) { DeviceId.ble("AA:BB:CC:DD:EE:01") }
        adapter.connect()
        assertEquals(1, adapter.status.value?.extra?.programStep)
        adapter.prepareForStart()
        assertNull(adapter.status.value!!.extra)
        adapter.disconnect()
    }

    @Test fun outerAdapterForwardsTheStartBoundaryCallsToItsGattEngine() = runBlocking {
        val fake = FakeRadio().apply { behaviour = FakeRadio.Behaviour(firstStatus = Status.full(programStep = 1)) }
        val adapter = AndroidBedJetLink(context, radio = fake.radio) { DeviceId.ble("AA:BB:CC:DD:EE:01") }
        try {
            adapter.connect()
            val port = fake.ports.single()
            adapter.prepareForStart()
            // A full report before the start says nothing about the program.
            port.notify(Status.full(programStep = 1))
            fake.drain()
            assertNull(adapter.status.value!!.extra)
            // Ending the preparation reaches the engine: progress is reported again.
            adapter.cancelPreparedStart()
            port.notify(Status.full(programStep = 1))
            fake.drain()
            assertEquals(1, adapter.status.value?.extra?.programStep)
            // The guarded start reaches the engine too: unprepared, it is refused before its final check runs.
            var guarded = false
            assertThrows<BedJetError.NotConnected> { adapter.sendGuarded(Codec.startProgram(2)) { guarded = true } }
            assertFalse("The interface default ran the final check outside the engine", guarded)
            assertFalse(port.writes.any { it.contentEquals(Codec.startProgram(2)) })
        } finally {
            adapter.disconnect()
        }
    }

    @Test
    fun exposesTheAddressRememberedBeforeTargetsExisted() {
        prefs.edit().putString("address", "AA:BB:CC:DD:EE:01").putString("name", "BEDJET 3").commit()
        val link = AndroidBedJetLink(context) { null }
        assertEquals("AA:BB:CC:DD:EE:01", link.rememberedAddress())
        assertEquals("BEDJET 3", link.deviceName)
        link.forget()
        assertNull(link.rememberedAddress())
        assertNull(link.deviceName)
    }

    @Test
    fun withoutBluetoothPermissionConnectAndDiscoverReportUnauthorized(): Unit = runBlocking {
        val link = AndroidBedJetLink(context) { "ble:AA:BB:CC:DD:EE:01" }
        assertThrows<BedJetError.NotConnected> { link.connect() }
        assertEquals(LinkState.UNAUTHORIZED, link.state.value)
        assertThrows<BedJetError.NotConnected> { link.discover(100) }
    }
}
