package io.github.willzy12h.nocturne.core.link

import io.github.willzy12h.nocturne.core.protocol.DeviceStatus
import kotlinx.coroutines.flow.StateFlow

enum class LinkState { BLUETOOTH_OFF, UNAUTHORIZED, SEARCHING, CONNECTING, READY }

/** A connection to one BedJet 3. The Android transport talks to hardware; [DemoBedJet] simulates a unit. */
interface BedJetLink {
    val state: StateFlow<LinkState>
    /** The most recent status the unit reported. */
    val status: StateFlow<DeviceStatus?>

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
