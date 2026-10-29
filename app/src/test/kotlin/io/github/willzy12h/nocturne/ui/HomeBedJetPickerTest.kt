package io.github.willzy12h.nocturne.ui

import android.os.Looper
import androidx.compose.ui.test.*
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.ble.AndroidBedJetLink
import io.github.willzy12h.nocturne.ble.FakeRadio
import io.github.willzy12h.nocturne.ble.GattRadio
import io.github.willzy12h.nocturne.ble.ScanHit
import io.github.willzy12h.nocturne.ble.ScanSpec
import io.github.willzy12h.nocturne.core.store.deviceId
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import io.github.willzy12h.nocturne.night.OutcomeKind
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.time.Duration

/** The radio is the external boundary; the picker, scan adapter, selection owner and repository remain real. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class HomeBedJetPickerTest {
    private val radio = FakeRadio().apply {
        advertisements = mapOf(ScanSpec.Service to listOf(ScanHit("AA:BB:CC:DD:EE:02", "BEDJET Bedroom", -40, true)))
    }
    private var openScans = 0
    private val observedRadio = object : GattRadio by radio.radio {
        override fun scan(spec: ScanSpec, onHit: (ScanHit) -> Unit, onFailed: (Int) -> Unit): AutoCloseable {
            val scan = radio.radio.scan(spec, onHit, onFailed)
            openScans++
            return AutoCloseable { scan.close(); openScans-- }
        }
    }
    private val ui = U06Fixture(U06Look.SIMPLE_MIDNIGHT, widthDp = 411, fontScale = 1f) { fixture, _ ->
        val graph = fixture.graph
        val adapter = AndroidBedJetLink(graph.context, observedRadio) { graph.repository.settings.value.deviceId() }
        ReflectionHelpers.setField(graph, "bleLink\$delegate", lazyOf(adapter))
        runBlocking { graph.repository.updateSettings { it.copy(demoMode = false, targetDevice = "ble:AA:BB:CC:DD:EE:01") } }
    }
    @get:Rule val rules = ui.rules

    @Test fun homeCanScanAndPairWithoutOpeningSettings() {
        ui.compose.onNodeWithText(ui.s(R.string.setup_find)).performScrollTo().assertIsDisplayed().performClick()
        ui.waitUntil { radio.scans.isNotEmpty() }
        radio.drain()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(11))
        ui.waitUntil { !ui.model.discovering.value }
        ui.compose.onNode(hasText("BEDJET Bedroom", substring = true) and hasClickAction()).performScrollTo().performClick()
        ui.waitUntil { ui.graph.repository.settings.value.targetDevice == "ble:AA:BB:CC:DD:EE:02" }
        ui.waitUntil { "AA:BB:CC:DD:EE:02" in radio.connects }
        assertFalse(ui.graph.repository.settings.value.demoMode)
        ui.compose.onNodeWithTag("tonight-fixed-wake").performScrollTo().assertIsDisplayed()
    }

    @Test fun homePickerKeepsBothRememberedBedJetsAndCanSwitchBack() {
        ui.compose.runOnIdle { runBlocking { ui.graph.actions.selectTarget("ble:AA:BB:CC:DD:EE:02", useRealDevice = true) } }
        val first = ui.compose.onNode(hasText("AA:BB:CC:DD:EE:01", substring = true) and hasClickAction())
        ui.checkControl(first, "Remembered BedJet").performClick()
        ui.waitUntil { ui.graph.repository.settings.value.targetDevice == "ble:AA:BB:CC:DD:EE:01" }
        ui.compose.onNode(hasText("AA:BB:CC:DD:EE:02", substring = true) and hasClickAction()).assertExists()
    }

    @Test fun demoHasAnExitAndReturnsToTheRememberedRealPicker() {
        ui.compose.runOnIdle { ui.model.useDemo() }
        ui.waitUntil { ui.graph.repository.settings.value.demoMode }
        ui.compose.onNodeWithText("Demo · no BedJet connected").assertIsDisplayed()
        ui.compose.onNodeWithText("Exit demo").assertIsDisplayed().performClick()
        ui.waitUntil { !ui.graph.repository.settings.value.demoMode }
        assertEquals("ble:AA:BB:CC:DD:EE:01", ui.graph.repository.settings.value.targetDevice)
        ui.compose.onNodeWithText(ui.s(R.string.setup_find)).performScrollTo().assertIsDisplayed()
    }

    @Test fun tryingDemoClosesAnInFlightHomeBluetoothScan() {
        ui.compose.runOnIdle { ui.model.discoverBedJets() }
        ui.waitUntil { openScans > 0 }
        ui.compose.runOnIdle { ui.model.useDemo() }
        ui.waitUntil { ui.graph.repository.settings.value.demoMode }
        assertEquals("Entering demo must release the real Bluetooth scan", 0, openScans)
        assertFalse(ui.model.discovering.value)
    }

    @Test fun demoCannotStartAnotherBluetoothScan() {
        ui.compose.runOnIdle { ui.model.useDemo() }
        ui.waitUntil { ui.graph.repository.settings.value.demoMode }
        val before = radio.scans.toList()
        ui.compose.runOnIdle { ui.model.discoverBedJets() }
        ui.compose.waitForIdle()
        assertEquals("Demo actions must never start BLE discovery", before, radio.scans.toList())
        assertFalse(ui.model.discovering.value)
    }

    @Test fun demoEntryIsRefusedWhileACommandOwnsTheBedJet() {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = ui.graph.scope.launch { ui.graph.actions.exclusive { entered.complete(Unit); release.await() } }
        try {
            ui.waitUntil { entered.isCompleted }
            val outcome = runBlocking { withTimeoutOrNull(200) { ui.graph.actions.setDemoMode(true) } }
            assertNotNull("Demo entry must refuse busy work instead of queueing a target switch", outcome)
            assertEquals(OutcomeKind.REFUSED, outcome!!.kind)
            assertFalse(ui.graph.repository.settings.value.demoMode)
        } finally { release.complete(Unit); holder.cancel() }
    }
}
