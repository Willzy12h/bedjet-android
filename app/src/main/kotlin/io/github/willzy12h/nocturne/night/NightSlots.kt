package io.github.willzy12h.nocturne.night

import io.github.willzy12h.nocturne.core.protocol.CommandSafety
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.link.*
import io.github.willzy12h.nocturne.core.night.*
import io.github.willzy12h.nocturne.core.plan.*
import io.github.willzy12h.nocturne.core.protocol.*
import io.github.willzy12h.nocturne.core.store.*
import io.github.willzy12h.nocturne.data.SlotBackupsUnreadable
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import io.github.willzy12h.nocturne.night.NightActions.Command

// Part of [NightActions], split out of NightActions.kt: writing a BedJet programme slot (pin, restore, Boost) and
// the saved-originals barrier checked before any slot write (IR-03, IR-06).

/** IR-03: while the saved originals are unreadable, a slot with none is not written, and nothing is published or
 * sent first. Repository.recordOriginalSlot stays the final barrier. */
internal fun NightActions.slotBackupsRefusal(slot: Int?): CommandOutcome? {
    val target = repo.settings.value.deviceId() ?: return null
    return if (slot != null && repo.originalsUnreadableFor(target, slot)) refused(R.string.runtime_slot_backups_unreadable) else null
}

/** [slotBackupsRefusal] for the commands that write a slot, checked before connecting. */
internal fun NightActions.slotWriteRefusal(command: Command): CommandOutcome? = slotBackupsRefusal(when (command) {
    Command.START_NIGHT, Command.BOOST -> repo.settings.value.workingSlot
    Command.REAPPLY, Command.TEMPORARY -> repo.activeNight.value?.slot
    Command.RESUME -> repo.runtime.value.resumeOffer?.night?.slot
    else -> null
})

internal suspend fun NightActions.writeSlotLocked(slot: Int, program: StoredProgram?, label: String, start: Boolean = false,
    expectedOriginal: SlotBackup? = null): CommandOutcome {
    if (program != null && CommandSafety.programme(program, graph.now().toLocalTime()) != null)
        return refused(R.string.runtime_restore_unsafe)
    val target = repo.settings.value.deviceId() ?: return refused(R.string.runtime_choose_target)
    val earlier = repo.runtime.value.slotWrite
    val night = repo.activeNight.value
    val intent = SlotWrite(target, slot, program, label = label, startRequested = start,
        displaced = if (start) night?.identity else null, nightId = night?.recordId)
    repo.updateRuntime { it.copy(slotWrite = intent) }
    var writing = false
    try {
        backup(target, slot)
        // Import uses the repository writer independently of command ownership. Recheck after its final
        // preparation suspension; the upload payload always remains the captured, reviewed programme.
        if (expectedOriginal != null && repo.originalSlot(target, slot) != expectedOriginal) {
            val outcome = refused(R.string.runtime_restore_preview_changed)
            repo.updateRuntime { if (it.slotWrite == intent) it.copy(slotWrite = earlier?.takeIf { write ->
                write.confidence == Confidence.PENDING || write.confidence == Confidence.UNKNOWN }, reason = outcome.message) else it }
            return outcome
        }
        writing = true
        if (program != null) graph.controller().store(program, slot) else {
            graph.link.send(Codec.deleteProgram(slot))
            if (graph.controller().storedPrograms()[slot] != null) throw BedJetError.VerifyFailed()
        }
        if (start) graph.controller().startProgram(slot)
        repo.updateRuntime { it.copy(slotWrite = intent.copy(confidence = Confidence.CONFIRMED)) }
        return CommandOutcome(OutcomeKind.CONFIRMED, label)
    } catch (e: Exception) {
        val reason = if (e is TimeoutCancellationException) text(R.string.runtime_operation_timeout) else e.planMessage(context)
        withContext(NonCancellable) {
            if (!writing) {
                // IR-05/IR-06: nothing reached the BedJet after a backup failure. Keep the error message, but leave
                // the journal and the running night as they were: no false uncertain intent, no FAILED night.
                repo.updateRuntime { it.copy(slotWrite = earlier?.takeIf { w -> w.confidence == Confidence.PENDING || w.confidence == Confidence.UNKNOWN },
                    reason = reason) }
            } else {
                repo.updateRuntime { it.copy(slotWrite = intent.copy(confidence = Confidence.UNKNOWN), reason = reason) }
                if (start) repo.activeNight.value?.let { publishNight(it.copy(confidence = Confidence.UNKNOWN)) }
            }
        }
        if (e is CancellationException) throw e
        if (e is SlotBackupsUnreadable) return refused(R.string.runtime_slot_backups_unreadable)
        // The write reports its own outcome: a write that may have reached the BedJet is UNKNOWN (IR-06).
        recordError("$label: ${e.message.orEmpty()}")
        return CommandOutcome(if (writing) OutcomeKind.UNKNOWN else OutcomeKind.FAILED, e.planMessage(context) ?: text(R.string.runtime_failed))
    }
}
