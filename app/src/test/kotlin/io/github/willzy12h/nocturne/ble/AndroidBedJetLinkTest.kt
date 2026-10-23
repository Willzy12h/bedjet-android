package io.github.willzy12h.nocturne.ble

import android.content.Context
import io.github.willzy12h.nocturne.core.link.BedJetError
import io.github.willzy12h.nocturne.core.link.DeviceId
import io.github.willzy12h.nocturne.core.link.LinkState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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

    @Test fun theProductionEngineForwardsStatusAndStartPreparation() = runBlocking {
        val fake = FakeRadio().apply { behaviour = FakeRadio.Behaviour(firstStatus = Status.full(programStep = 1)) }
        val adapter = AndroidBedJetLink(context, fake.radio) { DeviceId.ble(ADDRESS) }
        try {
            adapter.connect()
            assertEquals(listOf(ADDRESS), fake.connects)
            // Status and its source come from the engine's own flows.
            assertEquals(1, adapter.status.value?.extra?.programStep)
            assertEquals(DeviceId.ble(ADDRESS), adapter.sourcedStatus.value?.source)
            assertEquals(adapter.status.value, adapter.sourcedStatus.value?.status)
            adapter.prepareForStart()
            assertNull(adapter.status.value!!.extra)
            assertNull(adapter.sourcedStatus.value!!.status.extra)
            // A full report before the start still says nothing about the program.
            fake.ports.single().notify(Status.full(programStep = 1))
            fake.drain()
            assertNull(adapter.status.value!!.extra)
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

    private companion object {
        const val ADDRESS = "AA:BB:CC:DD:EE:01"
    }
}
