package io.github.willzy12h.nocturne.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import androidx.compose.ui.test.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class BluetoothBannerTest {
    private val ui = U06Fixture(U06Look.MIDNIGHT, widthDp = 411, fontScale = 1f) { f, _ ->
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        shadowOf(app.getSystemService(BluetoothManager::class.java).adapter).setEnabled(false)
        runBlocking { f.graph.repository.updateSettings { it.copy(demoMode = false, targetDevice = "ble:AA:BB:CC:DD:EE:FF") } }
    }
    @get:Rule val rules = ui.rules

    @Test fun bothScreensOfferEnableAndTheBannerClearsOnARealStateChange() {
        ui.compose.onNodeWithText("Enable Bluetooth").assertIsDisplayed()
        ui.compose.openRemote()
        ui.compose.onNodeWithText("Enable Bluetooth").assertIsDisplayed()
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app.getSystemService(BluetoothManager::class.java).adapter).setEnabled(true)
        app.sendBroadcast(Intent(BluetoothAdapter.ACTION_STATE_CHANGED).putExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_ON))
        ui.waitUntil { ui.compose.onAllNodesWithText("Enable Bluetooth").fetchSemanticsNodes().isEmpty() }
    }

    @Test fun enableLaunchesAndroidsRequestEnableIntent() {
        ui.compose.onNodeWithText("Enable Bluetooth").performClick()
        val launched = shadowOf(ui.compose.activity).nextStartedActivityForResult
        assertNotNull(launched)
        assertEquals(BluetoothAdapter.ACTION_REQUEST_ENABLE, launched.intent.action)
    }

    @Test fun aRevokedConnectPermissionIsRequestedBeforeTheEnableIntent() {
        ui.compose.onNodeWithText("Enable Bluetooth").assertExists()
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        ui.compose.onNodeWithText("Enable Bluetooth").performClick()
        val permissions = shadowOf(ui.compose.activity).lastRequestedPermission
        assertNotNull("The same Nearby devices permission flow must run first", permissions)
        assertTrue(permissions.requestedPermissions.contains(Manifest.permission.BLUETOOTH_CONNECT))
        assertNotEquals("Only the permission request may launch before permission is granted",
            BluetoothAdapter.ACTION_REQUEST_ENABLE, shadowOf(ui.compose.activity).nextStartedActivityForResult?.intent?.action)
    }
}
