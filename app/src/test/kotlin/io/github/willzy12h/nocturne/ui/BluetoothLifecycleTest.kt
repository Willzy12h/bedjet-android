package io.github.willzy12h.nocturne.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import io.github.willzy12h.nocturne.App
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.GraphLifecycle
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BluetoothLifecycleTest {
    @get:Rule val graphs = GraphLifecycle()

    @Test fun onlyTheVisibleModelListensAndBroadcastExtrasCannotFalsifyAdapterState() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as App
        val graph = graphs.fresh { Graph(app) }
        ReflectionHelpers.setField(app, "graph", graph)
        graph.repository.updateSettings { it.copy(demoMode = false, targetDevice = "ble:AA:BB:CC:DD:EE:FF", autoUpdateCheck = false) }
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        val adapter = app.getSystemService(BluetoothManager::class.java).adapter
        shadowOf(adapter).setEnabled(false)
        val model = AppViewModel(app)
        val store = ViewModelStore().also { it.put("bluetooth", model) }
        fun receivers() = shadowOf(app).registeredReceivers.count { it.intentFilter.hasAction(BluetoothAdapter.ACTION_STATE_CHANGED) }
        try {
            repeat(2) { model.onScreenVisible() }
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, receivers())
            assertTrue(model.bluetoothOff.value)
            app.sendBroadcast(Intent(BluetoothAdapter.ACTION_STATE_CHANGED).putExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_ON))
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue("The real adapter is still off", model.bluetoothOff.value)
            model.onScreenHidden()
            assertEquals(0, receivers())
            shadowOf(adapter).setEnabled(true)
            model.onScreenVisible()
            assertFalse(model.bluetoothOff.value)
            assertEquals(1, receivers())
        } finally { store.clear() }
        assertEquals("Clearing a visible ViewModel must unregister too", 0, receivers())
    }
}
