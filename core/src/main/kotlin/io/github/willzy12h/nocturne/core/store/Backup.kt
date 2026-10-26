package io.github.willzy12h.nocturne.core.store

import io.github.willzy12h.nocturne.core.plan.Profile
import io.github.willzy12h.nocturne.core.protocol.StoredProgram
import kotlinx.serialization.Serializable
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * What a BedJet's slot held before the app first overwrote it, on the device named by [deviceId] (a
 * [io.github.willzy12h.nocturne.core.link.DeviceId], or [Migrations.UNKNOWN_DEVICE] for one saved before devices were
 * recorded). [empty] marks a slot that held nothing, so putting it back means leaving it empty.
 */
@Serializable
data class SlotBackup(
    val deviceId: String,
    val slot: Int,
    val program: StoredProgram?,
    val savedAt: String,
    val empty: Boolean = program == null,
    /** Imported unknown-device records cannot identify the BedJet whose original they contain. */
    val imported: Boolean = false,
    /**
     * The slot may already have held one of the app's own programmes when this was saved: version 1 never recorded
     * empty slots (IR-31), and a record saved after the loss of unreadable originals was accepted can't tell either.
     */
    val uncertain: Boolean = false,
)

@Serializable
data class BackupBundle(
    val schema: Int = BackupCodec.SCHEMA,
    val createdAt: String,
    val profiles: List<Profile>,
    val settings: Settings,
    val slotBackups: List<SlotBackup> = emptyList(),
    val history: List<NightRecord> = emptyList(),
)

/** A backup that can't be used. [errors] lists every problem found, each a sentence a person can act on. */
enum class BackupFailure { INVALID, DAMAGED, NEWER_SCHEMA, TOO_LARGE, BUSY, CHANGED }

class BackupException(message: String, val errors: List<String> = listOf(message),
    val failure: BackupFailure = BackupFailure.INVALID) : Exception(message) {
    constructor(errors: List<String>) : this(errors.joinToString("\n"), errors)
}

object BackupCodec {
    /** 2 added the device to slot backups and nights, and the empty-slot marker. See [Migrations]. */
    const val SCHEMA = 2

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    private val strictJson = Json(json) { ignoreUnknownKeys = false }
    private const val MAX_DEPTH = 32
    private const val MAX_STRING = 2_048
    private const val MAX_TOKENS = 200_000
    private const val DAMAGED = "This isn't a Nocturne backup, or it is damaged."
    private const val NEWER = "This backup is from a newer version of Nocturne. Update the app first."

    fun encode(bundle: BackupBundle): String = json.encodeToString(BackupBundle.serializer(), bundle)

    /** Decodes a bounded, strict document; schema 1 (including its absent version marker) is migrated. */
    fun decode(text: String): BackupBundle = decode(parseDocument(text))

    /** Also used by snapshot envelopes, before building a recursive JSON tree. */
    internal fun parseDocument(text: String): JsonObject = checked {
        if (text.length > BackupValidator.MAX_BYTES || text.toByteArray(Charsets.UTF_8).size > BackupValidator.MAX_BYTES)
            throw BackupException("BACKUP_TOO_LARGE", failure = BackupFailure.TOO_LARGE)
        checkStructure(text)
        json.parseToJsonElement(text).jsonObject
    }

    /** The object overload receives an already bounded snapshot document. */
    fun decode(obj: JsonObject): BackupBundle = checked {
        val version = obj["schema"]
        val schema = if (version == null) 1 else {
            val number = version as? JsonPrimitive ?: throw BackupException("BACKUP_SCHEMA_INVALID")
            if (number.isString) throw BackupException("BACKUP_SCHEMA_INVALID")
            number.intOrNull ?: throw BackupException("BACKUP_SCHEMA_INVALID")
        }
        if (schema > SCHEMA) throw BackupException(NEWER, failure = BackupFailure.NEWER_SCHEMA)
        if (schema < 1) throw BackupException("BACKUP_SCHEMA_INVALID")
        val migrated = Migrations.bundle(obj)
        // These retired settings were present in supported schema-1/2 archives. Only these exact keys are
        // discarded; arbitrary unknown data is incompatible, rather than silently changing import meaning.
        val settings = migrated["settings"] as? JsonObject
        val compatible = if (settings == null) migrated else {
            val platform = settings["platform"] as? JsonObject
            val cleaned = settings - setOf("updateRepo", "autoUpdateCheck")
            val restored = if (platform == null) cleaned else cleaned + ("platform" to JsonObject(platform - "lullHandover"))
            JsonObject(migrated + ("settings" to JsonObject(restored)))
        }
        requireExactTypes(compatible, BackupBundle.serializer().descriptor)
        strictJson.decodeFromJsonElement(BackupBundle.serializer(), compatible)
    }

    /** Tree decoding otherwise accepts quoted numbers/booleans; import field types must match their schema. */
    @OptIn(ExperimentalSerializationApi::class)
    private fun requireExactTypes(value: JsonElement, descriptor: SerialDescriptor) {
        fun invalid(): Nothing = throw BackupException("BACKUP_FIELD_TYPE_INVALID")
        if (value is JsonNull) {
            if (!descriptor.isNullable) invalid()
            return
        }
        when (descriptor.kind) {
            StructureKind.CLASS, StructureKind.OBJECT -> {
                val fields = value as? JsonObject ?: invalid()
                fields.forEach { (name, child) ->
                    val index = descriptor.getElementIndex(name)
                    if (index < 0) throw BackupException("BACKUP_FIELD_UNKNOWN")
                    requireExactTypes(child, descriptor.getElementDescriptor(index))
                }
            }
            StructureKind.LIST -> {
                val elements = value as? JsonArray ?: invalid()
                elements.forEach { requireExactTypes(it, descriptor.getElementDescriptor(0)) }
            }
            StructureKind.MAP -> {
                val fields = value as? JsonObject ?: invalid()
                fields.forEach { (key, child) ->
                    requireExactTypes(JsonPrimitive(key), descriptor.getElementDescriptor(0))
                    requireExactTypes(child, descriptor.getElementDescriptor(1))
                }
            }
            PrimitiveKind.STRING, PrimitiveKind.CHAR, SerialKind.ENUM -> {
                if ((value as? JsonPrimitive)?.isString != true) invalid()
            }
            PrimitiveKind.BOOLEAN -> {
                val primitive = value as? JsonPrimitive ?: invalid()
                if (primitive.isString || primitive.booleanOrNull == null) invalid()
            }
            PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT, PrimitiveKind.LONG -> {
                val primitive = value as? JsonPrimitive ?: invalid()
                if (primitive.isString || !Regex("-?(0|[1-9][0-9]*)").matches(primitive.content)) invalid()
            }
            PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE -> {
                val primitive = value as? JsonPrimitive ?: invalid()
                if (primitive.isString || primitive.content.toDoubleOrNull()?.isFinite() != true) invalid()
            }
            else -> invalid()
        }
    }

    private inline fun <T> checked(work: () -> T): T = try { work() }
        catch (e: BackupException) { throw e }
        catch (_: SerializationException) { throw BackupException(DAMAGED, failure = BackupFailure.DAMAGED) }
        catch (_: IllegalArgumentException) { throw BackupException(DAMAGED, failure = BackupFailure.DAMAGED) }

    /**
     * A non-recursive preflight limits allocation/depth and rejects duplicate keys before JsonObject can erase
     * their ambiguity. The JSON decoder still owns grammar/type validation. Escaped keys compare decoded values.
     */
    private fun checkStructure(text: String) {
        val objects = ArrayDeque<MutableSet<String>?>()
        var index = 0
        var tokens = 0
        while (index < text.length) {
            when (text[index]) {
                '{', '[' -> {
                    if (objects.size >= MAX_DEPTH) throw BackupException("BACKUP_DEPTH_LIMIT")
                    objects.addLast(if (text[index] == '{') mutableSetOf() else null)
                    tokens++
                }
                '}', ']' -> {
                    if (objects.isEmpty()) throw BackupException("BACKUP_STRUCTURE_INVALID")
                    objects.removeLast()
                }
                ',' -> tokens++
                '"' -> {
                    val start = index++
                    var escaped = false
                    while (index < text.length) {
                        val char = text[index]
                        if (!escaped && char == '"') break
                        escaped = !escaped && char == '\\'
                        index++
                        if (index - start > MAX_STRING * 6 + 2) throw BackupException("BACKUP_STRING_LIMIT")
                    }
                    if (index >= text.length) throw BackupException("BACKUP_STRING_INVALID")
                    val value = json.parseToJsonElement(text.substring(start, index + 1)).jsonPrimitive.content
                    if (value.length > MAX_STRING) throw BackupException("BACKUP_STRING_LIMIT")
                    var character = 0
                    while (character < value.length) {
                        val char = value[character]
                        if (char.isHighSurrogate()) {
                            if (++character >= value.length || !value[character].isLowSurrogate()) throw BackupException("BACKUP_STRING_INVALID")
                        } else if (char.isLowSurrogate()) throw BackupException("BACKUP_STRING_INVALID")
                        character++
                    }
                    var next = index + 1
                    while (next < text.length && text[next].isWhitespace()) next++
                    if (next < text.length && text[next] == ':') {
                        val keys = objects.lastOrNull() ?: throw BackupException("BACKUP_STRUCTURE_INVALID")
                        if (!keys.add(value)) throw BackupException("BACKUP_DUPLICATE_KEY")
                        if (keys.size > 128) throw BackupException("BACKUP_FIELD_LIMIT")
                    }
                    tokens++
                }
            }
            if (tokens > MAX_TOKENS) throw BackupException("BACKUP_COLLECTION_LIMIT")
            index++
        }
        if (objects.isNotEmpty()) throw BackupException("BACKUP_STRUCTURE_INVALID")
    }
}
