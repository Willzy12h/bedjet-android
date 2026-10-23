package io.github.willzy12h.nocturne.core.link

import io.github.willzy12h.nocturne.core.protocol.DeviceStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map

enum class LinkState { BLUETOOTH_OFF, UNAUTHORIZED, SEARCHING, CONNECTING, READY }

/**
 * A status and the [DeviceId] of the unit that reported it. A null [source] comes from a link that only ever talks
 * to one unit, such as [DemoBedJet].
 */
data class SourcedStatus(val source: String?, val status: DeviceStatus) {
    /** Whether this report may stand for [target]'s state: a report from another BedJet never does. */
    fun isFrom(target: String?): Boolean = source == null || DeviceId.same(source, target)
}

/** A connection to one BedJet 3. The Android transport talks to hardware; [DemoBedJet] simulates a unit. */
interface BedJetLink {
    val state: StateFlow<LinkState>
    /** The most recent status the unit reported. */
    val status: StateFlow<DeviceStatus?>
    /** [status] with the unit that reported it. A link that can reach more than one BedJet must override this. */
    val sourcedStatus: Flow<SourcedStatus?> get() = status.map { it?.let { s -> SourcedStatus(null, s) } }
    /** The latest [sourcedStatus], at once. Overridden together with [sourcedStatus]. */
    fun latestSourcedStatus(): SourcedStatus? = status.value?.let { SourcedStatus(null, it) }

    /** Suspends until the unit is ready for commands, or throws [BedJetError.NotConnected]. */
    suspend fun connect()
    suspend fun disconnect()
    /** Writes a command and waits for the unit to acknowledge it. */
    suspend fun send(command: ByteArray)
    /** Checks a start's deadline at the transport write boundary, after reconnect and queue wait. */
    suspend fun sendGuarded(command: ByteArray, beforeWrite: () -> Unit) {
        beforeWrite()
        send(command)
    }
    /** Asks the unit for stored data and returns the reply payload, without the echoed request and tag. */
    suspend fun query(request: Int): ByteArray
    /**
     * Called before a program start is sent: forget the program progress last reported, so only a report that
     * arrives afterwards can confirm the start. Links without split status reports need do nothing.
     */
    suspend fun prepareForStart() {}
    /** Releases preparation if the start is withheld or interrupted before the link completes its write. */
    fun cancelPreparedStart() {}
}

/**
 * The latest status that [target] itself reported, or null when there is none or the last report came from another
 * BedJet (a session still on the previous target).
 */
fun BedJetLink.latestStatusFrom(target: String?): DeviceStatus? = latestSourcedStatus()?.takeIf { it.isFrom(target) }?.status

sealed class BedJetError(message: String) : Exception(message) {
    class NotConnected(
        message: String = "Couldn't reach your BedJet. Check that it's plugged in and nearby.",
    ) : BedJetError(message)
    class Timeout : BedJetError("Your BedJet didn't respond. Move closer and try again.")
    class BadResponse : BedJetError("Your BedJet sent something unexpected. Try again.")
    class VerifyFailed : BedJetError("The night didn't save to your BedJet correctly. Try again.")
    class ClockNotSet : BedJetError("Your BedJet's clock wasn't set, so it didn't start the night. Try again.")
    class ProgramTooLong : BedJetError("Your BedJet won't run a phase this long. Shorten the Heat phases or use Gentle heat.")
    class InvalidProgram(message: String) : BedJetError(message)
}
