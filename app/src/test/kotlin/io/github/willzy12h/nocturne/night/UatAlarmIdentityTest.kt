package io.github.willzy12h.nocturne.night

import io.github.willzy12h.nocturne.core.plan.AlarmPolicy
import io.github.willzy12h.nocturne.core.store.Settings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk = [31, 33, 34, 36])
class UatAlarmIdentityTest {
    @Test fun everyExplicitSamsungAliasHasTheSamsungClockLabel() {
        val context = RuntimeEnvironment.getApplication()
        samsungClockPackages.forEach { assertEquals("Samsung Clock", alarmSourceName(context, it)) }
    }
    @Test fun nullAndAndroidRemainNeutralWithoutLegitimateUidEvidence() {
        val context = RuntimeEnvironment.getApplication()
        for (raw in listOf(null, "", "android"))
            assertEquals("Phone alarm (app not identified)", alarmSourceName(context, raw))
        assertNull(identifiedAlarmCreator(null, null, listOf("com.sec.android.app.clockpackage"), false))
        assertNull(identifiedAlarmCreator(null, 1000, listOf("com.sec.android.app.clockpackage"), false))
    }
    @Test fun onlyOneNonSharedApplicationUidCanSupplyAMissingDisplayCreator() {
        val clock = "com.sec.android.app.clockpackage"
        assertEquals(clock, identifiedAlarmCreator(null, 10123, listOf(clock), false))
        assertNull(identifiedAlarmCreator(null, 10123, listOf(clock, "another.app"), false))
        assertNull(identifiedAlarmCreator(null, 10123, listOf(clock), true))
        assertNull(identifiedAlarmCreator(null, 10123, emptyList(), false))
    }
    @Test fun rawSystemOrDifferentCreatorsAreNeverRewrittenFromInstalledClockPackages() {
        val clock = listOf("com.sec.android.app.clockpackage")
        assertEquals("android", identifiedAlarmCreator("android", 10123, clock, false))
        assertEquals("other.clock", identifiedAlarmCreator("other.clock", 10123, clock, false))
    }
    @Test fun identificationDoesNotWidenSourceAcceptance() {
        val settings = Settings(acceptUnknownAlarmSource = false)
        val identified = identifiedAlarmCreator(null, 10123, listOf("com.sec.android.app.clockpackage"), false)
        assertNotNull(identified)
        assertFalse(AlarmPolicy(settings).acceptsSource(null))
        assertFalse(AlarmPolicy(settings).acceptsSource("android"))
    }
    @Test fun packageManagerLookupRejectsSharedOrMismatchedUidEvidenceOnEverySupportedApi() {
        val context = RuntimeEnvironment.getApplication()
        val pm = org.robolectric.Shadows.shadowOf(context.packageManager)
        val name = "com.example.clock"
        val info = android.content.pm.PackageInfo().apply {
            packageName = name
            applicationInfo = android.content.pm.ApplicationInfo().apply { packageName = name; uid = 10123 }
        }
        pm.installPackage(info)
        pm.setPackagesForUid(10123, name)
        assertEquals(name, identifyAlarmCreator(context, null, 10123))
        @Suppress("DEPRECATION") // Explicit legacy shared-UID fixture exercises the fail-closed check.
        fun markShared(value: String?) { info.sharedUserId = value }
        markShared("legacy.shared.uid")
        pm.installPackage(info)
        assertNull(identifyAlarmCreator(context, null, 10123))
        markShared(null)
        info.applicationInfo!!.uid = 10999
        pm.installPackage(info)
        assertNull(identifyAlarmCreator(context, null, 10123))
        assertEquals("android", identifyAlarmCreator(context, "android", 10123))
    }

}
