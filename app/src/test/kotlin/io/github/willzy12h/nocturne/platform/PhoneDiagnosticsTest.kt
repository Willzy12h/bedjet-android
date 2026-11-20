package io.github.willzy12h.nocturne.platform

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.PowerManager
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowActivityManager.ApplicationExitInfoBuilder

/** The phone section of the diagnostics export: whether a phone maker took the battery exemption or force-stopped us. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhoneDiagnosticsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test fun reportsTheBackgroundStateAndWhyAndroidLastClosedTheApp() {
        shadowOf(context.getSystemService(PowerManager::class.java)).setIgnoringBatteryOptimizations(context.packageName, false)
        val activity = context.getSystemService(ActivityManager::class.java)
        shadowOf(activity).setBackgroundRestricted(true)
        shadowOf(context.getSystemService(UsageStatsManager::class.java))
            .setCurrentAppStandbyBucket(UsageStatsManager.STANDBY_BUCKET_RESTRICTED)
        shadowOf(activity).addApplicationExitInfo(ApplicationExitInfoBuilder.newBuilder()
            .setProcessName(context.packageName).setReason(ApplicationExitInfo.REASON_USER_REQUESTED)
            .setTimestamp(1_763_420_400_000).setDescription("stop by power manager").build())

        val phone = PhoneDiagnostics.snapshot(context)

        assertEquals(false, phone["ignoringBatteryOptimizations"]!!.jsonPrimitive.boolean)
        assertEquals(true, phone["backgroundRestricted"]!!.jsonPrimitive.boolean)
        assertEquals("RESTRICTED", phone["standbyBucket"]!!.jsonPrimitive.content)
        val exit = phone["recentExits"]!!.jsonArray.single().jsonObject
        assertEquals("USER_REQUESTED", exit["reason"]!!.jsonPrimitive.content)
        assertEquals(false, exit.containsKey("at"))
        assertEquals(false, exit.containsKey("description"))
    }

    @Test fun namesUnknownReasonsAndBucketsRatherThanFailing() {
        assertEquals("UNKNOWN_999", PhoneDiagnostics.reasonName(999))
        assertEquals("OTHER_99", PhoneDiagnostics.bucketName(99))
    }
}
