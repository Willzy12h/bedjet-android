package io.github.willzy12h.nocturne.core.store

import io.github.willzy12h.nocturne.core.plan.Profile
import io.github.willzy12h.nocturne.core.protocol.StoredProgram
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** A program the app found in a BedJet slot before it first overwrote it. */
@Serializable
data class SlotBackup(val slot: Int, val program: StoredProgram, val savedAt: String)

@Serializable
data class BackupBundle(
    val schema: Int = BackupCodec.SCHEMA,
    val createdAt: String,
    val profiles: List<Profile>,
    val settings: Settings,
    val slotBackups: List<SlotBackup> = emptyList(),
    val history: List<NightRecord> = emptyList(),
)

class BackupException(message: String) : Exception(message)

object BackupCodec {
    const val SCHEMA = 1

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    fun encode(bundle: BackupBundle): String = json.encodeToString(BackupBundle.serializer(), bundle)

    fun decode(text: String): BackupBundle {
        val bundle = try {
            json.decodeFromString(BackupBundle.serializer(), text)
        } catch (e: SerializationException) {
            throw BackupException("This isn't a Nocturne backup, or it is damaged.")
        } catch (e: IllegalArgumentException) {
            throw BackupException("This isn't a Nocturne backup, or it is damaged.")
        }
        if (bundle.schema > SCHEMA) throw BackupException("This backup is from a newer version of Nocturne. Update the app first.")
        return bundle
    }
}
