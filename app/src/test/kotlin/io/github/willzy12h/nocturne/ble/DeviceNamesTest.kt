package io.github.willzy12h.nocturne.ble
import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class) @Config(sdk=[34])
class DeviceNamesTest : io.github.willzy12h.nocturne.platform.PlatformGraphFixture() {
    @Test fun namePairsSurviveRestartRemainTargetBoundAndForgetClearsThem() {
        val context:Context=RuntimeEnvironment.getApplication()
        var target:String?="ble:AA:BB:CC:DD:EE:FF"
        val a=target!!;val b="ble:11:22:33:44:55:66"
        context.getSharedPreferences("ble",Context.MODE_PRIVATE).edit().putString("name","legacy unbound").commit()
        val names=DeviceNames(context){target}
        assertNull(names.verifiedName(a))
        names.display(a,"query display");names.verify(a,"Exact GATT Name ")
        assertEquals("Exact GATT Name ",DeviceNames(context){target}.verifiedName(a))
        target=b
        assertNull(names.displayName());assertNull(names.verifiedName(b))
        names.display(b,"new display");names.verify(b,null)
        assertNull(names.verifiedName(b));assertEquals("new display",names.displayName())
        names.forget()
        assertNull(names.displayName());assertNull(DeviceNames(context){target}.verifiedName(a))
    }
}
