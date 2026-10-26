package io.github.willzy12h.nocturne.platform

import android.Manifest
import android.bluetooth.BluetoothManager
import android.os.Looper
import io.github.willzy12h.nocturne.App
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.ble.DeviceNames
import io.github.willzy12h.nocturne.core.link.DemoBedJet
import io.github.willzy12h.nocturne.core.link.LinkState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IdleHandoverTest : PlatformGraphFixture() {
    @Test fun optionalPeerScanningStopsDuringIdleGraceAndReturnsWithTheScreen() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as App
        val graph = fresh { Graph(app, DemoBedJet()) }
        val target = "ble:AA:BB:CC:DD:EE:FF"
        graph.repository.updateSettings { it.copy(demoMode = false, targetDevice = target,
            platform = it.platform.copy(lullHandover = true)) }
        DeviceNames(app) { target }.verify(target, "BEDJET-IDLE")
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        val adapter = app.getSystemService(BluetoothManager::class.java).adapter
        shadowOf(adapter).setEnabled(true)
        val scans = shadowOf(adapter.bluetoothLeScanner)
        graph.platform.handover.watch()
        graph.sessions.holdForScreen(true)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, scans.scanCallbacks.size)

        graph.sessions.holdForScreen(false)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20_000))
        assertEquals("The connection is still in its existing grace", LinkState.READY, graph.link.state.value)
        assertTrue("Idle grace must not continue optional BLE scanning", scans.scanCallbacks.isEmpty())

        graph.sessions.holdForScreen(true)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("The visible screen can receive peer requests again", 1, scans.scanCallbacks.size)
    }
}
