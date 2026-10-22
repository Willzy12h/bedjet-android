package io.github.willzy12h.nocturne.ble

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A scripted Bluetooth adapter. Callbacks arrive on one background thread, as GATT callbacks arrive on a binder
 * thread. By default each connection behaves like a healthy BedJet; tests change [behaviour] to break it.
 */
class FakeRadio {
    var availability = Radio.READY
    val connects = CopyOnWriteArrayList<String>()
    val ports = CopyOnWriteArrayList<FakePort>()
    val scans = CopyOnWriteArrayList<ScanSpec>()
    /** What each scan reports, by kind of scan. */
    var advertisements: Map<ScanSpec, List<ScanHit>> = emptyMap()
    /** How the next connections behave; the list is consumed in order, then [behaviour] applies. */
    val upcoming = ArrayDeque<Behaviour>()
    var behaviour = Behaviour()
    /** Thrown by the next connect or scan instead of starting it. */
    @Volatile var connectThrows: RuntimeException? = null
    @Volatile var scanThrows: RuntimeException? = null
    private val binder = Executors.newSingleThreadExecutor()

    data class Behaviour(
        /** The status of the connection callback; non-zero (such as 133) fails it. Null never answers. */
        val connectStatus: Int? = GATT_SUCCESS,
        val answerMtu: Boolean = true,
        val servicesStatus: Int = GATT_SUCCESS,
        val hasServices: Boolean = true,
        /** Sent once notifications are on; null sends nothing. */
        val firstStatus: ByteArray? = Status.full(programStep = 0),
        /** Delivers the connection callback before connect() has returned, as a fast stack can. */
        val connectsInline: Boolean = false,
    )

    val radio = object : GattRadio {
        override fun availability() = availability

        override fun connect(address: String, sink: GattSink): GattPort {
            connectThrows?.let { connectThrows = null; throw it }
            connects += address
            val port = FakePort(address, sink, upcoming.removeFirstOrNull() ?: behaviour)
            ports += port
            port.behaviour.connectStatus?.let { status ->
                val answer = { sink.onConnectionStateChange(status, status == GATT_SUCCESS) }
                if (port.behaviour.connectsInline) answer() else post(answer)
            }
            return port
        }

        override fun scan(spec: ScanSpec, onHit: (ScanHit) -> Unit, onFailed: (Int) -> Unit): AutoCloseable {
            scanThrows?.let { scanThrows = null; throw it }
            scans += spec
            advertisements[spec].orEmpty().forEach { hit -> post { onHit(hit) } }
            return AutoCloseable { }
        }
    }

    fun post(block: () -> Unit) {
        binder.execute(block)
    }

    /** Waits until every callback posted so far has run. */
    fun drain() {
        binder.submit { }.get(2, TimeUnit.SECONDS)
    }

    inner class FakePort(val address: String, val sink: GattSink, val behaviour: Behaviour) : GattPort {
        @Volatile var closed = false
        @Volatile var disconnected = false
        val writes = CopyOnWriteArrayList<ByteArray>()
        val reads = CopyOnWriteArrayList<Attr>()
        @Volatile var mtuRequests = 0
        /** When set, writes go out but the unit never acknowledges them. */
        @Volatile var holdWrites = false
        /** When set, status reads go out but are answered only by the test. */
        @Volatile var holdStatusReads = true
        /** Thrown by the next write instead of starting it. */
        @Volatile var writeThrows: RuntimeException? = null
        /** Thrown by every status read instead of starting it. */
        @Volatile var statusReadThrows: RuntimeException? = null
        private var lastQuery: ByteArray? = null

        override fun requestMtu(mtu: Int): Boolean {
            mtuRequests++
            if (behaviour.answerMtu) post { sink.onMtuChanged(mtu, GATT_SUCCESS) }
            return true
        }

        override fun discoverServices(): Boolean {
            post { sink.onServicesDiscovered(behaviour.servicesStatus) }
            return true
        }

        override fun resolve(): Boolean = behaviour.hasServices

        override fun write(attr: Attr, data: ByteArray): Boolean {
            writeThrows?.let { writeThrows = null; throw it }
            writes += data
            if (attr == Attr.COMMAND && data[0].toInt() == 0x41) lastQuery = data.copyOfRange(1, 3)
            if (!holdWrites) post { sink.onWrite(attr, GATT_SUCCESS) }
            return true
        }

        override fun read(attr: Attr): Boolean {
            if (attr == Attr.STATUS) statusReadThrows?.let { throw it }
            reads += attr
            when (attr) {
                Attr.REPLY -> {
                    val echo = lastQuery ?: byteArrayOf(0, 0)
                    post { sink.onRead(attr, echo + "BEDJET 3".toByteArray() + 0, GATT_SUCCESS) }
                }
                Attr.STATUS -> if (!holdStatusReads) post { sink.onRead(attr, Status.remainder(programStep = 0), GATT_SUCCESS) }
                else -> Unit
            }
            return true
        }

        override fun enableNotifications(): NotifyStart {
            post {
                sink.onDescriptorWrite(Attr.STATUS, cccd = true, status = GATT_SUCCESS)
                behaviour.firstStatus?.let { sink.onNotify(Attr.STATUS, it) }
            }
            return NotifyStart.STARTED
        }

        override fun disconnect() {
            disconnected = true
        }

        override fun close() {
            closed = true
        }

        fun notify(value: ByteArray) = post { sink.onNotify(Attr.STATUS, value) }
        fun answerStatusRead(value: ByteArray) = post { sink.onRead(Attr.STATUS, value, GATT_SUCCESS) }
        fun ackWrite(attr: Attr = Attr.COMMAND, status: Int = GATT_SUCCESS) = post { sink.onWrite(attr, status) }
        fun drop(status: Int = GATT_SUCCESS) = post { sink.onConnectionStateChange(status, false) }
        fun connected() = post { sink.onConnectionStateChange(GATT_SUCCESS, true) }
    }
}

/** Status packets laid out as a BedJet 3 sends them (see CodecTest). */
object Status {
    private val HEAD = hex("01 56 1b 01 00 00 00 2e 4f 00 13 00 00 14 50 00 00 2d 00 12")

    /** The first 20 bytes, which is all a notification carries at the default packet size. */
    fun head(): ByteArray = HEAD.copyOf()

    /** Bytes 20 onwards, served by a read of the status characteristic. */
    fun remainder(programStep: Int, notification: Int = 0): ByteArray {
        val rest = hex("01 9a 01 10 10 00 15 35 00 00 53")
        rest[8] = programStep.toByte()
        rest[9] = notification.toByte()
        return rest
    }

    fun full(programStep: Int): ByteArray = head() + remainder(programStep)

    private fun hex(text: String) = text.split(" ").map { it.toInt(16).toByte() }.toByteArray()
}
