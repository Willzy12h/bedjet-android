package io.github.willzy12h.nocturne.data

import io.github.willzy12h.nocturne.R
import android.util.Log
import androidx.core.util.AtomicFile
import io.github.willzy12h.nocturne.core.store.BackupCodec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.*
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import io.github.willzy12h.nocturne.data.Repository.Companion.TAG
import io.github.willzy12h.nocturne.data.Repository.Companion.PROFILES
import io.github.willzy12h.nocturne.data.Repository.Companion.SETTINGS
import io.github.willzy12h.nocturne.data.Repository.Companion.HISTORY
import io.github.willzy12h.nocturne.data.Repository.Companion.SLOT_BACKUPS
import io.github.willzy12h.nocturne.data.Repository.Companion.SLOT_BACKUPS_BLOCK
import io.github.willzy12h.nocturne.data.Repository.Companion.SLOT_BACKUPS_LOSS
import io.github.willzy12h.nocturne.data.Repository.Companion.RESTORE_JOURNAL
import io.github.willzy12h.nocturne.data.Repository.Companion.RUNTIME
import io.github.willzy12h.nocturne.data.Repository.Companion.ACTIVE_NIGHT
import io.github.willzy12h.nocturne.data.Repository.Companion.UNREADABLE
import io.github.willzy12h.nocturne.data.Repository.Write
import io.github.willzy12h.nocturne.data.Repository.JournalWrite
import io.github.willzy12h.nocturne.data.Repository.RestoreJournal
import io.github.willzy12h.nocturne.data.Repository.JournalState

// Part of [Repository], split out of Repository.kt: durable storage. Every write runs on the writer, one at a time;
// several files change together through the restore journal; unreadable files are set aside and reported.

/** Runs [work] on the writer, one at a time. */
internal suspend fun <T> Repository.serially(work: suspend () -> T): T = withContext(writer) { lock.withLock { work() } }

internal suspend fun <T> Repository.update(flow: MutableStateFlow<T>, name: String, serializer: KSerializer<T>, change: (T) -> T): Unit = serially {
    if (isDemoSession && name == HISTORY) return@serially
    val value = change(flow.value)
    writeValue(name, serializer, value)
    flow.value = value
    if (name == SETTINGS) _settingsPublication.value += 1
}

internal fun <T> Repository.writeValue(name: String, serializer: KSerializer<T>, value: T) {
    if (isDemoSession) return
    finishJournalBefore(name)
    writeFile(File(dir, name), encode(serializer, value))
}

/**
 * Writes several files as one change. A journal of the new contents is saved first; from then on the change is
 * committed, as the next start rolls it forward. Call on the writer.
 *
 * - Every file written: the values are published and the journal removed. If it can't be removed it stays
 *   pending ([pendingJournal]), to be removed before these files are next written.
 * - A file fails (an Error too): the files already written are put back and the journal removed, and the failure
 *   is thrown with nothing published. If a file can't be put back, or the journal can't be removed, the change
 *   stays committed instead: the new values are published, as the next start will load them, the journal stays
 *   pending, and [RestoreUnfinished] is thrown (an Error as it is). Nothing written after that is lost to the
 *   roll-forward, as every later write of these files finishes the journal first or is refused.
 */
internal fun Repository.writeAllOrNone(writes: List<Write>) {
    if (isDemoSession) { writes.forEach { it.publish() }; return }
    // A journal still pending is finished first: the new one replaces it.
    finishJournalBefore(*writes.map { it.name }.toTypedArray())
    val entries = writes.associate { it.name to it.new }
    val journal = RestoreJournal(writes.map { JournalWrite(it.name, Base64.getEncoder().encodeToString(it.new), digest(it.new)) })
    writeValue(RESTORE_JOURNAL, RestoreJournal.serializer(), journal)
    writes.forEachIndexed { i, write ->
        try {
            writeFile(File(dir, write.name), write.new)
        } catch (e: Throwable) {
            var putBack = true
            writes.take(i).forEach { done ->
                runCatching { writeFile(File(dir, done.name), done.old) }.onFailure { e.addSuppressed(it); putBack = false }
            }
            if (putBack && removeJournal()) throw e
            pendingJournal = entries
            writes.forEach { it.publish() }
            throw if (e is Exception) RestoreUnfinished(e, context.getString(R.string.runtime_change_unfinished)) else e
        }
    }
    writes.forEach { it.publish() }
    if (!removeJournal()) {
        Log.w(TAG, "Couldn't remove $RESTORE_JOURNAL; it is finished before these files are next written")
        pendingJournal = entries
    }
}

/**
 * Before any of [names] is written: finishes a pending journal that holds one of them, so the next start doesn't
 * roll it forward over the write. Throws [RestoreUnfinished], and then nothing is written, when it can't be done.
 */
internal fun Repository.finishJournalBefore(vararg names: String) {
    val entries = pendingJournal ?: return
    if (names.none { it in entries }) return
    try {
        finishJournal()
    } catch (e: Exception) {
        throw RestoreUnfinished(e, context.getString(R.string.runtime_earlier_change_unfinished))
    }
}

/** Writes every file of [pendingJournal], then removes the journal. Throws, leaving it pending, when either fails. */
private fun Repository.finishJournal() {
    val entries = pendingJournal ?: return
    entries.forEach { (name, bytes) -> writeFile(File(dir, name), bytes) }
    if (!removeJournal()) throw IOException("Couldn't remove $RESTORE_JOURNAL.")
    pendingJournal = null
    if (journalStuck) {
        journalStuck = false
        _unreadable.value = _unreadable.value - RESTORE_JOURNAL
    }
}

/** Removes the journal; true only when none of its files is left behind. */
private fun Repository.removeJournal(): Boolean {
    runCatching { deleteFile(File(dir, RESTORE_JOURNAL)) }.onFailure { Log.w(TAG, "Couldn't remove $RESTORE_JOURNAL", it) }
    return listOf("", ".new", ".bak").none { File(dir, RESTORE_JOURNAL + it).exists() }
}

/**
 * Runs before anything is loaded: rolls an interrupted multi-file change forward from its journal, through
 * [writeFile]. A journal that can't be read, names other files or fails its checks changes no file: it is noted
 * to be set aside and reported in [unreadable] instead of stopping start-up ([JournalState.DAMAGED]). When a
 * file can't be written now the journal stays pending ([JournalState.STUCK]): the loads read its contents, it is
 * reported in [unreadable] with a notice, and it is finished before any of its files is next written.
 */
internal fun Repository.recoverPendingRestore(): JournalState {
    val file = AtomicFile(File(dir, RESTORE_JOURNAL))
    val entries = try {
        readJournal(file.readFully())
    } catch (_: FileNotFoundException) {
        return JournalState.NONE
    } catch (e: IOException) {
        Log.e(TAG, "Couldn't use $RESTORE_JOURNAL; it will be moved aside (${e.javaClass.simpleName})")
        toSetAside += file.baseFile
        return JournalState.DAMAGED
    }
    // A restore clears the unreadable report; a morning check-in, the other multi-file change, does not.
    if (entries.keys == setOf(PROFILES, SETTINGS, HISTORY, SLOT_BACKUPS)) AtomicFile(File(dir, UNREADABLE)).delete()
    pendingJournal = entries
    return try {
        finishJournal()
        JournalState.NONE
    } catch (e: Exception) {
        Log.e(TAG, "Couldn't finish the interrupted change yet", e)
        JournalState.STUCK
    }
}

/** Every entry of a journal, checked before any is used. Throws [IOException] when it can't be trusted. */
private fun Repository.readJournal(bytes: ByteArray): Map<String, ByteArray> {
    val journal = try { decode(RestoreJournal.serializer(), bytes.decodeToString()) }
        catch (e: IllegalArgumentException) { throw IOException("The restore journal is unreadable.", e) }
    val names = journal.writes.map { it.name }
    if (names.size != names.toSet().size || names.toSet() !in setOf(setOf(PROFILES, SETTINGS, HISTORY, SLOT_BACKUPS),
            setOf(PROFILES, HISTORY), setOf(SETTINGS, ACTIVE_NIGHT, RUNTIME),
            setOf(PROFILES), setOf(PROFILES, SETTINGS))) {
        throw IOException("The restore journal does not name the expected files.")
    }
    return journal.writes.associate { entry ->
        val content = try { Base64.getDecoder().decode(entry.bytes) }
            catch (e: IllegalArgumentException) { throw IOException("The restore journal is damaged.", e) }
        if (digest(content) != entry.sha256) throw IOException("The restore journal failed its integrity check.")
        entry.name to content
    }
}

internal fun Repository.digest(bytes: ByteArray): String = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes))

internal fun <T> Repository.encode(serializer: KSerializer<T>, value: T) = BackupCodec.json.encodeToString(serializer, value).toByteArray()

internal fun <T> Repository.decode(serializer: KSerializer<T>, text: String): T = BackupCodec.json.decodeFromString(serializer, text)

/**
 * Null when the file doesn't exist yet, or couldn't be read; then it's moved aside and reported in [unreadable]. A
 * file in a pending journal is read from the journal, which is what it will hold.
 */
internal fun <T> Repository.load(name: String, decode: (String) -> T): T? {
    pendingJournal?.get(name)?.let { bytes ->
        runCatching { decode(bytes.decodeToString()) }.onSuccess { return it }.onFailure { Log.e(TAG, "Couldn't read $name from the journal (${it.javaClass.simpleName})") }
    }
    val file = AtomicFile(File(dir, name))
    return try {
        decode(file.readFully().decodeToString())
    } catch (_: FileNotFoundException) {
        null
    } catch (e: IOException) {
        setAside(file.baseFile, e)
    } catch (e: IllegalArgumentException) {
        // SerializationException is one: bad JSON, or a value from a newer version such as an unknown enum.
        setAside(file.baseFile, e)
    }
}

/** Notes [file] as unreadable; [setAsidePending] moves it aside on the writer. */
internal fun <T> Repository.setAside(file: File, cause: Exception): T? {
    Log.w(TAG, "Couldn't read ${file.name}; it will be moved aside (${cause.javaClass.simpleName})")
    toSetAside += file
    _unreadable.value = (_unreadable.value + file.name).distinct()
    if (file.name == SLOT_BACKUPS) originalsIntegrityBlocked = true
    return null
}

/** Runs on the writer. A report that can't be saved is shown as unreadable too, rather than lost on a restart. */
internal fun Repository.setAsidePending() {
    // Write the independent barrier before moving the corrupt file. If this write fails, a restart will still
    // encounter the corrupt original and refuse slot mutations rather than treating a missing file as new.
    val barrierSaved = if (toSetAside.any { it.name == SLOT_BACKUPS }) {
        runCatching { writeValue(SLOT_BACKUPS_BLOCK, String.serializer(), "blocked") }.onFailure {
            Log.e(TAG, "Couldn't save $SLOT_BACKUPS_BLOCK", it)
        }.isSuccess
    } else true
    val lossRecordAside = toSetAside.any { it.name == SLOT_BACKUPS_LOSS }
    toSetAside.forEach { file ->
        if (file.name == SLOT_BACKUPS && !barrierSaved) return@forEach
        val aside = File(dir, "${file.name}.corrupt-${System.currentTimeMillis()}")
        // If it can't be moved, a copy still keeps the original from being lost on the next write.
        if (!file.renameTo(aside)) runCatching { file.copyTo(aside) }.onFailure { Log.e(TAG, "Couldn't keep ${file.name}", it) }
    }
    toSetAside.clear()
    // A damaged record of the accepted loss is replaced by the time it is now read as.
    if (lossRecordAside) originalsLossAcceptedAt?.let { at ->
        runCatching { writeValue(SLOT_BACKUPS_LOSS, String.serializer(), at) }.onFailure { Log.e(TAG, "Couldn't save $SLOT_BACKUPS_LOSS", it) }
    }
    // A journal still pending is reported only while it is: each start finds it again.
    val names = _unreadable.value
    runCatching { writeValue(UNREADABLE, ListSerializer(String.serializer()), if (journalStuck) names - RESTORE_JOURNAL else names) }.onFailure {
        Log.e(TAG, "Couldn't save $UNREADABLE", it)
        _unreadable.value = (names + UNREADABLE).distinct()
    }
}

internal fun Repository.readUnreadableMarker(): List<String> =
    runCatching { decode(ListSerializer(String.serializer()), AtomicFile(File(dir, UNREADABLE)).readFully().decodeToString()) }.getOrDefault(emptyList())

internal fun Repository.clearUnreadable() {
    AtomicFile(File(dir, UNREADABLE)).delete()
    // A restore that still isn't finished stays reported: dismissing the notice doesn't finish it.
    _unreadable.value = if (journalStuck) listOf(RESTORE_JOURNAL) else emptyList()
}
