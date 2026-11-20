package io.github.willzy12h.nocturne.core.store

import io.github.willzy12h.nocturne.core.protocol.Mode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ManualControlsTest {
    private val unit = "ble:AA:BB:CC:DD:EE:FF"

    @Test fun manualChoicesRoundTripWithoutChangingPresetsOrOtherModes() {
        val settings = Settings().rememberManual(ManualControls(unit, Mode.COOL, temperature = 46))
            .rememberManual(ManualControls(unit, Mode.COOL, fanStep = 10))
            .rememberManual(ManualControls(unit, Mode.HEAT, fanStep = 3))
        val decoded = BackupCodec.json.decodeFromString(Settings.serializer(),
            BackupCodec.json.encodeToString(Settings.serializer(), settings))
        assertEquals(ManualControls(unit, Mode.COOL, 46, 10), decoded.manualFor(unit, Mode.COOL))
        assertNull(decoded.manualFor("ble:00:11:22:33:44:55", Mode.COOL))
        assertNull(decoded.manualFor(unit, Mode.HEAT)?.temperature)
        assertEquals(Settings().quickDefaults, decoded.quickDefaults)
        assertTrue(decoded.withDefaultControls().manualControls.isEmpty())
        assertEquals(decoded, decoded.rememberManual(ManualControls("demo", Mode.COOL, fanStep = 5)))
    }
}
