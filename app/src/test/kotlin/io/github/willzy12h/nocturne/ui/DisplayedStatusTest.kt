package io.github.willzy12h.nocturne.ui

import io.github.willzy12h.nocturne.core.link.SourcedStatus
import io.github.willzy12h.nocturne.core.protocol.DeviceStatus
import io.github.willzy12h.nocturne.core.protocol.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** P5: the screens show only the chosen BedJet's own status, never one from a session still on another unit. */
class DisplayedStatusTest {
    private val status = DeviceStatus(remainingSeconds = 600, actualTemperature = 60, targetTemperature = 70, mode = Mode.COOL,
        fanStep = 9, maxRuntimeMinutes = 600, minTemperature = 38, maxTemperature = 86, ambientTemperature = 44, lastShutdownReason = 0, extra = null)

    @Test fun aReportFromTheChosenBedJetIsShown() =
        assertEquals(status, displayedStatus(SourcedStatus("ble:AA:BB:CC:DD:EE:FF", status), "ble:aa:bb:cc:dd:ee:ff"))

    @Test fun aReportFromAnotherBedJetIsNotShown() =
        assertNull(displayedStatus(SourcedStatus("ble:11:22:33:44:55:66", status), "ble:AA:BB:CC:DD:EE:FF"))

    @Test fun anUnattributedReportFromTheDemoIsShown() = assertEquals(status, displayedStatus(SourcedStatus(null, status), "demo"))

    @Test fun noReportIsNothing() = assertNull(displayedStatus(null, "demo"))
}
