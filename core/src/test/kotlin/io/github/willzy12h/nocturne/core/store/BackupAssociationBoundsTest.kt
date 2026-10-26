package io.github.willzy12h.nocturne.core.store

import io.github.willzy12h.nocturne.core.plan.Presets
import io.github.willzy12h.nocturne.core.protocol.StoredProgram
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class BackupAssociationBoundsTest {
    private val at = "2026-10-24T22:30Z"
    private val device = "ble:AA:BB:CC:DD:EE:FF"
    private fun bundle() = BackupBundle(createdAt = at, profiles = listOf(Presets.summerCool), settings = Settings())
    private fun reject(settings: Settings) = assertTrue(BackupValidator.validate(bundle().copy(settings = settings)).isNotEmpty())

    @Test fun selectedAndSupervisedDevicesMustBeRealAddressesRatherThanHistorySentinels() {
        listOf("demo", "unknown").forEach { id -> reject(Settings(targetDevice = id)); reject(Settings(setupFanCheckedDevice = id)) }
    }

    @Test fun rememberedBedJetsAndCollapsedSectionsHaveStrictCollectionBounds() {
        reject(Settings(rememberedBedJets = List(17) { "ble:AA:BB:CC:DD:EE:%02X".format(it) }))
        reject(Settings(rememberedBedJets = listOf(device, device)))
        reject(Settings(rememberedBedJets = listOf(device.lowercase())))
        reject(Settings(rememberedBedJets = listOf("demo")))
        reject(Settings(collapsedTonightSections = setOf("unknown-section")))
        val encoded = """{"schema":2,"createdAt":"$at","profiles":[],"settings":{"collapsedTonightSections":["night","night"]}}"""
        assertThrows<BackupException> { BackupCodec.decode(encoded) }
    }

    @Test fun controlCharactersCannotEnterStoredProgrammeNamesOrAutomaticProfileIds() {
        assertTrue(BackupValidator.validate(bundle().copy(slotBackups = listOf(SlotBackup(device, 0,
            StoredProgram("Bad\u0000name", emptyList()), at)))).isNotEmpty())
        reject(Settings(platform = PlatformSettings(autoStart = AutoStartSettings(profileId = "profile\u0000name"))))
    }
}
