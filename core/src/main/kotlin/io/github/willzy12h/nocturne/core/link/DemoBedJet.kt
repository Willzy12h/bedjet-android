package io.github.willzy12h.nocturne.core.link

import io.github.willzy12h.nocturne.core.protocol.Codec
import io.github.willzy12h.nocturne.core.protocol.DeviceStatus
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.protocol.ProgramStep
import io.github.willzy12h.nocturne.core.protocol.StoredProgram
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * A simulated BedJet 3 that understands the same bytes as the real one, ported from rkanjani/lull's DemoLink.
 * Time moves when [tick] is called, so the app drives it once a second and tests drive it by hand.
 */
class DemoBedJet(
    initialSlots: List<StoredProgram?> = listOf(null, null, null),
    private val now: () -> ZonedDateTime = { ZonedDateTime.now() },
) : BedJetLink {
    private val _state = MutableStateFlow(LinkState.SEARCHING)
    override val state: StateFlow<LinkState> = _state.asStateFlow()
    private val _status = MutableStateFlow<DeviceStatus?>(null)
    override val status: StateFlow<DeviceStatus?> = _status.asStateFlow()
    private val _sourcedStatus = MutableStateFlow<SourcedStatus?>(null)
    override val sourcedStatus: StateFlow<SourcedStatus?> = _sourcedStatus.asStateFlow()
    override fun latestSourcedStatus(): SourcedStatus? = _sourcedStatus.value
    /** The full unit report survives local progress masking without receiving a new report. */
    private var lastReceived: SourcedStatus? = null

    val slots: MutableList<StoredProgram?> = initialSlots.toMutableList()
    /** The unit's clock as last set, as hour to minute. */
    var clock: Pair<Int, Int>? = null
        private set
    private var clockSetAt: ZonedDateTime? = null

    private var current = DeviceStatus(
        remainingSeconds = 0, actualTemperature = 46, targetTemperature = 60, mode = Mode.STANDBY, fanStep = 7,
        maxRuntimeMinutes = 0, minTemperature = 38, maxTemperature = 80, ambientTemperature = 45, lastShutdownReason = 0,
        extra = DeviceStatus.Extra(ledEnabled = true, beepsMuted = false, programStep = 0, notification = 0),
    )
    private data class Run(val slot: Int, val step: Int, val stepEnds: ZonedDateTime)
    private var running: Run? = null
    private var manualEnds: ZonedDateTime? = null
    private val pendingSteps = mutableMapOf<Int, List<ProgramStep>>()
    private var lastTick: ZonedDateTime? = null

    override suspend fun connect() {
        val newConnection = _state.value != LinkState.READY
        _state.value = LinkState.READY
        publish(newReceipt = newConnection)
    }

    override suspend fun disconnect() {
        _state.value = LinkState.SEARCHING
    }

    /** StateFlow conflates equal values: forget progress so even an identical first step confirms freshly. */
    override suspend fun prepareForStart() {
        val prepared = _sourcedStatus.value?.let { it.copy(status = it.status.copy(extra = null)) }
        _sourcedStatus.value = prepared
        _status.value = prepared?.status
    }

    override suspend fun send(command: ByteArray) {
        if (_state.value != LinkState.READY) throw BedJetError.NotConnected()
        val b = command.map { it.toInt() and 0xff }
        if (b.size < 2) throw BedJetError.BadResponse()
        val previousRun = running
        when (b[0]) {
            0x01 -> press(b[1])
            0x02 -> if (b.size >= 3) {
                val minutes = minOf(b[1] * 60 + b[2], current.maxRuntimeMinutes)
                current = current.copy(remainingSeconds = minutes * 60)
                if (running == null) manualEnds = now().plusMinutes(minutes.toLong())
            }
            0x03 -> { current = current.copy(targetTemperature = b[1]); limitHeat() }
            0x07 -> { current = current.copy(fanStep = b[1]); limitHeat() }
            0x08 -> if (b.size >= 3) { clock = b[1] to b[2]; clockSetAt = now() }
            0x40 -> store(b)
        }
        // A successful restart receives a report even when it reproduces the same first step.
        publish(newReceipt = running != null && running !== previousRun)
    }

    override suspend fun query(request: Int): ByteArray {
        if (_state.value != LinkState.READY) throw BedJetError.NotConnected()
        return when (request) {
            Codec.Query.DEVICE_NAME -> "BEDJET-DEMO".toByteArray() + 0
            Codec.Query.FIRMWARE -> "V1.20 (demo)".toByteArray() + 0
            Codec.Query.PROGRAM_NAMES -> slots.fold(ByteArray(0)) { acc, program ->
                val slot = ByteArray(16)
                if (program == null) slot[0] = 1
                else Codec.sanitize(program.name).toByteArray().copyInto(slot)
                acc + slot
            }
            in 7 until 19 -> {
                val slot = (request - 7) / 4
                val index = (request - 7) % 4
                val steps = slots[slot]?.steps.orEmpty()
                val fragments = Codec.writeProgram(slot, steps)
                if (index < fragments.size) fragments[index].copyOfRange(2, 43) else byteArrayOf(steps.size.toByte())
            }
            else -> throw BedJetError.BadResponse()
        }
    }

    /** Moves the simulation to the present: runs the program on, counts the timer down, drifts the air temperature. */
    fun tick() {
        val time = now()
        while (true) {
            val run = running ?: break
            if (time.isBefore(run.stepEnds)) break
            advance(run.stepEnds)
        }
        manualEnds?.let { if (running == null && !time.isBefore(it)) { manualEnds = null; enter(Mode.STANDBY, 0) } }
        val ends = running?.stepEnds ?: manualEnds
        if (ends != null) current = current.copy(remainingSeconds = maxOf(0, Duration.between(time, ends).seconds.toInt()))
        val seconds = lastTick?.let { Duration.between(it, time).seconds } ?: 1
        lastTick = time
        val goal = if (current.mode == Mode.STANDBY || current.mode == Mode.WAIT) current.ambientTemperature else current.targetTemperature
        val drift = (goal - current.actualTemperature).coerceIn(-seconds.toInt().coerceAtLeast(1), seconds.toInt().coerceAtLeast(1))
        current = current.copy(actualTemperature = current.actualTemperature + drift)
        publish()
    }

    private fun press(button: Int) {
        val extra = current.extra!!
        when (button) {
            in 0x01..0x06 -> {
                running = null
                val mode = Mode.entries.first { it.button == button }
                val minutes = when (mode) { Mode.STANDBY -> 0; Mode.TURBO -> 10; else -> 60 }
                enter(mode, minutes)
                current = current.copy(
                    maxRuntimeMinutes = when (mode) { Mode.STANDBY -> 0; Mode.TURBO -> 10; Mode.HEAT -> 240; else -> 720 },
                    extra = current.extra!!.copy(programStep = 0),
                )
                manualEnds = if (mode == Mode.STANDBY) null else now().plusMinutes(minutes.toLong())
                limitHeat()
            }
            0x46, 0x47 -> current = current.copy(extra = extra.copy(ledEnabled = button == 0x46))
            0x48, 0x49 -> current = current.copy(extra = extra.copy(beepsMuted = button == 0x48))
            0x52 -> current = current.copy(extra = extra.copy(notification = 0))
            in 0x80..0x82 -> {
                val program = slots[button - 0x80] ?: return
                // Like the real unit, refuse to start rather than stop partway through the night.
                val refusal = when {
                    clock == null -> Codec.Notification.PROGRAM_NEEDS_CLOCK
                    !program.steps.all { it.fitsUnitLimits } -> Codec.Notification.PROGRAM_TOO_LONG
                    else -> null
                }
                if (refusal != null) {
                    current = current.copy(extra = extra.copy(notification = refusal))
                    return
                }
                manualEnds = null
                running = Run(button - 0x80, -1, now())
                advance(now())
            }
            in 0x83..0x85 -> slots[button - 0x83] = null
        }
    }

    private fun advance(from: ZonedDateTime) {
        val run = running ?: return
        val program = slots[run.slot] ?: return
        val next = run.step + 1
        if (next >= program.steps.size) {
            running = null
            enter(Mode.STANDBY, 0)
            current = current.copy(extra = current.extra!!.copy(programStep = 0))
            return
        }
        val step = program.steps[next]
        val ends = if (step.endsAtClockTime) endOnUnitClock(from, step) else from.plusMinutes(step.durationMinutes.toLong())
        running = Run(run.slot, next, ends)
        current = current.copy(fanStep = step.fanStep, targetTemperature = step.temperature, extra = current.extra!!.copy(programStep = next + 1))
        enter(step.mode, Duration.between(from, ends).toMinutes().toInt())
    }

    /** A clock-time step ends when the unit's own clock, which counts on from when it was set, reaches that time. */
    private fun endOnUnitClock(from: ZonedDateTime, step: ProgramStep): ZonedDateTime {
        val (hour, minute) = clock ?: return from.plusMinutes(step.durationMinutes.toLong())
        val unitNow = LocalTime.of(hour, minute).plusSeconds(Duration.between(clockSetAt, from).seconds)
        var minutes = Duration.between(unitNow, LocalTime.of(step.hours, step.minutes)).toMinutes()
        if (minutes <= 0) minutes += 24 * 60
        return from.plusMinutes(minutes)
    }

    private fun enter(mode: Mode, minutes: Int) {
        val range = mode.temperatureRange
        current = current.copy(
            mode = mode,
            remainingSeconds = minutes * 60,
            maxRuntimeMinutes = minutes,
            minTemperature = if (mode.usesTemperature) range.first else 20,
            maxTemperature = if (mode.usesTemperature) range.last else 80,
            targetTemperature = if (current.targetTemperature in range) current.targetTemperature else range.first,
        )
    }

    /** Hot air at a high fan is cut off sooner, so manual Heat's ceiling follows temperature and fan. */
    private fun limitHeat() {
        if (current.mode != Mode.HEAT || running != null) return
        val longest = minOf(240, ProgramStep.longestMinutes(Mode.HEAT, current.targetTemperature, current.fanStep))
        current = current.copy(maxRuntimeMinutes = longest, remainingSeconds = minOf(current.remainingSeconds, longest * 60))
    }

    private fun store(b: List<Int>) {
        if (b.size < 3) return
        val target = b[1]
        if (target in 4..6) {
            val slot = target - 4
            val name = Codec.parseText(b.drop(3).map { it.toByte() }.toByteArray())
            val steps = pendingSteps.remove(slot)
            slots[slot] = if (steps != null) StoredProgram(name, steps) else slots[slot]?.copy(name = name)
        } else if (target in 7 until 19) {
            val slot = (target - 7) / 4
            val index = (target - 7) % 4
            val fragment = Codec.parseFragment(b.drop(2).map { it.toByte() }.toByteArray(), index) ?: return
            val steps = (if (index == 0) emptyList() else pendingSteps[slot].orEmpty()) + fragment.steps
            pendingSteps[slot] = steps
            if (steps.size >= fragment.total) slots[slot] = StoredProgram(slots[slot]?.name.orEmpty(), steps)
        }
    }

    private fun publish(newReceipt: Boolean = false) {
        val shown = if (_state.value == LinkState.READY) current else null
        val before = lastReceived
        lastReceived = when {
            shown == null -> null
            before == null || shown != before.status -> SourcedStatus(null, shown)
            newReceipt -> before.copy(receiptOrder = StatusReceiptOrder.next())
            else -> before
        }
        _sourcedStatus.value = lastReceived
        _status.value = lastReceived?.status
    }

}
