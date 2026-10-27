package io.github.willzy12h.nocturne.data

import io.github.willzy12h.nocturne.core.plan.Presets
import io.github.willzy12h.nocturne.core.plan.TonightChoice
import io.github.willzy12h.nocturne.core.store.*
import org.junit.Assert.*
import org.junit.Test

class ImportSectionsTest {
    private val at = "2026-10-24T22:30Z"
    private val device = "ble:AA:BB:CC:DD:EE:FF"
    private fun bundle() = BackupBundle(createdAt = at, profiles = emptyList(), settings = Settings())
    private fun night(id: String, name: String = id) = NightRecord(id, "profile", name, at, at, false)

    @Test fun profilePreviewIncludesAddsReplacementsUnchangedAndActualRemovals() {
        val a = Presets.summerCool.copy(id = "a", name = "Before")
        val same = a.copy(id = "same", name = "Same")
        val removed = a.copy(id = "removed", name = "Removed")
        val incoming = bundle().copy(profiles = listOf(a.copy(name = "After"), same, a.copy(id = "new", name = "Added")))
        val current = bundle().copy(profiles = listOf(a, same, removed))
        val section = importSections(current, incoming, incoming).first { it.area == ImportArea.PROFILES }
        assertEquals(mapOf("a" to ImportDisposition.REPLACED, "same" to ImportDisposition.UNCHANGED,
            "new" to ImportDisposition.ADDED, "removed" to ImportDisposition.REMOVED), section.entries.associate { it.id to it.disposition })
        assertEquals(1, section.count(ImportDisposition.ADDED))
        assertEquals(1, section.count(ImportDisposition.REPLACED))
        assertEquals(0, section.count(ImportDisposition.PRESERVED))
        assertEquals(1, section.count(ImportDisposition.UNCHANGED))
    }

    @Test fun settingsPreviewDistinguishesProtectedLocalPreferencesFromImportedChanges() {
        val current = bundle().copy(settings = Settings(targetDevice = device, tonightChoice = TonightChoice("local", at)))
        val incoming = bundle().copy(settings = Settings(fahrenheit = true, targetDevice = "ble:11:22:33:44:55:66",
            tonightChoice = TonightChoice("foreign", at)))
        val result = current.copy(settings = current.settings.copy(fahrenheit = true))
        val entries = importSections(current, incoming, result).first { it.area == ImportArea.SETTINGS }.entries.associateBy { it.id }
        assertEquals(ImportDisposition.REPLACED, entries.getValue("fahrenheit").disposition)
        assertEquals(ImportDisposition.PRESERVED, entries.getValue("targetDevice").disposition)
        assertEquals(ImportDisposition.PRESERVED, entries.getValue("tonightChoice").disposition)
        assertEquals(ImportDisposition.UNCHANGED, entries.getValue("workingSlot").disposition)
    }

    @Test fun historyPreviewNamesTheKeptLiveRecordAndShowsEveryDisposition() {
        val live = night("live", "Current live night")
        val replaced = night("replace", "Old name")
        val same = night("same")
        val current = bundle().copy(history = listOf(live, replaced, same, night("removed")))
        val incoming = bundle().copy(history = listOf(live.copy(profileName = "Foreign live night"), replaced.copy(profileName = "New name"),
            same, night("added"), night("expired").copy(startedAt = "2020-01-01T22:30Z")))
        val result = incoming.copy(history = listOf(live, replaced.copy(profileName = "New name"), same, night("added")))
        val section = importSections(current, incoming, result).first { it.area == ImportArea.HISTORY }
        assertEquals(mapOf("live" to ImportDisposition.PRESERVED, "replace" to ImportDisposition.REPLACED,
            "same" to ImportDisposition.UNCHANGED, "added" to ImportDisposition.ADDED,
            "expired" to ImportDisposition.OMITTED, "removed" to ImportDisposition.REMOVED), section.entries.associate { it.id to it.disposition })
        assertEquals("Current live night", section.entries.first { it.id == "live" }.name)
    }

    @Test fun aCoveredUnassignedOriginalIsOmittedWhileTheProtectedLocalOriginalIsPreserved() {
        val local = SlotBackup(device, 0, null, at)
        val unassigned = SlotBackup(Migrations.UNKNOWN_DEVICE, 0, null, at, uncertain = true)
        val current = bundle().copy(slotBackups = listOf(local))
        val incoming = bundle().copy(slotBackups = listOf(unassigned))
        val section = importSections(current, incoming, current).first { it.area == ImportArea.ORIGINALS }
        assertEquals(ImportDisposition.OMITTED, section.entries.first { it.deviceId == Migrations.UNKNOWN_DEVICE }.disposition)
        assertEquals(ImportDisposition.PRESERVED, section.entries.first { it.deviceId == device }.disposition)
        assertEquals(0, section.count(ImportDisposition.ADDED))
    }
}
