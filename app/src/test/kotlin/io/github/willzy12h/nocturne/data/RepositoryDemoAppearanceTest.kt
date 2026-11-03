package io.github.willzy12h.nocturne.data

import android.app.Application
import android.content.ContextWrapper
import io.github.willzy12h.nocturne.core.store.Palette
import io.github.willzy12h.nocturne.core.store.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.time.ZonedDateTime

/** Demo inherits appearance on entry while keeping every later preference edit disposable. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RepositoryDemoAppearanceTest {
    @get:Rule val temp = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val context by lazy {
        object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir(): File = temp.root
        }
    }
    private fun repository() = Repository(context, scope, writer = Dispatchers.Unconfined,
        now = { ZonedDateTime.parse("2026-10-29T22:30Z") })

    @After fun finish() = scope.cancel()

    @Test fun demoInheritsTheSelectedNightPalette() = runBlocking {
        val repo = repository()
        repo.updateSettings { it.copy(themeMode = ThemeMode.DARK, nightPalette = Palette.FOREST) }

        repo.enterDemoSession()

        assertEquals(Palette.FOREST, repo.settings.value.nightPalette)
    }

    @Test fun demoInheritsTheSelectedDayPalette() = runBlocking {
        val repo = repository()
        repo.updateSettings { it.copy(themeMode = ThemeMode.LIGHT, dayPalette = Palette.PLUM) }

        repo.enterDemoSession()

        assertEquals(Palette.PLUM, repo.settings.value.dayPalette)
    }

    @Test fun demoPaletteEditsLeavePersistedRealAppearanceUntouched() = runBlocking {
        val repo = repository()
        repo.updateSettings { it.copy(themeMode = ThemeMode.SCHEDULED,
            nightPalette = Palette.FOREST, dayPalette = Palette.PLUM) }
        val real = repo.settings.value
        val saved = File(temp.root, "settings.json").readBytes()
        repo.enterDemoSession()

        repo.updateSettings { it.copy(nightPalette = Palette.GRAPHITE, dayPalette = Palette.OVERCAST) }

        assertTrue(repo.isDemoSession)
        assertEquals(Palette.GRAPHITE, repo.settings.value.nightPalette)
        assertEquals(Palette.OVERCAST, repo.settings.value.dayPalette)
        assertArrayEquals("Demo palette changes cannot replace real saved preferences", saved,
            File(temp.root, "settings.json").readBytes())
        repo.exitDemoSession()
        assertEquals(real, repo.settings.value)
        assertArrayEquals(saved, File(temp.root, "settings.json").readBytes())
    }
}
