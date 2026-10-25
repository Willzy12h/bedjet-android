package io.github.willzy12h.nocturne.platform

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import io.github.willzy12h.nocturne.App
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.ble.DeviceNames
import io.github.willzy12h.nocturne.core.link.DemoBedJet
import io.github.willzy12h.nocturne.night.CommandOrigin
import io.github.willzy12h.nocturne.night.OutcomeKind
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** The local phone's advertisement is the request; an incoming request uses peerRequest instead. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [PhoneHandoverOriginTest.SuccessfulAdvertiser::class])
class PhoneHandoverOriginTest : PlatformGraphFixture() {
    private val app = RuntimeEnvironment.getApplication() as App

    private suspend fun graph(): Graph {
        val graph = fresh { Graph(app, DemoBedJet()) }
        graph.clock = Clock.fixed(Instant.parse("2026-10-21T22:30:00Z"), ZoneOffset.UTC)
        val target = "ble:AA:BB:CC:DD:EE:FF"
        graph.repository.updateSettings {
            it.copy(demoMode = false, targetDevice = target, platform = it.platform.copy(lullHandover = true))
        }
        DeviceNames(app) { target }.verify(target, "BEDJET-ORIGIN")
        return graph
    }

    @Test fun aLocallyRequestedHandoverRefusalKeepsAppOrigin() = runTest {
        val graph = graph()
        shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_ADVERTISE)
        val outcome = graph.platform.handover.request()
        assertEquals(OutcomeKind.REFUSED, outcome.kind)
        assertEquals(CommandOrigin.APP, outcome.origin)
        assertEquals(app.getString(R.string.feature_advertise_permission), outcome.message)
    }

    @Test fun aLocallyAdvertisedHandoverRequestKeepsAppOrigin() = runTest {
        val graph = graph()
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_ADVERTISE)
        val adapter = app.getSystemService(BluetoothManager::class.java).adapter
        shadowOf(adapter).setEnabled(true)
        shadowOf(adapter).setBluetoothLeAdvertiser(Shadow.newInstanceOf(BluetoothLeAdvertiser::class.java))
        val outcome = graph.platform.handover.request()
        assertEquals(OutcomeKind.LOCAL, outcome.kind)
        assertEquals(CommandOrigin.APP, outcome.origin)
        assertEquals(app.getString(R.string.feature_handover_requested), outcome.message)
    }

    /** Only the platform advertising callback is controlled; the complete request coroutine remains real. */
    @Implements(BluetoothLeAdvertiser::class)
    class SuccessfulAdvertiser {
        @Implementation
        protected fun startAdvertising(settings: AdvertiseSettings, data: AdvertiseData, callback: AdvertiseCallback) {
            callback.onStartSuccess(settings)
        }

        @Implementation
        protected fun stopAdvertising(callback: AdvertiseCallback) = Unit
    }
}
