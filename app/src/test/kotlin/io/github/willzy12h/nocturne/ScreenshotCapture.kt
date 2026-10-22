package io.github.willzy12h.nocturne

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.willzy12h.nocturne.core.store.ThemeMode
import io.github.willzy12h.nocturne.ui.MainActivity
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration
import kotlinx.coroutines.runBlocking

/**
 * Renders the real app's screens to PNG for before/after comparison. Off unless SCREENSHOT_TAG is set, e.g.
 * `SCREENSHOT_TAG=baseline ./gradlew :app:testDebugUnitTest --tests '*ScreenshotCapture*'`. Images land in
 * `app/build/screenshots/<tag>/`. Rendered by Robolectric's native graphics on the JVM against the demo BedJet:
 * real Compose layout and drawing, not a device, and not Bluetooth evidence.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ScreenshotCapture {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val tag = System.getenv("SCREENSHOT_TAG")
    private val graph get() = compose.activity.graph

    @Before
    fun only() = assumeTrue("set SCREENSHOT_TAG to capture", !tag.isNullOrBlank())

    @Test fun dark() = capture("dark", ThemeMode.DARK, simple = false)
    @Test fun light() = capture("light", ThemeMode.LIGHT, simple = false)
    @Test fun simpleDark() = capture("simple-dark", ThemeMode.DARK, simple = true)
    @Test fun simpleLight() = capture("simple-light", ThemeMode.LIGHT, simple = true)

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
            compose.onAllNodesWithText(tab)[0].performClick()
            settle()
            shot("$look-$name")
        }
        compose.onAllNodesWithText("Tonight")[0].performClick()
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
        // Drawing the window directly; captureToImage waits on frame callbacks Robolectric's paused clock never sends.
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(Canvas(bitmap)) }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
