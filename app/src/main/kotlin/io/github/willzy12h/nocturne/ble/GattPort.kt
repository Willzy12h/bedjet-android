package io.github.willzy12h.nocturne.ble

/**
 * The narrow seam between [GattLink]'s connection logic and Android's Bluetooth classes, so that logic can be
 * exercised on the JVM. [AndroidBedJetLink] provides the real implementations; tests drive a fake.
 */

/** The BedJet characteristics the link uses. [OTHER] is anything else, which no operation waits for. */
enum class Attr { COMMAND, REPLY, STATUS, OTHER }

/** How turning on status notifications began. */
enum class NotifyStart {
    /** The descriptor write was issued; its callback finishes the job. */
    STARTED,
    /** Nothing to wait for: the unit has no notification descriptor. */
    DONE,
    /** The stack refused. */
    REFUSED,
}

/** One GATT connection. Every call may throw, notably [SecurityException] when Bluetooth permission is revoked. */
interface GattPort {
    fun requestMtu(mtu: Int): Boolean
    fun discoverServices(): Boolean
    /** After discovery: finds the BedJet service's characteristics, and says whether they are all there. */
    fun resolve(): Boolean
    fun write(attr: Attr, data: ByteArray): Boolean
    fun read(attr: Attr): Boolean
    fun enableNotifications(): NotifyStart
    fun disconnect()
    fun close()
}

/** Where one connection's callbacks go. Each connection gets its own sink, so a stale one can be recognised. */
interface GattSink {
    fun onConnectionStateChange(status: Int, connected: Boolean)
    fun onMtuChanged(mtu: Int, status: Int)
    fun onServicesDiscovered(status: Int)
    fun onWrite(attr: Attr, status: Int)
    fun onRead(attr: Attr, value: ByteArray, status: Int)
    /** [cccd] says whether the descriptor written was the notification switch of [attr]. */
    fun onDescriptorWrite(attr: Attr, cccd: Boolean, status: Int)
    fun onNotify(attr: Attr, value: ByteArray)
}

enum class Radio { READY, UNAUTHORIZED, OFF }

/** What a scan looks for. */
sealed interface ScanSpec {
    /** Units advertising the BedJet service. */
    data object Service : ScanSpec
    /** Anything; the caller filters by name. Android delivers nothing to this while the screen is off. */
    data object Unfiltered : ScanSpec
}

/** One advertisement seen while scanning. */
data class ScanHit(val address: String, val name: String?, val rssi: Int, val advertisesService: Boolean)

/** The phone's Bluetooth adapter. */
interface GattRadio {
    fun availability(): Radio
    /** Starts connecting to [address]; callbacks go to [sink]. */
    fun connect(address: String, sink: GattSink): GattPort
    /** Starts a scan and returns what stops it. [onFailed] reports a scan the stack wouldn't run. */
    fun scan(spec: ScanSpec, onHit: (ScanHit) -> Unit, onFailed: (Int) -> Unit): AutoCloseable
}

/** A BedJet found by [GattLink.discover]. */
data class FoundBedJet(val address: String, val name: String?, val rssi: Int)

/** The success status every GATT callback reports when it worked. */
const val GATT_SUCCESS = 0
