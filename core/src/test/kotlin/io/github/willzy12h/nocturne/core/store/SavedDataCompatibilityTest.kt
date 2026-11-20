package io.github.willzy12h.nocturne.core.store

import io.github.willzy12h.nocturne.core.plan.Profile
import io.github.willzy12h.nocturne.core.plan.NapPreset
import io.github.willzy12h.nocturne.core.protocol.Mode
import kotlinx.serialization.builtins.ListSerializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
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

    @Test fun `beta 8 keeps the remembered Cool settings in local data and backups`() {
        val saved = versions.single { it.name == "1.1.0-beta.8" }
        val settings = BackupCodec.json.decodeFromString(Settings.serializer(), File(saved, "settings.json").readText())
        val backup = BackupCodec.decode(File(saved, "backup.json").readText())
        val expected = ManualControls("ble:02:00:00:00:00:08", Mode.COOL, temperature = 46, fanStep = 11)
        assertEquals(expected, settings.manualFor(settings.targetDevice, Mode.COOL))
        assertEquals(expected, backup.settings.manualFor(backup.settings.targetDevice, Mode.COOL))
        assertEquals(NapPreset(), settings.napPreset)
        assertEquals(settings.napPreset, backup.settings.napPreset)
        assertEquals(BackupCodec.SCHEMA, backup.schema)
        assertTrue(backup.history.none { it.isNap })
    }

    @Test fun `beta 9 keeps the edited nap and observed BedJet history`() {
        val saved = versions.single { it.name == "1.1.0-beta.9" }
        val settings = BackupCodec.json.decodeFromString(Settings.serializer(), File(saved, "settings.json").readText())
        val backup = BackupCodec.decode(File(saved, "backup.json").readText())
        assertEquals(NapPreset(durationMinutes = 105), settings.napPreset)
        assertEquals(settings, backup.settings)
        val nap = backup.history.single { it.isNap }
        assertEquals("2026-11-20T14:54:00Z", nap.lastRunningObservedAt)
        assertEquals("2026-11-20T14:55:00Z", nap.standbyObservedAt)
    }

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
