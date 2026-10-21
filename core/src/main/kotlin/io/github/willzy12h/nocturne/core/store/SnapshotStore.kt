package io.github.willzy12h.nocturne.core.store

import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZonedDateTime
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText

data class SnapshotInfo(val id: String, val createdAt: String, val label: String?, val manual: Boolean)

/** Point-in-time copies of everything, to restore from. Manual ones are kept; the last [KEEP_AUTOMATIC] automatic ones too. */
class SnapshotStore(private val dir: Path, private val now: () -> ZonedDateTime) {

    @Serializable
    private data class SnapshotFile(val createdAt: String, val label: String? = null, val manual: Boolean, val bundle: BackupBundle)

    fun save(bundle: BackupBundle, label: String?, manual: Boolean): SnapshotInfo {
        Files.createDirectories(dir)
        val time = now()
        var id = "snap-${time.toInstant().toEpochMilli()}-${if (manual) "m" else "a"}"
        while (dir.resolve("$id.json").exists()) id += "x"
        val file = SnapshotFile(time.toString(), label, manual, bundle)
        dir.resolve("$id.json").writeText(BackupCodec.json.encodeToString(SnapshotFile.serializer(), file))
        prune()
        return SnapshotInfo(id, file.createdAt, label, manual)
    }

    /** Saves an automatic snapshot unless nothing changed since the last one. Returns whether it saved. */
    fun saveIfChanged(bundle: BackupBundle): Boolean {
        val latest = list().firstOrNull { !it.manual }?.let { read(it.id)?.bundle }
        if (latest != null && latest.copy(createdAt = "") == bundle.copy(createdAt = "")) return false
        save(bundle, label = null, manual = false)
        return true
    }

    /** Newest first. */
    fun list(): List<SnapshotInfo> {
        if (!dir.exists()) return emptyList()
        return dir.listDirectoryEntries("snap-*.json")
            .mapNotNull { path -> read(path.name.removeSuffix(".json"))?.let { SnapshotInfo(path.name.removeSuffix(".json"), it.createdAt, it.label, it.manual) } }
            .sortedByDescending { it.id.substringAfter("snap-").substringBefore("-").toLongOrNull() ?: 0 }
    }

    fun load(id: String): BackupBundle? = read(id)?.bundle

    fun delete(id: String) {
        dir.resolve("$id.json").deleteIfExists()
    }

    private fun read(id: String): SnapshotFile? = runCatching {
        BackupCodec.json.decodeFromString(SnapshotFile.serializer(), dir.resolve("$id.json").readText())
    }.getOrNull()

    private fun prune() {
        list().filter { !it.manual }.drop(KEEP_AUTOMATIC).forEach { delete(it.id) }
    }

    companion object {
        const val KEEP_AUTOMATIC = 20
    }
}
