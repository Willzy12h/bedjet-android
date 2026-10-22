package io.github.willzy12h.nocturne.platform
import android.content.Context
import io.github.willzy12h.nocturne.graph
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk=[34])
class PlatformRestoreTest : io.github.willzy12h.nocturne.platform.PlatformGraphFixture() {
    @Test fun portableRestoreAlwaysDisablesAutomaticHardwareStart() = runBlocking {
        val context: Context = RuntimeEnvironment.getApplication()
        val repo=context.graph.repository
        val bundle=repo.bundle().let { it.copy(settings=it.settings.copy(platform=it.settings.platform.copy(chargerAutoStart=true, chargerDevice="ble:AA:BB:CC:DD:EE:FF"))) }
        repo.restore(bundle)
        assertFalse(repo.settings.value.platform.chargerAutoStart)
    }
    @Test fun invalidReminderSettingsAreRejectedBeforeRestore() = runBlocking {
        val repo=(RuntimeEnvironment.getApplication() as Context).graph.repository
        val bundle=repo.bundle().let { it.copy(settings=it.settings.copy(platform=it.settings.platform.copy(reminderMinutes=-1))) }
        assertTrue(io.github.willzy12h.nocturne.core.store.BackupValidator.validate(bundle).isNotEmpty())
    }
}
