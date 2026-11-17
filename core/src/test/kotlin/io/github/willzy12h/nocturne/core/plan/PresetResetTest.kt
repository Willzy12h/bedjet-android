package io.github.willzy12h.nocturne.core.plan

import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.store.ActionPreset
import io.github.willzy12h.nocturne.core.store.QuickDefaults
import io.github.willzy12h.nocturne.core.store.Settings
import io.github.willzy12h.nocturne.core.store.WakeAction
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue

class PresetResetTest {
    private val edited = Presets.summerCool.copy(name = "Mine", days = setOf(1, 2),
        middle = Presets.summerCool.middle.copy(fanStart = 80, tempStart = 50))
    private val own = Presets.winterWarm.copy(id = "own-1", name = "Own")

    @Test fun `a built-in profile resets to its default and keeps its days`() {
        assertTrue(Presets.isChanged(edited))
        assertEquals(Presets.summerCool.copy(days = setOf(1, 2)), Presets.reset(edited))
        assertFalse(Presets.isChanged(Presets.summerCool.copy(days = setOf(5))))
    }

    @Test fun `a profile the person made has no default`() {
        assertNull(Presets.reset(own))
        assertFalse(Presets.isChanged(own))
    }

    @Test fun `restoring puts back changed and deleted built-ins and keeps the person's own`() {
        val list = listOf(own, edited, Presets.hotSleeper)
        val restored = Presets.restore(list)
        assertEquals(listOf(own, Presets.summerCool.copy(days = setOf(1, 2)), Presets.hotSleeper,
            Presets.winterWarm, Presets.springAutumn, Presets.warmStartCoolNight), restored)
        assertTrue(Presets.canRestore(list))
        assertFalse(Presets.canRestore(restored))
        assertFalse(Presets.canRestore(Presets.all))
    }

    @Test fun `controls go back to their defaults and nothing else changes`() {
        val custom = Settings(boostMinutes = 40, snoozeWarm = ActionPreset(Mode.HEAT, 70, 60, 5),
            extendWarm = ActionPreset(Mode.HEAT, 70, 60, 50), notificationActions = listOf(WakeAction.OFF),
            quickDefaults = listOf(ActionPreset(Mode.DRY, 56, 80, 60)), fahrenheit = true, workingSlot = 1,
            targetDevice = "ble:AA:BB:CC:DD:EE:FF")
        val reset = custom.withDefaultControls()
        val defaults = Settings()
        assertEquals(defaults.boostMinutes, reset.boostMinutes)
        assertEquals(defaults.snoozeWarm, reset.snoozeWarm)
        assertEquals(defaults.extendWarm, reset.extendWarm)
        assertEquals(defaults.notificationActions, reset.notificationActions)
        QuickDefaults.modes.forEach { assertEquals(QuickDefaults.builtIn(it), QuickDefaults.resolve(reset, it)) }
        assertEquals(Triple(true, 1, "ble:AA:BB:CC:DD:EE:FF"), Triple(reset.fahrenheit, reset.workingSlot, reset.targetDevice))
        assertFalse(custom.controlsAreDefault)
        assertTrue(reset.controlsAreDefault)
        assertTrue(Settings().controlsAreDefault, "A new install's controls are already the defaults")
    }
}
