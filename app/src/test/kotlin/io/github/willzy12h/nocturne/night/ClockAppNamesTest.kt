package io.github.willzy12h.nocturne.night

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import android.provider.AlarmClock
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Samsung and Google both call their app "Clock"; the alarm app choice must say which is which. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ClockAppNamesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun clockApp(packageName: String, label: String) {
        val app = ApplicationInfo().apply { this.packageName = packageName; nonLocalizedLabel = label; name = label }
        shadowOf(context.packageManager).installPackage(PackageInfo().apply { this.packageName = packageName; applicationInfo = app })
        val resolve = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply { this.packageName = packageName; name = "$packageName.Alarms"; applicationInfo = app }
        }
        shadowOf(context.packageManager).addResolveInfoForIntent(Intent(AlarmClock.ACTION_SET_ALARM), resolve)
    }

    @Test fun samsungAndGoogleClocksAreNamedLikeTonight() {
        clockApp("com.sec.android.app.clockpackage", "Clock")
        clockApp("com.google.android.deskclock", "Clock")
        assertEquals(setOf("Samsung Clock", "Google Clock"), ClockApps.installed(context).map { it.label }.toSet())
    }

    @Test fun otherAppsThatShareANameShowTheirPackage() {
        clockApp("com.example.clock", "Clock")
        clockApp("org.other.clock", "Clock")
        clockApp("com.example.alarmy", "Alarmy")
        assertEquals(setOf("Clock (com.example.clock)", "Clock (org.other.clock)", "Alarmy"),
            ClockApps.installed(context).map { it.label }.toSet())
    }
}
