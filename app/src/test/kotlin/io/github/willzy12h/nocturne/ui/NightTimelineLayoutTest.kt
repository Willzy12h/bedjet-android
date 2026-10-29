package io.github.willzy12h.nocturne.ui

import android.Manifest
import android.content.res.Configuration
import android.os.Looper
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import io.github.willzy12h.nocturne.App
import io.github.willzy12h.nocturne.GraphLifecycle
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.plan.Middle
import io.github.willzy12h.nocturne.core.plan.Phase
import io.github.willzy12h.nocturne.core.plan.Profile
import io.github.willzy12h.nocturne.core.plan.WakeSource
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.store.ThemeMode
import io.github.willzy12h.nocturne.graph
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/** Real Tonight layouts at the combined compact-width and large-font boundary. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w320dp-h891dp-xxhdpi")
class NightTimelineLayoutTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val fixture = TestRule { base, _ -> object : Statement() {
        override fun evaluate() {
            val app = RuntimeEnvironment.getApplication() as App
            val graph = app.graph
            val previousClock = graph.clock
            val previousScale = app.resources.configuration.fontScale
            graph.clock = Clock.fixed(Instant.parse("2026-10-22T22:30:00Z"), ZoneOffset.UTC)
            shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS)
            @Suppress("DEPRECATION")
            app.resources.updateConfiguration(Configuration(app.resources.configuration).apply { fontScale = 2f }, app.resources.displayMetrics)
            runBlocking {
                graph.repository.setActiveNight(null)
                graph.repository.saveProfile(Profile(
                    id = "layout-clock-labels", name = "Layout clock labels",
                    start = listOf(Phase(Mode.HEAT, 64, 40, 30)),
                    middle = Middle(Mode.COOL, 40, 40, 44, 44),
                    wake = listOf(Phase(Mode.HEAT, 61, 30, 30)),
                    afterWake = listOf(Phase(Mode.DRY, 50, 30, 15)),
                    wakeSource = WakeSource(useAlarm = false, defaultWakeMinute = 6 * 60 + 30),
                ))
                graph.repository.updateSettings {
                    it.copy(demoMode = true, themeMode = ThemeMode.DARK, simpleView = false, tonightOverride = null)
                        .chooseTonight("layout-clock-labels", graph.now())
                }
            }
            try { base.evaluate() } finally {
                graph.clock = previousClock
                @Suppress("DEPRECATION")
                app.resources.updateConfiguration(Configuration(app.resources.configuration).apply { fontScale = previousScale }, app.resources.displayMetrics)
            }
        }
    } }
    @get:Rule val rules: TestRule = RuleChain.outerRule(GraphLifecycle()).around(fixture).around(compose)
    private val graph get() = compose.activity.graph
    private val curve = hasContentDescription("Night plan from 22:30 to 06:45", substring = true)

    @Test fun darkCurveClockLabelsStayWholeAtTwoHundredPercent() = assertTimes(ThemeMode.DARK, false)

    @Test fun lightCurveClockLabelsStayWholeAtTwoHundredPercent() = assertTimes(ThemeMode.LIGHT, false)

    @Test fun darkSimpleTimelineTimesStayWholeAtTwoHundredPercent() = assertTimes(ThemeMode.DARK, true)

    @Test fun lightSimpleTimelineTimesStayWholeAtTwoHundredPercent() = assertTimes(ThemeMode.LIGHT, true)

    private fun assertTimes(theme: ThemeMode, simple: Boolean) {
        val graph = graph
        compose.runOnUiThread { runBlocking { graph.repository.updateSettings { it.copy(themeMode = theme, simpleView = simple) } } }
        if (theme == ThemeMode.LIGHT) {
            compose.onNodeWithText(compose.activity.getString(R.string.theme_switch_now)).performClick()
        }
        compose.openTonightDetail()
        compose.waitUntil(10_000) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            if (simple) compose.onAllNodesWithText("23:00", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            else compose.onAllNodes(curve).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals("The actual activity must be 320 dp wide", 320, compose.activity.resources.configuration.screenWidthDp)
        assertEquals("The actual activity must use 200% text", 2f, compose.activity.resources.configuration.fontScale, 0.001f)
        val clockLabels = if (simple) {
            compose.onNode(curve).assertDoesNotExist()
            listOf("22:30" to "22:30", "23:00" to "23:00", "06:00" to "06:00", "06:30" to "06:30")
        } else {
            compose.onNode(curve).assertExists()
            compose.onNodeWithText("23:00", useUnmergedTree = true).assertDoesNotExist()
            compose.onNodeWithText("06:00", useUnmergedTree = true).assertDoesNotExist()
            listOf("22:30" to "22:30", "Wake 06:30" to "06:30", "06:45" to "06:45")
        }
        for ((text, clock) in clockLabels) {
            // Daylight also has a start tile; the plan-card label follows it in the real screen.
            val matches = compose.onAllNodesWithText(text, useUnmergedTree = true)
            val node = matches[matches.fetchSemanticsNodes().lastIndex]
            node.performScrollTo().assertIsDisplayed()
            val layouts = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            val label = "$theme simple=$simple $text"
            if (text == "22:30") assertEquals("$label must render the requested palette",
                if (theme == ThemeMode.DARK) MidnightInkScheme.onSurfaceVariant else io.github.willzy12h.nocturne.core.store.Palette.OVERCAST.tokens.materialScheme().onSurfaceVariant,
                layout.layoutInput.style.color)
            val first = text.indexOf(clock)
            val last = first + clock.length - 1
            if (simple) assertEquals("$label must stay on one line", 1, layout.lineCount)
            assertEquals("$label must keep the complete clock on one line", layout.getLineForOffset(first), layout.getLineForOffset(last))
            assertFalse("$label must not overflow its text box: size=${layout.size}, " +
                "paragraph=${layout.multiParagraph.width} x ${layout.multiParagraph.height}, " +
                "constraints=${layout.layoutInput.constraints}, width=${layout.didOverflowWidth}, height=${layout.didOverflowHeight}, " +
                "fontSize=${layout.layoutInput.style.fontSize}, lineHeight=${layout.layoutInput.style.lineHeight}, density=${layout.layoutInput.density}, " +
                "lineTop=${layout.getLineTop(0)}, lineBottom=${layout.getLineBottom(0)}, lineRight=${layout.getLineRight(0)}, " +
                "bounds=${node.fetchSemanticsNode().boundsInRoot}, unclippedBounds=${node.getUnclippedBoundsInRoot()}", layout.hasVisualOverflow)
            val line = layout.getLineForOffset(first)
            assertFalse("$label must not be ellipsised", layout.isLineEllipsized(line))
            assertTrue("$label must fit its measured width", layout.getLineRight(line) <= layout.size.width + 1f)
            val bounds = node.fetchSemanticsNode().boundsInRoot
            val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
            assertTrue("$label must not be clipped horizontally by its parent", bounds.width >= layout.size.width - 1f)
            assertTrue("$label must not be clipped vertically by its parent", bounds.height >= layout.size.height - 1f)
            assertTrue("$label must stay on screen", bounds.left >= root.left && bounds.right <= root.right)
        }
    }
}
