package io.github.willzy12h.nocturne.platform

import io.github.willzy12h.nocturne.core.link.DeviceId
import io.github.willzy12h.nocturne.core.store.NightRecord
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import java.time.Instant

@RunWith(RobolectricTestRunner::class) @Config(sdk = [34])
class HealthDemoIsolationTest : PlatformGraphFixture() {
    @Test fun demoHistoryNeverConstructsAHealthConnectClient() = runBlocking {
        var constructed = false
        val source = object : SleepSource {
            override suspend fun permitted() = true
            override suspend fun sessions(start: Instant, end: Instant) = emptyList<SleepStage>()
        }
        val record = NightRecord("demo-night", "p", "Demo", "2026-10-26T22:30Z", "2026-10-27T07:00Z",
            false, deviceId = DeviceId.DEMO)
        val result = SleepOverlayReader({ true }) { constructed = true; source }.read(record)
        assertEquals(SleepAvailability.EMPTY, result.availability)
        assertFalse("Demo must not read real Health Connect data", constructed)
    }

    @Test fun permissionRationaleExplainsTheOptionalWriteAndItsUnverifiedSleepMeaning() {
        val activity = Robolectric.buildActivity(HealthRationaleActivity::class.java).create()
        try {
            val message = shadowOf(ShadowAlertDialog.getLatestAlertDialog()).message.toString()
            assertTrue("Permission rationale must disclose optional programme writes", message.contains("writes", ignoreCase = true))
            assertTrue("A BedJet programme is not verified sleep", message.contains("not verified sleep", ignoreCase = true))
        } finally { activity.destroy() }
    }
}
