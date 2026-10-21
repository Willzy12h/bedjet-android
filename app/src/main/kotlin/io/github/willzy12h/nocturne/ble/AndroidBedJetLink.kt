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
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import io.github.willzy12h.nocturne.core.link.BedJetError
import io.github.willzy12h.nocturne.core.link.BedJetLink
import io.github.willzy12h.nocturne.core.link.LinkState
import io.github.willzy12h.nocturne.core.protocol.Codec
import io.github.willzy12h.nocturne.core.protocol.DeviceStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Bluetooth LE transport for a BedJet 3. Connects to the remembered unit, or the first one found by scanning.
 *
 * The BedJet accepts one connection at a time and handles one request at a time, so every operation goes through
 * [operations]. GATT callbacks arrive on a binder thread and complete the pending operation's deferred.
 */
@SuppressLint("MissingPermission") // Every entry point checks permissions through hasPermissions().
class AndroidBedJetLink(private val context: Context) : BedJetLink {
    private val _state = MutableStateFlow(LinkState.SEARCHING)
    override val state: StateFlow<LinkState> = _state.asStateFlow()
    private val _status = MutableStateFlow<DeviceStatus?>(null)
    override val status: StateFlow<DeviceStatus?> = _status.asStateFlow()

    private val prefs = context.getSharedPreferences("ble", Context.MODE_PRIVATE)
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val operations = Mutex()
    private val connecting = Mutex()

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var command: BluetoothGattCharacteristic? = null
    @Volatile private var reply: BluetoothGattCharacteristic? = null
    @Volatile private var statusCharacteristic: BluetoothGattCharacteristic? = null
    @Volatile private var pendingWrite: CompletableDeferred<Unit>? = null
    @Volatile private var pendingRead: CompletableDeferred<ByteArray>? = null
    @Volatile private var pendingDescriptor: CompletableDeferred<Unit>? = null
    @Volatile private var pendingServices: CompletableDeferred<Boolean>? = null
    private var tag = 0
    private var lastHead: ByteArray? = null
    private var lastExtra: DeviceStatus.Extra? = null
    private var lastRemainderRead = 0L

    /** The unit's name, once read; shown in settings. */
    var deviceName: String? = prefs.getString(KEY_NAME, null)
        private set

    override suspend fun connect() = connecting.withLock {
        if (_state.value == LinkState.READY && gatt != null) return@withLock
        val adapter = manager?.adapter
        if (!hasPermissions()) { _state.value = LinkState.UNAUTHORIZED; throw BedJetError.NotConnected() }
        if (adapter == null || !adapter.isEnabled) { _state.value = LinkState.BLUETOOTH_OFF; throw BedJetError.NotConnected() }

        val remembered = prefs.getString(KEY_ADDRESS, null)?.let { runCatching { adapter.getRemoteDevice(it) }.getOrNull() }
        // Going straight to a known unit is quicker than scanning; one that was replaced or moved never answers.
        if (remembered != null && attach(remembered, REMEMBERED_PATIENCE_MS)) return@withLock
        _state.value = LinkState.SEARCHING
        val found = scan() ?: run { _state.value = LinkState.SEARCHING; throw BedJetError.NotConnected() }
        if (!attach(found, CONNECT_PATIENCE_MS)) { _state.value = LinkState.SEARCHING; throw BedJetError.NotConnected() }
    }

    override suspend fun disconnect() {
        operations.withLock {
            gatt?.let { it.disconnect(); it.close() }
            dropConnection()
        }
    }

    /** Forgets the remembered unit so the next connection pairs with whichever BedJet is nearest. */
    fun forget() {
        prefs.edit().remove(KEY_ADDRESS).remove(KEY_NAME).apply()
        deviceName = null
    }

    override suspend fun send(command: ByteArray) = operations.withLock { write(command) }

    override suspend fun query(request: Int): ByteArray = operations.withLock {
        // The reply characteristic keeps its previous contents until the unit has answered,
        // so retry until the echoed request and tag match.
        repeat(4) { attempt ->
            tag = if (tag >= 255) 1 else tag + 1
            write(Codec.query(request, tag))
            if (attempt > 0) delay(150)
            val data = read(reply ?: throw BedJetError.NotConnected())
            if (data.size >= 2 && (data[0].toInt() and 0xff) == request && (data[1].toInt() and 0xff) == tag) {
                return@withLock data.copyOfRange(2, data.size)
            }
        }
        throw BedJetError.BadResponse()
    }

    // Connection

    private suspend fun scan(): BluetoothDevice? {
        val scanner = manager?.adapter?.bluetoothLeScanner ?: return null
        val result = CompletableDeferred<BluetoothDevice>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, scan: ScanResult) {
                val services = scan.scanRecord?.serviceUuids.orEmpty()
                val name = scan.scanRecord?.deviceName ?: scan.device.name.orEmpty()
                if (ParcelUuid(SERVICE) in services || name.uppercase().startsWith("BEDJET")) result.complete(scan.device)
            }
        }
        // Not filtered by service: some units advertise only their name.
        scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
        return try {
            withTimeoutOrNull(SCAN_PATIENCE_MS) { result.await() }
        } finally {
            runCatching { scanner.stopScan(callback) }
        }
    }

    private suspend fun attach(device: BluetoothDevice, patienceMs: Long): Boolean {
        _state.value = LinkState.CONNECTING
        val services = CompletableDeferred<Boolean>()
        pendingServices = services
        val connection = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        gatt = connection
        val ready = withTimeoutOrNull(patienceMs) { services.await() } ?: false
        if (!ready) {
            connection.disconnect()
            connection.close()
            dropConnection()
            return false
        }
        // Notifications must be switched on before the unit streams its status.
        statusCharacteristic?.let { enableNotifications(connection, it) }
        prefs.edit().putString(KEY_ADDRESS, device.address).apply()
        _state.value = LinkState.READY
        runCatching { deviceName = Codec.parseText(query(Codec.Query.DEVICE_NAME)).also { prefs.edit().putString(KEY_NAME, it).apply() } }
        withTimeoutOrNull(3_000) { status.first { it != null } }
        return true
    }

    private suspend fun enableNotifications(connection: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        connection.setCharacteristicNotification(characteristic, true)
        val descriptor = characteristic.getDescriptor(CCCD) ?: return
        val done = CompletableDeferred<Unit>()
        pendingDescriptor = done
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            connection.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            connection.writeDescriptor(descriptor)
        }
        withTimeoutOrNull(OPERATION_TIMEOUT_MS) { done.await() }
    }

    private fun dropConnection() {
        gatt = null
        command = null
        reply = null
        statusCharacteristic = null
        pendingWrite?.completeExceptionally(BedJetError.NotConnected())
        pendingRead?.completeExceptionally(BedJetError.NotConnected())
        pendingServices?.complete(false)
        pendingWrite = null
        pendingRead = null
        pendingServices = null
        _status.value = null
        if (_state.value == LinkState.READY || _state.value == LinkState.CONNECTING) _state.value = LinkState.SEARCHING
    }

    // Operations

    private suspend fun write(data: ByteArray) {
        val connection = gatt ?: throw BedJetError.NotConnected()
        val characteristic = command ?: throw BedJetError.NotConnected()
        val done = CompletableDeferred<Unit>()
        pendingWrite = done
        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            connection.writeCharacteristic(characteristic, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = data
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            connection.writeCharacteristic(characteristic)
        }
        if (!started) { pendingWrite = null; throw BedJetError.NotConnected() }
        awaitOperation(done)
    }

    private suspend fun read(characteristic: BluetoothGattCharacteristic): ByteArray {
        val connection = gatt ?: throw BedJetError.NotConnected()
        val done = CompletableDeferred<ByteArray>()
        pendingRead = done
        if (!connection.readCharacteristic(characteristic)) { pendingRead = null; throw BedJetError.NotConnected() }
        return awaitOperation(done)
    }

    private suspend fun <T> awaitOperation(deferred: CompletableDeferred<T>): T =
        try {
            withTimeout(OPERATION_TIMEOUT_MS) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            throw BedJetError.Timeout()
        }

    /**
     * Notifications carry the live readings. When the packet size is too small for the whole status, the unit sends
     * the first part and serves the remainder (program progress, LED and beep settings) to a read of the same
     * characteristic.
     */
    private fun receiveStatus(chunk: ByteArray) {
        if (!Codec.isStatusStart(chunk)) {
            val head = lastHead ?: return
            Codec.parseStatus(head + chunk)?.extra?.let { extra ->
                lastExtra = extra
                _status.value = _status.value?.copy(extra = extra)
            }
            return
        }
        val parsed = Codec.parseStatus(chunk) ?: return
        if (parsed.extra != null) {
            lastExtra = parsed.extra
            _status.value = parsed
        } else {
            lastHead = chunk
            _status.value = parsed.copy(extra = lastExtra)
            val now = System.currentTimeMillis()
            val connection = gatt
            val characteristic = statusCharacteristic
            // Only when nothing else is in flight: the unit handles one request at a time.
            if (connection != null && characteristic != null && now - lastRemainderRead > 2_000 &&
                pendingRead == null && pendingWrite == null
            ) {
                lastRemainderRead = now
                connection.readCharacteristic(characteristic)
            }
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (gatt != this@AndroidBedJetLink.gatt) return
            when (newState) {
                // A larger packet size lets the whole status arrive in one notification.
                BluetoothProfile.STATE_CONNECTED -> if (!gatt.requestMtu(247)) gatt.discoverServices()
                BluetoothProfile.STATE_DISCONNECTED -> { gatt.close(); dropConnection() }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val service = gatt.getService(SERVICE)
            command = service?.getCharacteristic(COMMAND)
            reply = service?.getCharacteristic(REPLY)
            statusCharacteristic = service?.getCharacteristic(STATUS)
            pendingServices?.complete(command != null && reply != null && statusCharacteristic != null)
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            val pending = pendingWrite ?: return
            pendingWrite = null
            if (status == BluetoothGatt.GATT_SUCCESS) pending.complete(Unit) else pending.completeExceptionally(BedJetError.BadResponse())
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            readCompleted(characteristic, value, status)
        }

        @Deprecated("Used below Android 13")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) readCompleted(characteristic, characteristic.value ?: ByteArray(0), status)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (characteristic.uuid == STATUS) receiveStatus(value)
        }

        @Deprecated("Used below Android 13")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && characteristic.uuid == STATUS) receiveStatus(characteristic.value ?: return)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            pendingDescriptor?.complete(Unit)
            pendingDescriptor = null
        }
    }

    private fun readCompleted(characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
        if (characteristic.uuid == STATUS && pendingRead == null) {
            // The remainder of a split status.
            if (status == BluetoothGatt.GATT_SUCCESS) receiveStatus(value)
            return
        }
        val pending = pendingRead ?: return
        pendingRead = null
        if (status == BluetoothGatt.GATT_SUCCESS) pending.complete(value) else pending.completeExceptionally(BedJetError.BadResponse())
    }

    fun hasPermissions(): Boolean = listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        .all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private companion object {
        fun uuid(short: String): UUID = UUID.fromString("0000$short-bed0-0080-aa55-4265644a6574")
        val SERVICE: UUID = uuid("1000")
        val STATUS: UUID = uuid("2000")
        val COMMAND: UUID = uuid("2004")
        val REPLY: UUID = uuid("2006")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val KEY_ADDRESS = "address"
        const val KEY_NAME = "name"
        const val OPERATION_TIMEOUT_MS = 5_000L
        const val REMEMBERED_PATIENCE_MS = 12_000L
        const val CONNECT_PATIENCE_MS = 15_000L
        const val SCAN_PATIENCE_MS = 20_000L
    }
}
