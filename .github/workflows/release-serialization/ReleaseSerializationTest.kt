package io.github.willzy12h.nocturne.releasegate

import io.github.willzy12h.nocturne.core.store.BackupBundle
import io.github.willzy12h.nocturne.core.store.BackupCodec
import io.github.willzy12h.nocturne.core.store.SnapshotStore
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.time.ZonedDateTime
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs the same fixtures before R8 (JUnit) and after separate R8 classfile shrinking
 * (main). Neither invocation executes the APK's DEX. The latter receives class names
 * from its own R8 mapping; the Python gate checks every loaded classfile hash.
 *
 * Reflection is confined to this test so private persistence wrappers are covered
 * without exposing production APIs or changing R8's production roots/keep rules.
 */
@OptIn(ExperimentalSerializationApi::class)
class ReleaseSerializationTest {
    private val fixtureBytes = File(checkNotNull(System.getProperty("nocturne.serializationFixtures")) {
        "Set nocturne.serializationFixtures to release-serialization-fixtures.json"
    }).readBytes()
    private val fixture = BackupCodec.json.parseToJsonElement(fixtureBytes.decodeToString()).jsonObject
    private val values = fixture.getValue("values").jsonObject
    private val models = fixture.getValue("models").jsonObject
    private val classNames = System.getProperty("nocturne.serializationClassNames")?.let {
        BackupCodec.json.parseToJsonElement(File(it).readText()).jsonObject
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun expand(value: JsonElement, seen: Set<String> = emptySet()): JsonElement = when (value) {
        is JsonObject -> {
            val reference = value["\$ref"]?.jsonPrimitive?.content
            if (reference != null) {
                check(value.size == 1 && reference !in seen) { "Invalid or recursive fixture reference: $reference" }
                expand(values.getValue(reference), seen + reference)
            } else JsonObject(value.mapValues { expand(it.value, seen) })
        }
        is JsonArray -> JsonArray(value.map { expand(it, seen) })
        else -> value
    }

    @Suppress("UNCHECKED_CAST")
    private fun serializer(model: Class<*>): Pair<KSerializer<Any>, String> {
        val instanceField = model.declaredFields.singleOrNull { it.name == "Companion" }
            ?: model.declaredFields.singleOrNull { it.name == "INSTANCE" }
            ?: error("No generated serializer entry point for ${model.name}; add explicit test support")
        val companion = instanceField.apply { isAccessible = true }.get(null)
        val method = companion.javaClass.getDeclaredMethod("serializer").apply { isAccessible = true }
        return (method.invoke(companion) as KSerializer<Any>) to companion.javaClass.name
    }

    /** A decoded default must not hide a missing or changed non-default fixture value. */
    private fun assertPreserved(expected: JsonElement, actual: JsonElement, path: String) {
        when (expected) {
            is JsonObject -> {
                assertTrue("Expected an object at $path", actual is JsonObject)
                expected.forEach { (key, value) ->
                    assertTrue("Lost persisted field $path.$key", key in actual.jsonObject)
                    assertPreserved(value, actual.jsonObject.getValue(key), "$path.$key")
                }
            }
            is JsonArray -> {
                assertTrue("Expected an array at $path", actual is JsonArray)
                assertEquals("Changed collection length at $path", expected.size, actual.jsonArray.size)
                expected.forEachIndexed { index, value -> assertPreserved(value, actual.jsonArray[index], "$path[$index]") }
            }
            else -> assertEquals("Changed persisted value at $path", expected, actual)
        }
    }

    @Test
    fun allStoredModelsRoundTrip() {
        check(fixture.getValue("format").jsonPrimitive.content == "1")
        check(models.isNotEmpty())
        models.toSortedMap().forEach { (name, rawCases) ->
            val model = Class.forName(classNames?.getValue(name)?.jsonPrimitive?.content ?: name)
            val (serializer, factoryClass) = serializer(model)
            val descriptor = serializer.descriptor
            val cases = rawCases.jsonArray.map { expand(it) }
            check(cases.isNotEmpty()) { "No cases for $name" }
            val elements = (0 until descriptor.elementsCount).map(descriptor::getElementName).toSet()
            when (descriptor.kind) {
                SerialKind.ENUM -> assertEquals("Cover every enum value of $name", elements,
                    cases.map { it.jsonPrimitive.content }.toSet())
                StructureKind.CLASS, StructureKind.OBJECT -> assertEquals("Cover every serialized field of $name", elements,
                    cases.flatMap { it.jsonObject.keys }.toSet())
                PolymorphicKind.SEALED -> {
                    val alternatives = descriptor.getElementDescriptor(1)
                    assertEquals("Cover every sealed alternative of $name",
                        (0 until alternatives.elementsCount).map(alternatives::getElementName).toSet(),
                        cases.map { it.jsonObject.getValue("type").jsonPrimitive.content }.toSet())
                }
                else -> error("Unhandled serializer kind ${descriptor.kind} for $name; add explicit coverage")
            }
            cases.forEachIndexed { index, input ->
                val first = BackupCodec.json.decodeFromJsonElement(serializer, input)
                val encoded = BackupCodec.json.encodeToString(serializer, first)
                assertPreserved(input, BackupCodec.json.parseToJsonElement(encoded), "$name case $index")
                val second = BackupCodec.json.decodeFromString(serializer, encoded)
                assertEquals("Round trip changed $name case $index", first, second)
                assertEquals("Encoding is unstable for $name case $index", encoded,
                    BackupCodec.json.encodeToString(serializer, second))
            }
            val classBytes = checkNotNull(model.getResourceAsStream("/${model.name.replace('.', '/')}.class")) {
                "Cannot hash executed class $name"
            }.use { it.readBytes() }
            println("NOCTURNE_SERIALIZATION " + buildJsonObject {
                put("model", name)
                put("executedClass", model.name)
                put("classSha256", sha256(classBytes))
                put("fixtureSha256", sha256(fixtureBytes))
                put("serializer", serializer.javaClass.name)
                put("factoryClass", factoryClass)
                put("kind", descriptor.kind.toString())
                put("cases", cases.size)
            })
        }
    }

    @Test
    fun snapshotStorePersistsItsPrivateWrapper() {
        val input = expand(values.getValue("backup"))
        val bundle = BackupCodec.json.decodeFromJsonElement(BackupBundle.serializer(), input)
        val directory = Files.createTempDirectory("nocturne-release-snapshot-")
        try {
            val store = SnapshotStore(directory) { ZonedDateTime.parse("2026-10-23T22:30Z") }
            val saved = store.save(bundle, "Release \"round trip\" · 夜", manual = true)
            assertEquals(bundle, store.load(saved.id))
            assertEquals(saved, store.list().single())
            val disk = BackupCodec.json.parseToJsonElement(directory.resolve("${saved.id}.json").toFile().readText()).jsonObject
            assertEquals(JsonPrimitive(saved.label), disk.getValue("label"))
            assertEquals(JsonPrimitive(true), disk.getValue("manual"))
            assertPreserved(input, disk.getValue("bundle"), "SnapshotFile.bundle")
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    companion object {
        /** Only this entry point is kept by the standalone JVM shrinker. */
        @JvmStatic
        fun main(args: Array<String>) {
            ReleaseSerializationTest().apply {
                allStoredModelsRoundTrip()
                snapshotStorePersistsItsPrivateWrapper()
            }
            println("NOCTURNE_SERIALIZATION_COMPLETE both fixture methods passed")
        }
    }
}
