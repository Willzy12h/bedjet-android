package io.github.willzy12h.nocturne.platform
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class) @Config(sdk=[31, 33, 34, 36])
class PlatformManifestTest : io.github.willzy12h.nocturne.platform.PlatformGraphFixture() {
    @Test fun applicationManifestReceiversAreRegisteredForBroadcastDelivery() {
        val context = RuntimeEnvironment.getApplication()
        val registered = org.robolectric.Shadows.shadowOf(context).registeredReceivers
            .map { it.broadcastReceiver.javaClass.name }.toSet()
        val receivers = listOf("platform.PlatformClockReceiver", "night.BootReceiver",
            "night.AlarmChangedReceiver", "widget.QuickControlsReceiver", "widget.SingleActionReceiver")
        receivers.forEach { receiver ->
            val name = "${context.packageName}.$receiver"
            context.packageManager.getReceiverInfo(ComponentName(context.packageName, name), 0)
            assertTrue("The merged unit-test manifest must register $name", name in registered)
        }
    }

    @Test fun backgroundAndTileEntrypointsRequireSystemPermissions() {
        val context:Context=RuntimeEnvironment.getApplication();val pm=context.packageManager
        fun service(name:String)=pm.getServiceInfo(ComponentName(context.packageName,"${context.packageName}.platform.$name"),0)
        assertEquals("android.permission.BIND_JOB_SERVICE",service("ChargerJobService").permission)
        assertEquals("android.permission.BIND_QUICK_SETTINGS_TILE",service("NightTileService").permission)
        assertFalse(pm.getActivityInfo(ComponentName(context.packageName,"${context.packageName}.platform.PlatformActionActivity"),0).exported)
    }
}
