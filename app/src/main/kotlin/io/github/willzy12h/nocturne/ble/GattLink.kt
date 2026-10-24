package io.github.willzy12h.nocturne.ble

import io.github.willzy12h.nocturne.core.link.BedJetError
import io.github.willzy12h.nocturne.core.link.BedJetLink
import io.github.willzy12h.nocturne.core.link.DeviceId
import io.github.willzy12h.nocturne.core.link.LinkState
import io.github.willzy12h.nocturne.core.link.SourcedStatus
import io.github.willzy12h.nocturne.core.protocol.Codec
import io.github.willzy12h.nocturne.core.protocol.DeviceStatus
import io.github.willzy12h.nocturne.core.protocol.Limits
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * The BedJet 3 connection logic, independent of Android's Bluetooth classes (which sit behind [GattRadio]).
 *
 * Each connection attempt is a [Session] that owns its GATT handle, the operation waiting for an answer, the split
 * status and its background work. Retiring a session closes all of that at once, so nothing from an old
 * connection can answer a new one. The BedJet handles one request at a time, so every operation goes through
 * [operations].
 */
class GattLink(
    private val radio: GattRadio,
    private val target: () -> String?,
    private val timings: Timings = Timings(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val onName: (String, String) -> Unit = { _, _ -> },
    private val onVerifiedName: (String, String?) -> Unit = { _, _ -> },
    /** What to say when no BedJet is chosen; the app passes its resource, Context-free callers keep [NO_TARGET]. */
    private val noTargetMessage: () -> String = { NO_TARGET },
) : BedJetLink {
    data class Timings(
        val operationMs: Long = 5_000,
        val connectMs: Long = 12_000,
        val mtuFallbackMs: Long = 1_500,
        val firstStatusMs: Long = 3_000,
        /** The pause before each retry of a failed connection; its size bounds the retries. */
        val retryBackoffMs: List<Long> = listOf(500, 1_500),
        val queryRetryMs: Long = 150,
        val remainderEveryMs: Long = 2_000,
    )

    private val _state = MutableStateFlow(LinkState.SEARCHING)
    override val state: StateFlow<LinkState> = _state.asStateFlow()
    private val _status = MutableStateFlow<DeviceStatus?>(null)
    override val status: StateFlow<DeviceStatus?> = _status.asStateFlow()
    private val _sourcedStatus = MutableStateFlow<SourcedStatus?>(null)
    /** [status] with the BedJet whose session reported it, so a report from a previous target is never taken for the new one's. */
    override val sourcedStatus: StateFlow<SourcedStatus?> = _sourcedStatus.asStateFlow()
    override fun latestSourcedStatus(): SourcedStatus? = _sourcedStatus.value

    private val operations = Mutex()
    private val connecting = Mutex()
    @Volatile private var current: Session? = null
    /** Set by [connect], cleared by [disconnect]: whether an operation may reconnect a retired session. */
    @Volatile private var wanted = false
    /** Progress from a previous programme must not confirm a newly prepared start, even across reconnects. */
    @Volatile private var startPending = false
    /** The session [prepareForStart] ran on: the only one a start may go out on. */
    @Volatile private var preparedSession: Session? = null
    private val startStatusLock = Any()
    private var tag = 0

    override suspend fun connect() {
        wanted = true
        connecting.withLock {
            val address = DeviceId.address(target()) ?: run {
                current?.let(::retire)
                _state.value = LinkState.SEARCHING
                throw BedJetError.NotConnected(noTargetMessage())
            }
            if (current?.takeIf { it.ready && it.address == address } != null) return
            current?.let(::retire)
            checkRadio()
            // A failed connection (133 is the stack's catch-all) or one that never answers often works on a
            // second try. Only the chosen BedJet is ever tried.
            for (attempt in 0..timings.retryBackoffMs.size) {
                if (attempt > 0) delay(timings.retryBackoffMs[attempt - 1])
                if (!wanted) break
                // A selection change invalidates this entire attempt, including its retry address.
                if (!selected(address)) throw BedJetError.NotConnected()
                if (attach(address, readVerifiedName = attempt == 0)) return
            }
            _state.value = LinkState.SEARCHING
            throw BedJetError.NotConnected()
        }
    }

    override suspend fun disconnect() {
        wanted = false
        operations.withLock { current?.let(::retire) }
    }

    override suspend fun send(command: ByteArray) = sendGuarded(command) {}

    /**
     * Writes [command] once [beforeWrite] passes, at the write boundary. A program start (told apart by its bytes,
     * see [isStart]) never reconnects: it goes out only on the live session [prepareForStart] ran on, or fails with
     * [BedJetError.NotConnected] before [beforeWrite] runs. A reconnect could use up the time its caller has left,
     * and a new session's first report could be taken for its confirmation.
     */
    override suspend fun sendGuarded(command: ByteArray, beforeWrite: () -> Unit) {
        if (isStart(command)) return sendStart(command, beforeWrite)
        ensureConnected()
        operations.withLock {
            val session = live()
            beforeWrite()
            write(session, command)
        }
    }

    private suspend fun sendStart(command: ByteArray, beforeWrite: () -> Unit) = operations.withLock {
        val session = live()
        if (!startPending || preparedSession !== session) throw BedJetError.NotConnected()
        beforeWrite()
        // Only reports that arrive after the start may confirm it: an older program step must not.
        session.forgetExtra()
        write(session, command)
        synchronized(startStatusLock) { startPending = false; preparedSession = null }
    }

    override suspend fun query(request: Int): ByteArray {
        ensureConnected()
        return operations.withLock { ask(live(), request) }
    }

    /**
     * Forgets the program progress last reported, now and for head-only reports, until a fresh full status or
     * remainder read arrives, so an older program step can't confirm the start that follows.
     */
    override suspend fun prepareForStart() {
        operations.withLock {
            synchronized(startStatusLock) {
                startPending = true
                preparedSession = current
                current?.forgetExtra()
                publish(current, _status.value?.copy(extra = null))
            }
        }
    }

    /** Ends a preparation whose start will not be written; the next head-only report reads the remainder at once. */
    override fun cancelPreparedStart() {
        synchronized(startStatusLock) {
            startPending = false
            preparedSession = null
            current?.lastRemainderRead = null
        }
    }

    /**
     * Lists the BedJets advertising nearby, strongest first, without connecting to any: first by the BedJet service
     * (which works with the screen off), then by name for units that advertise only that.
     */
    suspend fun discover(timeoutMs: Long): List<FoundBedJet> {
        checkRadio()
        val found = linkedMapOf<String, FoundBedJet>()
        fun keep(hit: ScanHit, matches: Boolean) {
            if (!matches) return
            synchronized(found) {
                val seen = found[hit.address]
                if (seen == null || hit.rssi > seen.rssi) found[hit.address] = FoundBedJet(hit.address, hit.name ?: seen?.name, hit.rssi)
            }
        }
        scanFor(ScanSpec.Service, timeoutMs / 2) { keep(it, true) }
        scanFor(ScanSpec.Unfiltered, timeoutMs - timeoutMs / 2) {
            keep(it, it.advertisesService || it.name.orEmpty().uppercase().startsWith("BEDJET"))
        }
        return synchronized(found) { found.values.sortedByDescending { it.rssi } }
    }

    /** Closes the connection at once, without waiting for an operation to finish; for tests. */
    fun shutdown() {
        current?.let(::retire)
    }

    private fun checkRadio() {
        when (platform(null) { radio.availability() }) {
            Radio.UNAUTHORIZED -> { _state.value = LinkState.UNAUTHORIZED; throw BedJetError.NotConnected() }
            Radio.OFF -> { _state.value = LinkState.BLUETOOTH_OFF; throw BedJetError.NotConnected() }
            Radio.READY -> Unit
        }
    }

    private suspend fun scanFor(spec: ScanSpec, patienceMs: Long, onHit: (ScanHit) -> Unit) {
        val failed = CompletableDeferred<Unit>()
        val handle = platform(null) { radio.scan(spec, onHit) { failed.complete(Unit) } }
        try {
            withTimeoutOrNull(patienceMs) { failed.await() }
        } finally {
            runCatching { handle.close() }
        }
    }

    // Connection

    private suspend fun ensureConnected() {
        if (current?.takeIf { it.ready && selected(it.address) } != null) return
        if (!wanted) throw BedJetError.NotConnected()
        connect()
    }

    private fun selected(address: String): Boolean = DeviceId.address(target()) == address

    private fun live(): Session {
        val session = current?.takeIf { it.ready } ?: throw BedJetError.NotConnected()
        if (!selected(session.address)) {
            retire(session)
            throw BedJetError.NotConnected()
        }
        return session
    }

    /**
     * One connection attempt. True once the unit is ready. On false, a throw or cancellation the session is retired,
     * so nothing is left open.
     */
    private suspend fun attach(address: String, readVerifiedName: Boolean): Boolean {
        current?.let(::retire)
        val session = Session(address)
        current = session
        // A new session invalidates old interop metadata, including after a name-read timeout.
        onVerifiedName(DeviceId.ble(address), null)
        publish(null, null)
        _state.value = LinkState.CONNECTING
        var attached = false
        try {
            session.attachPort(platform(session) { radio.connect(address, session) })
            val discovered = withTimeoutOrNull(timings.connectMs) { session.services.await() } ?: false
            // Notifications must be switched on before the unit streams its status; without them it never reports.
            if (!discovered || !operations.withLock { enableNotifications(session) }) return false
            session.ready = true
            _state.value = LinkState.READY
            try {
                operations.withLock {
                    val name = Codec.parseText(ask(session, Codec.Query.DEVICE_NAME))
                    if (current === session && wanted && session.ready) onName(DeviceId.ble(session.address), name)
                }
                // Optional Lull identity comes from characteristic 2001, never the display query.
                // An in-flight timeout still retires this session. Only this connect call's later retry
                // skips metadata, allowing ordinary control on a fresh session with interop unknown.
                if (readVerifiedName && session.port().has(Attr.NAME)) operations.withLock {
                    val name = Codec.parseText(exchange(session, Kind.READ, Attr.NAME) { it.read(Attr.NAME) })
                    if (current === session && wanted && session.ready)
                        onVerifiedName(DeviceId.ble(session.address), name.takeIf { it.isNotEmpty() })
                }
            } catch (e: BedJetError) {
                // Metadata is optional, but exchange failures still retire the session.
                // The current-session check below decides whether this attempt remains usable.
            }
            withTimeoutOrNull(timings.firstStatusMs) { status.first { it != null } }
            // The name query can time out, the unit can drop or disconnect() can land meanwhile, each retiring
            // the session: only a session that is still current and still wanted counts as connected.
            attached = wanted && selected(address) && current === session && session.ready
            return attached
        } finally {
            if (!attached) retire(session)
        }
    }

    /**
     * Every status goes out with the session that reported it; see [sourcedStatus]. Without a session there is no
     * status, so [status] is never left set while [sourcedStatus] is empty.
     */
    private fun publish(session: Session?, value: DeviceStatus?) {
        val shown = value?.takeIf { session != null }
        _sourcedStatus.value = if (session == null || shown == null) null else SourcedStatus(DeviceId.ble(session.address), shown)
        _status.value = shown
    }

    private fun retire(session: Session) {
        val wasCurrent = synchronized(this) {
            (current === session).also { if (it) current = null }
        }
        session.shut()
        if (wasCurrent) {
            publish(null, null)
            if (_state.value == LinkState.READY || _state.value == LinkState.CONNECTING) _state.value = LinkState.SEARCHING
        }
    }

    // Operations

    private suspend fun write(session: Session, data: ByteArray) {
        exchange(session, Kind.WRITE, Attr.COMMAND) { it.write(Attr.COMMAND, data) }
    }

    private suspend fun ask(session: Session, request: Int): ByteArray {
        // The reply characteristic keeps its previous contents until the unit has answered,
        // so retry until the echoed request and tag match.
        repeat(4) { attempt ->
            tag = if (tag >= 255) 1 else tag + 1
            write(session, Codec.query(request, tag))
            if (attempt > 0) delay(timings.queryRetryMs)
            val data = exchange(session, Kind.READ, Attr.REPLY) { it.read(Attr.REPLY) }
            if (data.size >= 2 && (data[0].toInt() and 0xff) == request && (data[1].toInt() and 0xff) == tag) {
                return data.copyOfRange(2, data.size)
            }
        }
        throw BedJetError.BadResponse()
    }

    private suspend fun enableNotifications(session: Session): Boolean {
        val pending = Pending(Kind.DESCRIPTOR, Attr.STATUS)
        session.pending.set(pending)
        try {
            return when (platform(session) { session.port().enableNotifications() }) {
                NotifyStart.REFUSED -> false
                NotifyStart.DONE -> true
                // A timeout here fails the attach, which retires the session.
                NotifyStart.STARTED -> withTimeoutOrNull(timings.operationMs) { pending.result.await() } != null
            }
        } catch (e: BedJetError.BadResponse) {
            return false
        } finally {
            session.pending.compareAndSet(pending, null)
        }
    }

    /**
     * Starts one request and waits for its own answer. A request that times out or is cancelled while in flight
     * retires the session: its late answer couldn't be told apart from the next request's, so the next operation
     * reconnects instead.
     */
    private suspend fun exchange(session: Session, kind: Kind, attr: Attr, start: (GattPort) -> Boolean): ByteArray {
        val pending = Pending(kind, attr)
        session.pending.set(pending)
        try {
            if (!selected(session.address)) {
                retire(session)
                throw BedJetError.NotConnected()
            }
            if (!platform(session) { start(session.port()) }) throw BedJetError.NotConnected()
            return withTimeoutOrNull(timings.operationMs) { pending.result.await() } ?: run {
                retire(session)
                throw BedJetError.Timeout()
            }
        } catch (e: CancellationException) {
            retire(session)
            throw e
        } finally {
            session.pending.compareAndSet(pending, null)
        }
    }

    /**
     * Notifications carry the live readings. When the packet size is too small for the whole status, the unit sends
     * the first part and serves the remainder (program progress, LED and beep settings) to a read of the same
     * characteristic.
     */
    private fun receiveStatus(session: Session, chunk: ByteArray) {
        if (current !== session) return
        if (!selected(session.address)) return retire(session)
        if (!Codec.isStatusStart(chunk)) {
            val head = session.lastHead ?: return
            Codec.parseStatus(head + chunk)?.extra?.let { extra ->
                synchronized(startStatusLock) {
                    if (current !== session || !selected(session.address)) return
                    if (startPending) return
                    session.lastExtra = extra
                    publish(session, _status.value?.copy(extra = extra))
                }
            }
            return
        }
        val parsed = Codec.parseStatus(chunk) ?: return
        if (parsed.extra != null) {
            synchronized(startStatusLock) {
                if (current !== session || !selected(session.address)) return
                if (startPending) {
                    session.lastExtra = null
                    publish(session, parsed.copy(extra = null))
                } else {
                    session.lastExtra = parsed.extra
                    publish(session, parsed)
                }
            }
            return
        }
        synchronized(startStatusLock) {
            if (current !== session || !selected(session.address)) return
            session.lastHead = chunk
            publish(session, parsed.copy(extra = if (startPending) null else session.lastExtra))
        }
        // While a start is pending a remainder read would only be dropped, and would hold up the start.
        if (startPending) return
        val now = clock()
        val last = session.lastRemainderRead
        // Only while no operation holds the queue, not merely between its steps: the unit and the GATT stack each
        // take one request at a time. The read gives the queue back however it ends.
        if ((last == null || now - last > timings.remainderEveryMs) && operations.tryLock()) {
            session.lastRemainderRead = now
            session.scope.launch {
                try {
                    receiveStatus(session, exchange(session, Kind.READ, Attr.STATUS) { it.read(Attr.STATUS) })
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Missed this time; the next notification asks again.
                }
            }.invokeOnCompletion { operations.unlock() }
        }
    }

    /**
     * Runs a call into the Bluetooth stack. A revoked permission and any other platform failure become
     * [BedJetError.NotConnected] and retire [session], since its state can no longer be trusted.
     */
    private inline fun <T> platform(session: Session?, block: () -> T): T =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: BedJetError) {
            throw e
        } catch (e: SecurityException) {
            session?.let(::retire)
            _state.value = LinkState.UNAUTHORIZED
            throw BedJetError.NotConnected()
        } catch (e: Exception) {
            session?.let(::retire)
            throw BedJetError.NotConnected()
        }

    private fun isStart(command: ByteArray) = (0 until Limits.SLOTS).any { Codec.startProgram(it).contentEquals(command) }

    private enum class Kind { WRITE, READ, DESCRIPTOR }

    private class Pending(val kind: Kind, val attr: Attr) {
        val result = CompletableDeferred<ByteArray>()
    }

    /** One connection attempt and everything that belongs to it. Its callbacks arrive on a binder thread. */
    private inner class Session(val address: String) : GattSink {
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val services = CompletableDeferred<Boolean>()
        val pending = AtomicReference<Pending?>(null)
        @Volatile var ready = false
        @Volatile var lastHead: ByteArray? = null
        @Volatile var lastExtra: DeviceStatus.Extra? = null
        @Volatile var lastRemainderRead: Long? = null
        @Volatile private var port: GattPort? = null
        private val closed = AtomicBoolean(false)
        private val discovering = AtomicBoolean(false)
        private val negotiating = AtomicBoolean(false)
        @Volatile private var linked = false

        fun port(): GattPort = port ?: throw BedJetError.NotConnected()

        /** Program progress stays unknown until the unit reports it afresh, by a remainder read or full status. */
        fun forgetExtra() {
            // Without the head, a remainder read already under way can't be merged into a status either.
            lastHead = null
            lastExtra = null
            lastRemainderRead = null
        }

        /** Takes the handle [GattRadio.connect] returned; its connection callback may already have arrived. */
        fun attachPort(port: GattPort) {
            synchronized(this) { this.port = port }
            if (closed.get()) closePort(port) else if (linked) callback { negotiate() }
        }

        /** Closes the connection and fails whatever was waiting on it. Safe to call more than once. */
        fun shut() {
            if (!closed.compareAndSet(false, true)) return
            scope.cancel()
            services.complete(false)
            pending.getAndSet(null)?.result?.completeExceptionally(BedJetError.NotConnected())
            synchronized(this) { port }?.let(::closePort)
        }

        private fun closePort(port: GattPort) {
            runCatching { port.disconnect() }
            runCatching { port.close() }
        }

        override fun onConnectionStateChange(status: Int, connected: Boolean) = callback {
            // A "connected" with an error status (often 133) is a failed connection too.
            if (status != GATT_SUCCESS || !connected) return@callback retire(this)
            linked = true
            if (port != null) negotiate()
        }

        /** Runs once per connection, when it is up and its handle known. */
        private fun negotiate() {
            if (!negotiating.compareAndSet(false, true)) return
            // A larger packet size lets the whole status arrive in one notification. Some stacks never answer the
            // request, so discover anyway if it hasn't completed shortly.
            if (platform(this) { port().requestMtu(MTU) }) {
                scope.launch { delay(timings.mtuFallbackMs); callback { discoverServices() } }
            } else {
                discoverServices()
            }
        }

        override fun onMtuChanged(mtu: Int, status: Int) = callback { discoverServices() }

        override fun onServicesDiscovered(status: Int) = callback {
            services.complete(status == GATT_SUCCESS && platform(this) { port().resolve() })
        }

        override fun onWrite(attr: Attr, status: Int) = callback { answer(Kind.WRITE, attr, status, ByteArray(0)) }

        override fun onRead(attr: Attr, value: ByteArray, status: Int) = callback { answer(Kind.READ, attr, status, value) }

        override fun onDescriptorWrite(attr: Attr, cccd: Boolean, status: Int) = callback {
            answer(Kind.DESCRIPTOR, if (cccd) attr else Attr.OTHER, status, ByteArray(0))
        }

        override fun onNotify(attr: Attr, value: ByteArray) = callback {
            if (attr == Attr.STATUS) receiveStatus(this, value)
        }

        /**
         * Runs a callback only for the current session; an old one's GATT is closed instead. A platform failure
         * inside has already retired the session, so there is nothing more to do with it here.
         */
        private inline fun callback(block: () -> Unit) {
            if (current !== this) return shut()
            try {
                block()
            } catch (e: BedJetError) {
                // Already handled by platform().
            }
        }

        /** Completes the waiting operation with its own answer. Anything else fails it, as a mismatch does. */
        private fun answer(kind: Kind, attr: Attr, status: Int, value: ByteArray) {
            val waiting = pending.get() ?: return
            if (!pending.compareAndSet(waiting, null)) return
            when {
                waiting.kind != kind || waiting.attr != attr -> {
                    // This operation's own answer may still arrive, and would be taken for the next one's. Retire
                    // before failing the operation, so nothing can run on this session in between.
                    retire(this)
                    waiting.result.completeExceptionally(BedJetError.BadResponse())
                }
                status != GATT_SUCCESS -> waiting.result.completeExceptionally(BedJetError.BadResponse())
                else -> waiting.result.complete(value)
            }
        }

        /** Starts service discovery once per connection, on the MTU answer or its fallback, whichever comes first. */
        private fun discoverServices() {
            if (!discovering.compareAndSet(false, true)) return
            // Refused while the MTU exchange is still running; its answer then tries again.
            if (!platform(this) { port().discoverServices() }) discovering.set(false)
        }
    }

    companion object {
        const val NO_TARGET = "No BedJet chosen yet. Choose yours in Setup."
        private const val MTU = 247
    }
}
