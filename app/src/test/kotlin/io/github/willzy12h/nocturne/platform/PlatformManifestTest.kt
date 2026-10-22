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
@RunWith(RobolectricTestRunner::class) @Config(sdk=[34])
class PlatformManifestTest : io.github.willzy12h.nocturne.platform.PlatformGraphFixture() {
    @Test fun backgroundAndTileEntrypointsRequireSystemPermissions() {
        val context:Context=RuntimeEnvironment.getApplication();val pm=context.packageManager
        fun service(name:String)=pm.getServiceInfo(ComponentName(context.packageName,"${context.packageName}.platform.$name"),0)
        assertEquals("android.permission.BIND_JOB_SERVICE",service("ChargerJobService").permission)
        assertEquals("android.permission.BIND_QUICK_SETTINGS_TILE",service("NightTileService").permission)
        assertFalse(pm.getActivityInfo(ComponentName(context.packageName,"${context.packageName}.platform.PlatformActionActivity"),0).exported)
    }
}
