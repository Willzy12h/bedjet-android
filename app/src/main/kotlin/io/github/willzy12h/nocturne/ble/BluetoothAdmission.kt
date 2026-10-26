package io.github.willzy12h.nocturne.ble

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import io.github.willzy12h.nocturne.R
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** A request that waited through a radio interruption must be chosen again, even if the radio is now on.
 * This receiver exists only from submission to owner admission (or bounded cancellation), never while idle. */
class BluetoothAdmission(context: Context) : AbstractCoroutineContextElement(Key), AutoCloseable {
    companion object Key : CoroutineContext.Key<BluetoothAdmission>
    private val context = context.applicationContext
    private var interrupted = false
    private var listening = true
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            // This is a protected Android broadcast. Its transition is useful even when delivery was delayed
            // until the adapter returned. It never asserts a connected/confirmed state or drives the UI banner.
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            synchronized(this@BluetoothAdmission) {
                if (listening && (state == BluetoothAdapter.STATE_OFF || state == BluetoothAdapter.STATE_TURNING_OFF ||
                        state == BluetoothAdapter.STATE_TURNING_ON || BluetoothAvailability.read(context) == BluetoothAvailability.State.OFF))
                    interrupted = true
            }
        }
    }
    init {
        ContextCompat.registerReceiver(this.context, receiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED)
    }

    @Synchronized fun check() {
        BluetoothAvailability.requireEnabled(context)
        if (interrupted) throw BluetoothInterruptedException(context.getString(R.string.bluetooth_interrupted))
    }

    @Synchronized fun admit() {
        check()
        close()
    }

    @Synchronized override fun close() {
        if (listening) {
            listening = false
            context.unregisterReceiver(receiver)
        }
    }
}
