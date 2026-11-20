package io.github.willzy12h.nocturne.data

import android.app.Application
import android.content.ContextWrapper
import io.github.willzy12h.nocturne.core.plan.Presets
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.store.ManualControls
import io.github.willzy12h.nocturne.core.store.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.time.ZonedDateTime

/** Putting built-in profiles and the controls back to how they ship, and knowing when a start found nothing saved. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RepositoryResetTest {
    @get:Rule val temp = TemporaryFolder()
    private val scopes = mutableListOf<CoroutineScope>()
    private val at = ZonedDateTime.parse("2026-11-17T22:30Z")
    private val context by lazy {
        object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir(): File = temp.root
        }
    }

    private fun repository(): Repository {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).also(scopes::add)
        return Repository(context, scope, writer = Dispatchers.Unconfined, now = { at })
    }

    @After fun finish() = scopes.forEach { it.cancel() }

    @Test fun aStartWithNothingSavedIsEmptyAndOneWithSavedDataIsNot() = runBlocking {
        val first = repository()
        assertTrue(first.startedEmpty)
        first.updateSettings { it.copy(fahrenheit = true) }
        scopes.forEach { it.cancel() }
        assertFalse(repository().startedEmpty)
    }

    @Test fun restoringBuiltInProfilesKeepsOwnProfilesAndDaysAndSnapshotsFirst() = runBlocking {
        val repo = repository()
        val summer = repo.profiles.value.first()
        val changed = summer.copy(name = "Mine now", days = setOf(6, 7), middle = summer.middle.copy(fanStart = 20))
        val own = summer.copy(id = "own-1", name = "My night", days = setOf(1))
        repo.saveProfile(changed)
        repo.saveProfile(own)
        repo.deleteProfile(Presets.all.last().id)

        repo.restoreBuiltInProfiles()

        val profiles = repo.profiles.value
        assertEquals(Presets.byId(summer.id)!!.copy(days = setOf(6, 7)), profiles.first { it.id == summer.id })
        assertEquals(own, profiles.first { it.id == "own-1" })
        assertTrue(Presets.all.all { builtIn -> profiles.any { it.id == builtIn.id } })
        val snapshot = repo.snapshotList.value.single { it.manual }
        assertEquals("Before reset", snapshot.label)
        // The snapshot holds what was there before, so the reset can be undone.
        assertEquals(changed, repo.snapshots.load(snapshot.id)!!.profiles.first { it.id == summer.id })
        scopes.forEach { it.cancel() }
        assertEquals("The reset is saved", profiles, repository().profiles.value)
    }

    @Test fun resettingControlsKeepsEverythingElse() = runBlocking {
        val repo = repository()
        val device = "ble:AA:BB:CC:DD:EE:FF"
        val remembered = ManualControls(device, Mode.COOL, temperature = 46, fanStep = 10)
        repo.updateSettings { it.copy(fahrenheit = true, targetDevice = device, boostMinutes = 25,
            snoozeWarm = it.snoozeWarm.copy(minutes = 3), manualControls = listOf(remembered)) }
        val before = repo.settings.value
        repo.snapshotBeforeReset()
        repo.updateSettings(Settings::withDefaultControls)
        val settings = repo.settings.value
        assertTrue(settings.controlsAreDefault)
        assertTrue(settings.fahrenheit)
        assertEquals(device, settings.targetDevice)
        assertTrue(settings.manualControls.isEmpty())
        val snapshot = repo.snapshotList.value.single { it.manual }
        assertEquals(before, repo.snapshots.load(snapshot.id)!!.settings)
        assertTrue(repo.restoreSnapshot(snapshot.id))
        assertEquals(listOf(remembered), repo.settings.value.manualControls)
        assertEquals(25, repo.settings.value.boostMinutes)
    }
}
