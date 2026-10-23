package io.github.willzy12h.nocturne.core.link

import io.github.willzy12h.nocturne.core.store.BackupCodec
import io.github.willzy12h.nocturne.core.store.Settings
import io.github.willzy12h.nocturne.core.store.deviceId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DeviceIdTest {
    @Test
    fun bleIdsCarryTheAddress() {
        val id = DeviceId.ble("aa:bb:cc:dd:ee:ff")
        assertEquals("ble:AA:BB:CC:DD:EE:FF", id)
        assertEquals("AA:BB:CC:DD:EE:FF", DeviceId.address(id))
        assertNull(DeviceId.address(DeviceId.DEMO))
        assertTrue(DeviceId.isDemo(DeviceId.DEMO))
    }

    @Test
    fun settingsResolveTheEffectiveTarget() {
        assertNull(Settings().deviceId())
        assertEquals(DeviceId.DEMO, Settings(demoMode = true, targetDevice = "ble:X").deviceId())
        assertEquals("ble:X", Settings(targetDevice = "ble:X").deviceId())
    }

    @Test
    fun settingsSavedBeforeTargetDeviceStillLoad() {
        val old = """{"workingSlot":2,"demoMode":false}"""
        assertNull(BackupCodec.json.decodeFromString(Settings.serializer(), old).targetDevice)
    }
}
