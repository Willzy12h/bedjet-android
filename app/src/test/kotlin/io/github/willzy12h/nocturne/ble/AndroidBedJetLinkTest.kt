package io.github.willzy12h.nocturne.ble

import android.content.Context
import io.github.willzy12h.nocturne.core.link.BedJetError
import io.github.willzy12h.nocturne.core.link.LinkState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The Android adapter's own duties: the old remembered address, forgetting, and permission checks. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidBedJetLinkTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val prefs = context.getSharedPreferences("ble", Context.MODE_PRIVATE)

    @Test fun outerAdapterForwardsFreshStatusPreparation() = runBlocking {
        val demo = io.github.willzy12h.nocturne.core.link.DemoBedJet()
        demo.connect()
        val adapter = AndroidBedJetLink(context, demo) { null }
        adapter.prepareForStart()
        assertNull(demo.status.value!!.extra)
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
