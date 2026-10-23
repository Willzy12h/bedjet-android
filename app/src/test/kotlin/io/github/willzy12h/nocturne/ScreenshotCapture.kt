package io.github.willzy12h.nocturne

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.willzy12h.nocturne.core.store.ThemeMode
import io.github.willzy12h.nocturne.ui.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.time.Duration
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.runBlocking

/**
 * Renders the real app's screens to PNG for before/after comparison. Off unless SCREENSHOT_TAG is set, e.g.
 * `SCREENSHOT_TAG=after ./gradlew :app:testDebugUnitTest --tests '*ScreenshotCapture*' --rerun-tasks`. Images land in
 * `app/build/screenshots/<tag>/`. Rendered by Robolectric's native graphics on the JVM against the demo BedJet:
 * real Compose layout and drawing, not a device, and not Bluetooth evidence.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ScreenshotCapture {
    private val tag = System.getenv("SCREENSHOT_TAG")
    private val captureInstant = Instant.parse("2026-10-22T22:30:00Z")
    private val captureZone = ZoneId.of("Europe/London")
    private var expectedWidthDp = 411
    private var expectedFontScale = 1f
    private val compose = createAndroidComposeRule<MainActivity>()
    private val pinClock = TestRule { base, description ->
        object : org.junit.runners.model.Statement() {
          override fun evaluate() {
            assumeTrue("set SCREENSHOT_TAG to capture", !tag.isNullOrBlank())
            require(tag!!.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]*"))) { "SCREENSHOT_TAG must be a simple directory name" }
            val app = RuntimeEnvironment.getApplication() as App
            val oldClock = app.graph.clock
            val oldScale = app.resources.configuration.fontScale
            app.graph.clock = Clock.fixed(captureInstant, captureZone)
            val combined = description.methodName.contains("combined320And200")
            expectedWidthDp = if (combined) 320 else 411
            expectedFontScale = if (combined) 2f else 1f
            if (oldScale != expectedFontScale) {
                val config = android.content.res.Configuration(app.resources.configuration).apply { fontScale = expectedFontScale }
                @Suppress("DEPRECATION")
                app.resources.updateConfiguration(config, app.resources.displayMetrics)
            }
            try { base.evaluate() } finally {
                app.graph.clock = oldClock
                if (app.resources.configuration.fontScale != oldScale) {
                    val config = android.content.res.Configuration(app.resources.configuration).apply { fontScale = oldScale }
                    @Suppress("DEPRECATION")
                    app.resources.updateConfiguration(config, app.resources.displayMetrics)
                }
            }
          }
        }
    }
    @get:Rule val rules: TestRule = RuleChain.outerRule(pinClock).around(compose)

    private val graph get() = compose.activity.graph

    @Test fun dark() = capture("dark", ThemeMode.DARK, simple = false)
    @Test fun light() = capture("light", ThemeMode.LIGHT, simple = false)
    @Test fun simpleDark() = capture("simple-dark", ThemeMode.DARK, simple = true)
    @Test fun simpleLight() = capture("simple-light", ThemeMode.LIGHT, simple = true)
    @Test @Config(qualifiers = "w320dp-h891dp-xxhdpi")
    fun combined320And200() = capture("combined-320dp-200pct", ThemeMode.DARK, simple = false)

    @Test fun featureViews() {
        compose.runOnUiThread { runBlocking {
            graph.repository.updateSettings { it.copy(demoMode = true, themeMode = ThemeMode.DARK) }
            graph.repository.setActiveNight(null)
        } }
        settle()
        compose.onAllNodesWithText("More")[0].performScrollTo().performClick()
        settle()
        shot("features-01-idle-handover")
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        settle()
        compose.onAllNodesWithText("Settings")[0].performClick()
        compose.onAllNodesWithText("Bedtime reminder")[0].performScrollTo()
        settle()
        shot("features-02-platform-settings")
        compose.onAllNodesWithText("History")[0].performClick()
        settle()
        shot("features-03-history-export")
    }

    private fun capture(look: String, theme: ThemeMode, simple: Boolean) {
        compose.runOnUiThread {
            runBlocking {
                graph.repository.updateSettings { it.copy(demoMode = true, themeMode = theme, simpleView = simple) }
                graph.repository.setActiveNight(null)
            }
        }
        settle()
        shot("$look-01-tonight")
        compose.onAllNodesWithText("Off")[0].performScrollTo()
        settle()
        shot("$look-01b-tonight-lower")
        for ((tab, name) in listOf("Profiles" to "02-profiles", "Remote" to "03-remote", "History" to "04-history", "Settings" to "05-settings")) {
            if (look.startsWith("combined-")) compose.onAllNodes(hasContentDescription(tab))[0].performClick()
            else compose.onAllNodesWithText(tab)[0].performClick()
            settle()
            shot("$look-$name")
        }
        if (look.startsWith("combined-")) compose.onAllNodes(hasContentDescription("Tonight"))[0].performClick()
        else compose.onAllNodesWithText("Tonight")[0].performClick()
        settle()
        compose.onAllNodesWithText("Start night now")[0].performScrollTo().performClick()
        compose.waitUntil(15_000) {
            settle(250)
            compose.onAllNodes(hasText("End night")).fetchSemanticsNodes().isNotEmpty()
        }
        settle(6_000) // let the confirmation message bar time out
        shot("$look-06-tonight-running")
    }

    private fun settle(ms: Long = 500) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
        compose.waitForIdle()
    }

    private fun shot(name: String) {
        val dir = File("build/screenshots/$tag").apply { mkdirs() }
        val config = compose.activity.resources.configuration
        assertEquals("Capture must use the pinned Graph.clock instant", captureInstant, graph.clock.instant())
        assertEquals("Capture must use the pinned Graph.clock zone", captureZone, graph.clock.zone)
        assertEquals("Capture width in dp", expectedWidthDp, config.screenWidthDp)
        assertEquals("Capture font scale", expectedFontScale, config.fontScale, 0.001f)
        // Drawing the window directly; captureToImage waits on frame callbacks Robolectric's paused clock never sends.
        val view = compose.activity.window.decorView
        assertTrue("Capture window must be laid out", view.width > 0 && view.height > 0)
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(Canvas(bitmap)) }
        File(dir, "$name.png").outputStream().use {
            assertTrue("PNG compression must succeed", bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        File(dir, "$name.json").writeText("""
            {"renderer":"Robolectric native graphics","sdk":34,"tag":"$tag",
             "clockInstant":"${graph.clock.instant()}","clockZone":"${graph.clock.zone}",
             "screenWidthDp":${config.screenWidthDp},"screenHeightDp":${config.screenHeightDp},
             "fontScale":${config.fontScale},"densityDpi":${config.densityDpi},
             "pixelWidth":${view.width},"pixelHeight":${view.height},
             "theme":"${graph.repository.settings.value.themeMode}",
             "simpleView":${graph.repository.settings.value.simpleView},"demoMode":${graph.repository.settings.value.demoMode}}
        """.trimIndent() + "\n")
        bitmap.recycle()
    }
}
