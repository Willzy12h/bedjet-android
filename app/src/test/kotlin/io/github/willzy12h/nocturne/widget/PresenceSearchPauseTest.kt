package io.github.willzy12h.nocturne.widget

import io.github.willzy12h.nocturne.App
import io.github.willzy12h.nocturne.GraphLifecycle
import io.github.willzy12h.nocturne.night.NightActions.PairingPause
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * A connected BedJet stops advertising, so Android's companion-device search cannot find it. Nocturne lets go of it
 * for the search, without interrupting a command or undoing a release the person made for another phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PresenceSearchPauseTest {
    @get:Rule val graphs = GraphLifecycle()
    private val app get() = RuntimeEnvironment.getApplication() as App

    @Before fun chooseARealBedJet() {
        runBlocking { app.graph.repository.updateSettings { it.copy(targetDevice = "ble:AA:BB:CC:DD:EE:FF", demoMode = false) } }
    }

    @Test fun theSearchLetsGoOfTheBedJetAndTakesItBack() = runBlocking {
        assertEquals(PairingPause.PAUSED, app.graph.actions.pauseForPairing(WidgetPresence.SEARCH_PAUSE_MS))
        assertTrue("Android can only find a BedJet that is not connected", app.graph.sessions.released.value)
        app.graph.actions.endPairingPause()
        assertFalse("Nocturne takes the BedJet back once the prompt ends", app.graph.sessions.released.value)
    }

    @Test fun aReleaseForAnotherPhoneIsLeftAsItIs() = runBlocking {
        app.graph.sessions.suspendAutoConnect(null).join()
        assertEquals(PairingPause.ALREADY_FREE, app.graph.actions.pauseForPairing(WidgetPresence.SEARCH_PAUSE_MS))
        // No search pause was taken, so returning from the prompt must not take the BedJet back from the other phone.
        WidgetPresence.endSearchPause(app)
        assertTrue(app.graph.sessions.released.value)
    }

    @Test fun anImportUnderWayIsNotInterrupted() = runBlocking {
        val importing = requireNotNull(app.graph.repository.operationAdmission.tryImport())
        try {
            assertEquals(PairingPause.BUSY, app.graph.actions.pauseForPairing(WidgetPresence.SEARCH_PAUSE_MS))
            assertFalse(app.graph.sessions.released.value)
        } finally { importing.close() }
    }

    @Test fun demoModeHasNothingToLetGoOf() = runBlocking {
        app.graph.repository.updateSettings { it.copy(demoMode = true) }
        assertEquals(PairingPause.ALREADY_FREE, app.graph.actions.pauseForPairing(WidgetPresence.SEARCH_PAUSE_MS))
        assertFalse(app.graph.sessions.released.value)
    }
}
