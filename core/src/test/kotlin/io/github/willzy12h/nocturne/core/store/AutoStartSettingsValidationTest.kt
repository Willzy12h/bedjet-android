package io.github.willzy12h.nocturne.core.store

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** Invalid imported/locally decoded automation input must never reach a hardware schedule. */
class AutoStartSettingsValidationTest {
    private fun settings(extra: String): PlatformSettings = BackupCodec.json.decodeFromString(
        PlatformSettings.serializer(), """{"autoStart":{"enabled":true,"profileId":"profile",
            "deviceId":"ble:AA:BB:CC:DD:EE:FF",$extra}}""")

    @Test fun `reject fixed clock minutes outside a civil day`() {
        for (value in listOf(-1, 1440, Int.MAX_VALUE)) {
            assertTrue(settings("\"fixedMinute\":$value").validationErrors().isNotEmpty(), "minute=$value")
        }
    }
    @Test fun `relative offset respects the one to eighteen hour start window`() {
        for (value in listOf(-1, 0, 59, 1081, Int.MAX_VALUE)) {
            assertTrue(settings("\"mode\":\"BEFORE_WAKE\",\"minutesBeforeWake\":$value").validationErrors().isNotEmpty(), "offset=$value")
        }
    }
    @Test fun `invalid device association is refused`() {
        val prefs = BackupCodec.json.decodeFromString(PlatformSettings.serializer(),
            """{"autoStart":{"enabled":true,"profileId":"profile","deviceId":"demo"}}""")
        assertTrue(prefs.validationErrors().isNotEmpty())
    }
    @Test fun `oversized profile identifier is refused`() {
        val prefs = BackupCodec.json.decodeFromString(PlatformSettings.serializer(),
            """{"autoStart":{"enabled":true,"profileId":"${"p".repeat(129)}","deviceId":"ble:AA:BB:CC:DD:EE:FF"}}""")
        assertTrue(prefs.validationErrors().isNotEmpty())
    }
    @Test fun `empty profile association cannot enable automatic control`() {
        val prefs = BackupCodec.json.decodeFromString(PlatformSettings.serializer(),
            """{"autoStart":{"enabled":true,"profileId":"","deviceId":"ble:AA:BB:CC:DD:EE:FF"}}""")
        assertTrue(prefs.validationErrors().isNotEmpty())
    }
    @Test fun `valid boundary offsets remain accepted`() {
        for (value in listOf(60, 480, 1080)) {
            assertTrue(settings("\"mode\":\"BEFORE_WAKE\",\"minutesBeforeWake\":$value").validationErrors().isEmpty())
        }
    }
}
