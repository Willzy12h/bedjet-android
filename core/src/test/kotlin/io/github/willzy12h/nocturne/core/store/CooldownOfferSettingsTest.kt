package io.github.willzy12h.nocturne.core.store

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CooldownOfferSettingsTest {
    private fun encodedSettings(bundle: BackupBundle) = BackupCodec.json.parseToJsonElement(BackupCodec.encode(bundle))
        .jsonObject.getValue("settings").jsonObject

    @Test fun olderSettingsDefaultToUnanswered() {
        val old = BackupCodec.decode("""{"schema":2,"createdAt":"2026-10-27T12:00Z","profiles":[],"settings":{}}""")
        assertEquals("false", encodedSettings(old).getValue("cooldownOfferAnswered").jsonPrimitive.content)
    }

    @Test fun answeredOfferSurvivesBackupExportAndImport() {
        val imported = BackupCodec.decode("""{"schema":2,"createdAt":"2026-10-27T12:00Z","profiles":[],"settings":{"cooldownOfferAnswered":true}}""")
        val restored = BackupCodec.decode(BackupCodec.encode(imported))
        assertEquals("true", encodedSettings(restored).getValue("cooldownOfferAnswered").jsonPrimitive.content)
    }
}
