package io.github.willzy12h.nocturne.core.store

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonObject
import java.io.FileOutputStream
import java.io.Reader
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.ZonedDateTime
import kotlin.io.path.bufferedReader
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText

data class SnapshotInfo(val id: String, val createdAt: String, val label: String?, val manual: Boolean)

/** Point-in-time copies of everything, to restore from. Manual ones are kept; the last [KEEP_AUTOMATIC] automatic ones too. */
class SnapshotStore(private val dir: Path, private val now: () -> ZonedDateTime) {

    init {
        if (dir.exists()) {
            // A completed snapshot never has this suffix. Leave every other file alone.
            dir.listDirectoryEntries("snap-*.json.tmp").forEach { path ->
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    try { path.deleteIfExists() } catch (_: IOException) { /* A good snapshot remains usable. */ }
                }
            }
        }
    }

    /** Written header first, so [list] can stop reading before the bundle. */
    @Serializable
    private data class SnapshotFile(val createdAt: String, val label: String? = null, val manual: Boolean, val bundle: BackupBundle)

    private data class Header(val createdAt: String, val label: String?, val manual: Boolean)

    /** Syncs contents before atomic publication, then syncs the directory where supported. Power-loss guarantees vary by filesystem. */
    fun save(bundle: BackupBundle, label: String?, manual: Boolean): SnapshotInfo {
        Files.createDirectories(dir)
        val time = now()
        var id = "snap-${time.toInstant().toEpochMilli()}-${if (manual) "m" else "a"}"
        while (dir.resolve("$id.json").exists()) id += "x"
        val file = SnapshotFile(time.toString(), label, manual, bundle)
        val temp = dir.resolve("$id.json.tmp")
        FileOutputStream(temp.toFile()).use { out ->
            out.write(BackupCodec.json.encodeToString(SnapshotFile.serializer(), file).toByteArray())
            out.fd.sync()
        }
        Files.move(temp, dir.resolve("$id.json"), StandardCopyOption.ATOMIC_MOVE)
        syncDirectoryIfSupported()
        prune()
        return SnapshotInfo(id, file.createdAt, label, manual)
    }

    /** Saves an automatic snapshot unless nothing changed since the last one. Returns whether it saved. */
    fun saveIfChanged(bundle: BackupBundle): Boolean {
        val latest = list().firstOrNull { !it.manual }?.let { load(it.id) }
        if (latest != null && latest.copy(createdAt = "") == bundle.copy(createdAt = "")) return false
        save(bundle, label = null, manual = false)
        return true
    }

    /** Newest first. Reads only each file's header, not the bundle after it. */
    fun list(): List<SnapshotInfo> {
        if (!dir.exists()) return emptyList()
        return dir.listDirectoryEntries("snap-*.json")
            .mapNotNull { path ->
                val id = path.name.removeSuffix(".json")
                runCatching { path.bufferedReader().use { HeaderReader(it).read() } }
                    .getOrNull()?.let { SnapshotInfo(id, it.createdAt, it.label, it.manual) }
            }
            .sortedByDescending { it.id.substringAfter("snap-").substringBefore("-").toLongOrNull() ?: 0 }
    }

    /** Null when missing, unreadable, or written by a newer version than this one. Older ones are migrated. */
    fun load(id: String): BackupBundle? = runCatching {
        val file = BackupCodec.json.parseToJsonElement(dir.resolve("$id.json").readText()).jsonObject
        BackupCodec.decode(file.getValue("bundle").jsonObject)
    }.getOrNull()

    fun delete(id: String) {
        dir.resolve("$id.json").deleteIfExists()
    }

    private fun prune() {
        list().filter { !it.manual }.drop(KEEP_AUTOMATIC).forEach { delete(it.id) }
    }

    private fun syncDirectoryIfSupported() {
        // Android/filesystems differ on whether a directory can be opened as a channel.
        try { FileChannel.open(dir, StandardOpenOption.READ).use { it.force(true) } }
        catch (_: IOException) { }
        catch (_: UnsupportedOperationException) { }
        catch (_: SecurityException) { }
    }

    /**
     * Reads `createdAt`, `label` and `manual` from the start of a snapshot file and stops at `bundle`, which can be
     * large. Null when the file doesn't start that way.
     */
    private class HeaderReader(private val reader: Reader) {
        fun read(): Header? {
            if (nextToken() != '{'.code) return null
            var createdAt: String? = null
            var label: String? = null
            var manual: Boolean? = null
            var c = nextToken()
            while (c != '}'.code) {
                if (c == ','.code) c = nextToken()
                if (c != '"'.code) return null
                val key = string()
                if (nextToken() != ':'.code) return null
                if (key == "bundle") break
                c = nextToken()
                when (key) {
                    "createdAt" -> createdAt = if (c == '"'.code) string() else return null
                    "label" -> label = when (c) {
                        '"'.code -> string()
                        'n'.code -> if (literal("ull")) null else return null
                        else -> return null
                    }
                    "manual" -> manual = when (c) {
                        't'.code -> if (literal("rue")) true else return null
                        'f'.code -> if (literal("alse")) false else return null
                        else -> return null
                    }
                    else -> return null
                }
                c = nextToken()
            }
            return Header(createdAt ?: return null, label, manual ?: return null)
        }

        private fun nextToken(): Int {
            var c: Int
            do c = reader.read() while (c == ' '.code || c == '\n'.code || c == '\r'.code || c == '\t'.code)
            return c
        }

        private fun literal(rest: String) = rest.all { reader.read() == it.code }

        /** The rest of a string whose opening quote has been read. */
        private fun string(): String = buildString {
            while (true) {
                when (val c = reader.read()) {
                    -1 -> error("Unterminated string")
                    '"'.code -> return@buildString
                    '\\'.code -> when (val e = reader.read()) {
                        'n'.code -> append('\n')
                        't'.code -> append('\t')
                        'r'.code -> append('\r')
                        'b'.code -> append('\b')
                        'f'.code -> append('\u000C')
                        'u'.code -> append(CharArray(4) { reader.read().toChar() }.concatToString().toInt(16).toChar())
                        -1 -> error("Unterminated string")
                        else -> append(e.toChar())
                    }
                    else -> append(c.toChar())
                }
            }
        }
    }

    companion object {
        const val KEEP_AUTOMATIC = 20
    }
}
