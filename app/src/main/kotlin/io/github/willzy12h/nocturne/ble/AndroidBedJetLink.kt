package io.github.willzy12h.nocturne.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import io.github.willzy12h.nocturne.core.link.BedJetLink
import io.github.willzy12h.nocturne.core.link.LinkState
import io.github.willzy12h.nocturne.core.link.SourcedStatus
import io.github.willzy12h.nocturne.core.protocol.DeviceStatus
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/**
 * Bluetooth LE transport for a BedJet 3: the chosen unit only, named by [target] as a `ble:<MAC>` device id.
 * The connection logic lives in [GattLink]; this class supplies Android's Bluetooth classes and remembers the
 * unit's name for display.
 */
class AndroidBedJetLink(private val context: Context, radio: GattRadio? = null, target: () -> String?) : BedJetLink {
    private val prefs = context.getSharedPreferences("ble", Context.MODE_PRIVATE)
    private val names = DeviceNames(context, target)
    val verifiedNames: StateFlow<Pair<String, String?>?> get() = names.verified
    private val engine = GattLink(radio ?: AndroidRadio(context, ::hasPermissions), target,
        onName = names::display, onVerifiedName = names::verify)

    override val state: StateFlow<LinkState> get() = engine.state
    override val status: StateFlow<DeviceStatus?> get() = engine.status
    override val sourcedStatus: StateFlow<SourcedStatus?> get() = engine.sourcedStatus
    override fun latestSourcedStatus(): SourcedStatus? = engine.latestSourcedStatus()

    /** The unit's name, once read; shown in settings. */
    val deviceName: String? get() = names.displayName()
    /** Exact GATT characteristic name, bound to its originating device; legacy display names cannot qualify. */
    fun deviceNameFor(deviceId: String): String? = names.verifiedName(deviceId)

    override suspend fun connect() = engine.connect()
    override suspend fun prepareForStart() = engine.prepareForStart()
    override fun cancelPreparedStart() = engine.cancelPreparedStart()
    override suspend fun disconnect() = engine.disconnect()
    override suspend fun send(command: ByteArray) = engine.send(command)
    override suspend fun sendGuarded(command: ByteArray, beforeWrite: () -> Unit) = engine.sendGuarded(command, beforeWrite)
    override suspend fun query(request: Int): ByteArray = engine.query(request)

    /** Lists the BedJets nearby for setup to choose from, without connecting to any. */
    suspend fun discover(timeoutMs: Long): List<FoundBedJet> = engine.discover(timeoutMs)

    /**
     * The address earlier versions remembered after their first connection, from before the BedJet was chosen in
     * setup. The app adopts it as the target when none is set; this link never connects to it by itself.
     */
    fun rememberedAddress(): String? = prefs.getString(KEY_ADDRESS, null)

    /** Forgets the remembered unit and its name. */
    fun forget() {
        prefs.edit().remove(KEY_ADDRESS).remove(KEY_NAME).apply()
        names.forget()
    }

    fun hasPermissions(): Boolean = listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        .all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private companion object {
        const val KEY_ADDRESS = "address"
        const val KEY_NAME = "name"
    }
}

/** The phone's adapter, for [GattLink]. Calls can throw [SecurityException]; [GattLink] handles that. */
@SuppressLint("MissingPermission") // GattLink checks availability() first and turns a SecurityException into an error.
private class AndroidRadio(private val context: Context, private val permitted: () -> Boolean) : GattRadio {
    private val manager = context.getSystemService(BluetoothManager::class.java)

    override fun availability(): Radio {
        val adapter = manager?.adapter
        return when {
            !permitted() -> Radio.UNAUTHORIZED
            adapter == null || !adapter.isEnabled -> Radio.OFF
            else -> Radio.READY
        }
    }

    override fun connect(address: String, sink: GattSink): GattPort {
        val adapter = manager?.adapter ?: throw IllegalStateException("No Bluetooth adapter")
        val port = AndroidPort()
        val gatt = adapter.getRemoteDevice(address).connectGatt(context, false, port.callback(sink), BluetoothDevice.TRANSPORT_LE)
            ?: throw IllegalStateException("connectGatt refused")
        port.bind(gatt)
        return port
    }

    override fun scan(spec: ScanSpec, onHit: (ScanHit) -> Unit, onFailed: (Int) -> Unit): AutoCloseable {
        val scanner = manager?.adapter?.bluetoothLeScanner ?: throw IllegalStateException("Bluetooth is off")
        val filters = when (spec) {
            ScanSpec.Service -> listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(Gatt.SERVICE)).build())
            ScanSpec.Unfiltered -> null
        }
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val record = result.scanRecord
                val name = record?.deviceName ?: runCatching { result.device.name }.getOrNull()
                val service = ParcelUuid(Gatt.SERVICE) in record?.serviceUuids.orEmpty()
                onHit(ScanHit(result.device.address, name, result.rssi, service))
            }

            override fun onScanFailed(errorCode: Int) = onFailed(errorCode)
        }
        scanner.startScan(filters, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
        return AutoCloseable { scanner.stopScan(callback) }
    }
}

/** One [BluetoothGatt] connection, for [GattLink]. Its callback forwards to that connection's own sink. */
@SuppressLint("MissingPermission")
private class AndroidPort : GattPort {
    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var characteristics: Map<Attr, BluetoothGattCharacteristic> = emptyMap()

    private fun gatt(): BluetoothGatt = gatt ?: throw IllegalStateException("Not connected yet")
    private fun characteristic(attr: Attr) = characteristics[attr] ?: throw IllegalStateException("No $attr characteristic")

    fun bind(gatt: BluetoothGatt) {
        this.gatt = gatt
    }

    override fun requestMtu(mtu: Int): Boolean = gatt().requestMtu(mtu)

    override fun discoverServices(): Boolean = gatt().discoverServices()

    override fun resolve(): Boolean {
        val service = gatt().getService(Gatt.SERVICE) ?: return false
        characteristics = listOf(Attr.COMMAND to Gatt.COMMAND, Attr.REPLY to Gatt.REPLY, Attr.STATUS to Gatt.STATUS, Attr.NAME to Gatt.NAME)
            .mapNotNull { (attr, uuid) -> service.getCharacteristic(uuid)?.let { attr to it } }
            .toMap()
        return listOf(Attr.COMMAND, Attr.REPLY, Attr.STATUS).all { it in characteristics }
    }

    override fun write(attr: Attr, data: ByteArray): Boolean {
        val characteristic = characteristic(attr)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt().writeCharacteristic(characteristic, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = data
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            gatt().writeCharacteristic(characteristic)
        }
    }

    override fun has(attr: Attr): Boolean = attr in characteristics

    override fun read(attr: Attr): Boolean = gatt().readCharacteristic(characteristic(attr))

    override fun enableNotifications(): NotifyStart {
        val characteristic = characteristic(Attr.STATUS)
        if (!gatt().setCharacteristicNotification(characteristic, true)) return NotifyStart.REFUSED
        val descriptor = characteristic.getDescriptor(Gatt.CCCD) ?: return NotifyStart.DONE
        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt().writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            gatt().writeDescriptor(descriptor)
        }
        return if (started) NotifyStart.STARTED else NotifyStart.REFUSED
    }

    override fun disconnect() {
        gatt?.disconnect()
    }

    override fun close() {
        gatt?.close()
    }

    fun callback(sink: GattSink) = object : BluetoothGattCallback() {
        private fun attr(characteristic: BluetoothGattCharacteristic) = Gatt.attr(characteristic.uuid)

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            // Can arrive before connectGatt has returned.
            if (this@AndroidPort.gatt == null) bind(gatt)
            sink.onConnectionStateChange(status, newState == BluetoothProfile.STATE_CONNECTED)
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) = sink.onMtuChanged(mtu, status)

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) = sink.onServicesDiscovered(status)

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) =
            sink.onWrite(attr(characteristic), status)

        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) =
            sink.onRead(attr(characteristic), value, status)

        @Deprecated("Used below Android 13")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) sink.onRead(attr(characteristic), characteristic.value ?: ByteArray(0), status)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) =
            sink.onNotify(attr(characteristic), value)

        @Deprecated("Used below Android 13")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) sink.onNotify(attr(characteristic), characteristic.value ?: return)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) =
            sink.onDescriptorWrite(attr(descriptor.characteristic), descriptor.uuid == Gatt.CCCD, status)
    }
}

/** The BedJet 3's GATT layout. */
private object Gatt {
    private fun uuid(short: String): UUID = UUID.fromString("0000$short-bed0-0080-aa55-4265644a6574")
    val SERVICE: UUID = uuid("1000")
    val STATUS: UUID = uuid("2000")
    val NAME: UUID = uuid("2001")
    val COMMAND: UUID = uuid("2004")
    val REPLY: UUID = uuid("2006")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    fun attr(uuid: UUID): Attr = when (uuid) {
        COMMAND -> Attr.COMMAND
        REPLY -> Attr.REPLY
        STATUS -> Attr.STATUS
        NAME -> Attr.NAME
        else -> Attr.OTHER
    }
}

/** Paired editor transactions and observable verification. No mutable target sampling in either callback. */
internal class DeviceNames(context: Context, private val target: () -> String?) {
    private val prefs = context.getSharedPreferences("ble", Context.MODE_PRIVATE)
    private val mutable = kotlinx.coroutines.flow.MutableStateFlow(prefs.getString("verifiedNameDevice", null)?.let {
        it to prefs.getString("verifiedName", null)
    })
    val verified: StateFlow<Pair<String, String?>?> = mutable
    fun display(device: String, name: String) {
        prefs.edit().putString("name", name).putString("nameDevice", device).apply()
    }
    fun verify(device: String, name: String?) {
        prefs.edit().putString("verifiedNameDevice", device).putString("verifiedName", name).apply()
        mutable.value = device to name
    }
    fun displayName(): String? = if (prefs.getString("nameDevice", null) == target()) prefs.getString("name", null) else null
    fun verifiedName(device: String): String? = mutable.value?.takeIf { it.first == device }?.second
    fun forget() {
        prefs.edit().remove("name").remove("nameDevice").remove("verifiedName").remove("verifiedNameDevice").apply()
        mutable.value = null
    }
}
