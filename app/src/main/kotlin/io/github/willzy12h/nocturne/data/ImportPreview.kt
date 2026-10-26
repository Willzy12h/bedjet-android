package io.github.willzy12h.nocturne.data

import io.github.willzy12h.nocturne.core.store.BackupBundle
import io.github.willzy12h.nocturne.core.store.BackupCodec
import io.github.willzy12h.nocturne.core.store.Settings
import io.github.willzy12h.nocturne.core.store.SlotBackup
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.util.Locale

/** Replacement removes absent profiles/history; omitted incoming history falls outside retention. */
enum class ImportDisposition { ADDED, REPLACED, PRESERVED, UNCHANGED, REMOVED, OMITTED }
enum class ImportArea { PROFILES, SETTINGS, HISTORY, ORIGINALS }
enum class ImportWarning {
    UNCERTAIN_ORIGINALS, UNASSIGNED_ORIGINALS, DEVICE_ASSOCIATIONS, UNSUPPORTED_ORIGINALS,
    ORIGINALS_PROTECTED, HISTORY_OMITTED, IMPORTED_HISTORY_UNVERIFIED, AUTOMATIC_ACTIONS_DISABLED,
}

data class ImportEntry(
    val id: String,
    /** User-provided profile/programme name, or the internal settings key mapped to resources by the UI. */
    val name: String?,
    val disposition: ImportDisposition,
    val at: String? = null,
    val deviceId: String? = null,
    val slot: Int? = null,
)

data class ImportSection(val area: ImportArea, val entries: List<ImportEntry>) {
    fun count(disposition: ImportDisposition): Int = entries.count { it.disposition == disposition }
}

/**
 * The reviewed bytes are immutable and separate from caller-owned collections. Only Repository can create an
 * applicable preview. Apply re-decodes these bytes and compares local state under its writer/admission barrier.
 */
class ImportPreview internal constructor(
    internal val encodedInput: String,
    internal val expectedState: String,
    internal val snapshotLabel: String,
    val createdAt: String,
    val sections: List<ImportSection>,
    val warnings: Set<ImportWarning>,
    val deviceAssociations: List<String>,
    val incompatibleFields: Int,
) {
    val canApply: Boolean get() = incompatibleFields == 0
}

/** Describe the exact result of the same restore projection used when applying, including current-only entries. */
internal fun importSections(current: BackupBundle, incoming: BackupBundle, result: BackupBundle): List<ImportSection> {
    fun disposition(before: Any?, requested: Any?, after: Any?): ImportDisposition = when {
        after == null -> if (before == null) ImportDisposition.OMITTED else ImportDisposition.REMOVED
        before == null -> ImportDisposition.ADDED
        before != after -> ImportDisposition.REPLACED
        requested != before -> ImportDisposition.PRESERVED
        else -> ImportDisposition.UNCHANGED
    }
    val beforeProfiles = current.profiles.associateBy { it.id }
    val requestedProfiles = incoming.profiles.associateBy { it.id }
    val afterProfiles = result.profiles.associateBy { it.id }
    val profiles = (requestedProfiles.keys + beforeProfiles.keys).map { id ->
        ImportEntry(id, (requestedProfiles[id] ?: beforeProfiles.getValue(id)).name,
            disposition(beforeProfiles[id], requestedProfiles[id], afterProfiles[id]))
    }

    fun preferences(settings: Settings): Map<String, JsonElement> {
        val encoded = BackupCodec.json.encodeToJsonElement(Settings.serializer(), settings).jsonObject
        return buildMap {
            encoded.forEach { (key, value) ->
                if (key == "platform" && value is JsonObject) value.forEach { (name, child) ->
                    if (name != "lullHandover") put("platform.$name", child)
                } else if (key !in setOf("updateRepo", "autoUpdateCheck")) put(key, value)
            }
        }
    }
    val beforeSettings = preferences(current.settings)
    val requestedSettings = preferences(incoming.settings)
    val afterSettings = preferences(result.settings)
    val settings = (requestedSettings.keys + beforeSettings.keys + afterSettings.keys).map { key ->
        ImportEntry(key, key, disposition(beforeSettings[key], requestedSettings[key], afterSettings[key]))
    }

    val beforeHistory = current.history.associateBy { it.id }
    val requestedHistory = incoming.history.associateBy { it.id }
    val afterHistory = result.history.associateBy { it.id }
    val history = (requestedHistory.keys + beforeHistory.keys).map { id ->
        val night = afterHistory[id] ?: requestedHistory[id] ?: beforeHistory.getValue(id)
        ImportEntry(id, night.profileName, disposition(beforeHistory[id], requestedHistory[id], afterHistory[id]),
            at = night.startedAt, deviceId = night.deviceId)
    }

    fun key(original: SlotBackup) = "${original.deviceId.lowercase(Locale.ROOT)}:${original.slot}"
    val beforeOriginals = current.slotBackups.associateBy(::key)
    val requestedOriginals = incoming.slotBackups.associateBy(::key)
    val afterOriginals = result.slotBackups.associateBy(::key)
    val originals = (requestedOriginals.keys + beforeOriginals.keys).map { id ->
        val before = beforeOriginals[id]
        val requested = requestedOriginals[id]
        val after = afterOriginals[id]
        val original = after ?: before ?: requireNotNull(requested)
        // Imported provenance alone does not make an otherwise identical protected original a replacement.
        val comparable = if (requested != null && before != null) requested.copy(imported = before.imported, deviceId = before.deviceId) else requested
        val action = disposition(before, comparable, after)
        ImportEntry(id, original.program?.name, action, at = original.savedAt, deviceId = original.deviceId, slot = original.slot)
    }
    return listOf(ImportSection(ImportArea.PROFILES, profiles), ImportSection(ImportArea.SETTINGS, settings),
        ImportSection(ImportArea.HISTORY, history), ImportSection(ImportArea.ORIGINALS, originals))
}
