package io.github.willzy12h.nocturne.core.store

import io.github.willzy12h.nocturne.core.plan.Profile
import kotlinx.serialization.builtins.ListSerializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File

/**
 * Profiles, settings and a backup file written by each released version, read the way this version reads them.
 * An update that couldn't read one would show the built-in profiles and default settings instead of the person's own.
 * Add the new version's files to `saved-data/` at each release (AGENTS.md says how).
 */
class SavedDataCompatibilityTest {
    private val versions: List<File> = File(requireNotNull(javaClass.getResource("/saved-data")).toURI())
        .listFiles { file -> file.isDirectory }.orEmpty().sortedBy { it.name }

    @TestFactory fun `every released version's saved data still reads`(): List<DynamicTest> {
        assertTrue(versions.size >= 4, "saved-data holds a folder per released version")
        return versions.map { version ->
            DynamicTest.dynamicTest(version.name) {
                val profiles = BackupCodec.json.decodeFromString(ListSerializer(Profile.serializer()), File(version, "profiles.json").readText())
                val settings = BackupCodec.json.decodeFromString(Settings.serializer(), File(version, "settings.json").readText())
                val backup = BackupCodec.decode(File(version, "backup.json").readText())
                // What each file was written with, so nothing the person changed falls back to a default unnoticed.
                val summer = profiles.first { it.id == "preset-summer" }
                assertEquals(Triple("Summer, cooler", setOf(6, 7), 70), Triple(summer.name, summer.days, summer.middle.fanStart))
                assertEquals(setOf(1, 2, 3), profiles.first { it.id == "own-1" }.days)
                assertEquals(1 to true, settings.workingSlot to settings.fahrenheit)
                assertEquals(profiles, backup.profiles)
                assertEquals(1 to true, backup.settings.workingSlot to backup.settings.fahrenheit)
            }
        }
    }
}
