package io.github.willzy12h.nocturne.core.store

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class UatSetupChecklistTest {
    private val required = SetupItem.entries.filter { it.required }.toSet()
    @Test fun completionRemovesEachItemAndTheCardOnceRequiredSetupIsDone() {
        val settings = Settings()
        for (item in required) assertFalse(item in SetupSnapshot(setOf(item)).visible(settings))
        assertTrue(SetupSnapshot(required).requiredComplete)
        assertTrue(SetupSnapshot(required).visible(settings).isEmpty())
    }
    @Test fun revocationReturnsOnlyTheMissingRequiredItemAfterCompletion() {
        val settings = Settings(setupChecklistCompleted = true)
        for (item in required) assertEquals(listOf(item), SetupSnapshot(required - item).visible(settings), item.name)
    }
    @Test fun optionalDismissalsNeverHideRequiredChecks() {
        val settings = Settings(setupDismissedOptional = setOf("health", "widget", "bluetooth"))
        val shown = SetupSnapshot(emptySet()).visible(settings)
        assertEquals(required, shown.toSet())
        assertTrue(SetupItem.BLUETOOTH in shown)
    }
    @Test fun reopeningShowsRemainingOptionalSetupAndDismissalWorksInTheOpenCard() {
        val settings = Settings(setupChecklistCompleted = true)
        val snapshot = SetupSnapshot(required)
        assertEquals(listOf(SetupItem.HEALTH, SetupItem.WIDGET), snapshot.visible(settings, reopened = true))
        assertEquals(listOf(SetupItem.WIDGET), snapshot.visible(settings.copy(setupDismissedOptional = setOf("health")), true))
    }
    @Test fun demoDoesNotCompleteOrAutomaticallyShowRealDeviceSetup() {
        val state = SetupSnapshot(emptySet())
        assertFalse(state.requiredComplete)
        assertTrue(state.visible(Settings(demoMode = true)).isEmpty())
        assertTrue(SetupItem.PAIR in state.visible(Settings(demoMode = true), reopened = true))
    }
}
