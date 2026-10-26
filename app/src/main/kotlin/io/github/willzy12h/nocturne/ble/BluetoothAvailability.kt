package io.github.willzy12h.nocturne.ble

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.graph

/** Read the adapter itself; broadcast extras are only a signal to read it again. */
object BluetoothAvailability {
    enum class State { ENABLED, OFF, PERMISSION_REQUIRED, UNAVAILABLE }

    val permissions: Array<String>
        get() = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)

    fun read(context: Context): State {
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
            return State.PERMISSION_REQUIRED
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return State.UNAVAILABLE
        return try {
            if (adapter.isEnabled) State.ENABLED else State.OFF
        } catch (_: SecurityException) { State.PERMISSION_REQUIRED }
    }

    /** Checked before service delivery and again under the command owner, before any connection or write. */
    fun requireEnabled(context: Context) {
        if (!context.graph.repository.settings.value.demoMode && read(context) == State.OFF)
            throw BluetoothOffException(context.getString(R.string.bluetooth_off))
    }
}

open class BluetoothCommandException(message: String) : IllegalStateException(message)
class BluetoothOffException(message: String) : BluetoothCommandException(message)
class BluetoothInterruptedException(message: String) : BluetoothCommandException(message)
