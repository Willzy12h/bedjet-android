package io.github.willzy12h.nocturne.platform

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Which maker's battery tips a phone gets, and which settings screen "Open settings" reaches. */
@RunWith(RobolectricTestRunner::class) @Config(sdk = [34])
class PhoneMakerTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test fun makersAreRecognisedByManufacturerOrBrand() {
        assertEquals(PhoneMaker.SAMSUNG, PhoneMaker.of("samsung", "samsung"))
        assertEquals(PhoneMaker.HONOR, PhoneMaker.of("HONOR", "HONOR"))
        // An Honor made before the split reports Huawei as its manufacturer.
        assertEquals(PhoneMaker.HONOR, PhoneMaker.of("HUAWEI", "HONOR"))
        assertEquals(PhoneMaker.HUAWEI, PhoneMaker.of("HUAWEI", "HUAWEI"))
        assertEquals(PhoneMaker.XIAOMI, PhoneMaker.of("Xiaomi", "Redmi"))
        assertEquals(PhoneMaker.XIAOMI, PhoneMaker.of("Xiaomi", "POCO"))
        assertEquals(PhoneMaker.OPPO, PhoneMaker.of("OnePlus", "OnePlus"))
        assertEquals(PhoneMaker.OPPO, PhoneMaker.of("realme", "realme"))
        assertEquals(PhoneMaker.OPPO, PhoneMaker.of("OPPO", "OPPO"))
        assertEquals(PhoneMaker.VIVO, PhoneMaker.of("vivo", "iQOO"))
        assertNull(PhoneMaker.of("Google", "google"))
        assertNull(PhoneMaker.of(null, null))
    }

    @Test fun theMakersOwnScreenOpensWhenThePhoneHasIt() {
        shadowOf(context).checkActivities(true)
        val second = PhoneMaker.HONOR.screens[1]
        shadowOf(context.packageManager).addOrUpdateActivity(ActivityInfo().apply {
            packageName = second.packageName; name = second.className; exported = true
        })
        assertEquals(second, PhoneMaker.open(context, PhoneMaker.HONOR))
        val started = shadowOf(context).nextStartedActivity
        assertEquals(second, started.component)
        assertEquals(context.packageName, started.getStringExtra("package_name"))
    }

    @Test fun nocturnesAppPageOpensWhenNoMakerScreenIsThere() {
        shadowOf(context).checkActivities(true)
        shadowOf(context.packageManager).addActivityIfNotPresent(ComponentName("com.android.settings", "AppDetails"))
        shadowOf(context.packageManager).addIntentFilterForActivity(ComponentName("com.android.settings", "AppDetails"),
            android.content.IntentFilter(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                addCategory(Intent.CATEGORY_DEFAULT); addDataScheme("package") })
        for (maker in PhoneMaker.entries) {
            assertNull("$maker", PhoneMaker.open(context, maker))
            val started = shadowOf(context).nextStartedActivity
            assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, started.action)
            assertEquals("package:${context.packageName}", started.dataString)
            assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, started.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
