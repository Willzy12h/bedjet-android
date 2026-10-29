package io.github.willzy12h.nocturne.ui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import io.github.willzy12h.nocturne.App
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.link.LinkState
import io.github.willzy12h.nocturne.core.night.ActiveNight
import io.github.willzy12h.nocturne.core.night.AppliedProgram
import io.github.willzy12h.nocturne.core.night.Confidence
import io.github.willzy12h.nocturne.core.plan.PlanCompiler
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.io.File
import kotlin.math.abs

private enum class CaptureScene { IDLE, CONFIRMED, LOST_ORIGINALS }

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
private annotation class CaptureSeed(val value: CaptureScene)

/** Normal-size top views of every tab, plus independent evidence for the two retained layout fixes. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class U06NormalCaptureTest(look: U06Look) {
    private val ui = U06Fixture(look, widthDp = 411, fontScale = 1f, beforeLaunch = ::seedCapture)
    private val views = CaptureViews(ui)
    @get:Rule val rules: TestRule = ui.rules

    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun looks(): List<Array<Any>> = U06Look.entries.map { arrayOf<Any>(it) }
    }

    @Test @CaptureSeed(CaptureScene.IDLE)
    fun normalIdleTonightAndFourTabs() = views.idleTonight(allTabs = true)

    @Test @CaptureSeed(CaptureScene.CONFIRMED)
    fun normalConfirmedTonight() = views.confirmedTonight()

    @Test @CaptureSeed(CaptureScene.LOST_ORIGINALS)
    fun normalAcceptLossDialog() = views.acceptLossDialog()
}

/** Separate method names keep both viewports' captures distinct under a single SCREENSHOT_TAG. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class U06LargeTextCaptureTest(look: U06Look) {
    private val ui = U06Fixture(look, beforeLaunch = ::seedCapture)
    private val views = CaptureViews(ui)
    @get:Rule val rules: TestRule = ui.rules

    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun looks(): List<Array<Any>> = U06Look.entries.map { arrayOf<Any>(it) }
    }

    @Test @CaptureSeed(CaptureScene.IDLE)
    fun largeTextIdleTonight() = views.idleTonight(allTabs = false)

    @Test @CaptureSeed(CaptureScene.CONFIRMED)
    fun largeTextConfirmedTonight() = views.confirmedTonight()

    @Test @CaptureSeed(CaptureScene.LOST_ORIGINALS)
    fun largeTextAcceptLossDialog() = views.acceptLossDialog()
}

private fun seedCapture(ui: U06Fixture, description: Description) {
    // Skip before Activity launch, while retaining GraphLifecycle cleanup of the app's background work.
    assumeTrue("Set SCREENSHOT_TAG to collect native U06 capture evidence",
        !System.getenv("SCREENSHOT_TAG").isNullOrBlank())
    when (requireNotNull(description.getAnnotation(CaptureSeed::class.java)).value) {
        CaptureScene.IDLE -> Unit
        CaptureScene.CONFIRMED -> {
            val graph = ui.graph
            val profile = graph.repository.profiles.value.first()
            val now = graph.now()
            val wake = now.plusHours(8)
            val plan = PlanCompiler.compile(profile, now, wake)
            runBlocking {
                graph.repository.setActiveNight(ActiveNight(
                    "u06-capture-night", profile.id, now.toString(), wake.toString(), false, 2,
                    wake.minusMinutes(30).toString(), wake.plusMinutes(30).toString(),
                    deviceId = "demo", profile = profile, applied = AppliedProgram.from(plan),
                    confidence = Confidence.CONFIRMED,
                ))
            }
        }
        CaptureScene.LOST_ORIGINALS -> {
            val app = RuntimeEnvironment.getApplication() as App
            val clock = ui.graph.clock
            ui.graphs.afterFinishing { File(app.filesDir, "slot-backups.json").writeText("{unreadable") }
            val replacement = ui.graphs.fresh { Graph(app).also { it.clock = clock } }
            ReflectionHelpers.setField(app, "graph", replacement)
        }
    }
}

/** Capture independently of assertions that deliberately fail on the base's primary label or dialog text. */
private class CaptureViews(private val ui: U06Fixture) {
    private val compose get() = ui.compose

    fun idleTonight(allTabs: Boolean) {
        ui.waitUntil { ui.graph.link.state.value == LinkState.READY && ui.graph.link.status.value != null }
        if (allTabs) captureTop("tonight")
        tonight(R.string.tonight_start_now, "start")
        if (allTabs) {
            listOf(R.string.tab_profiles to "profiles", R.string.tab_history to "history",
                R.string.tab_settings to "settings").forEach { (id, name) ->
                val label = ui.s(id)
                compose.onNode(hasClickAction() and (hasText(label) or hasContentDescription(label))).performClick()
                captureTop(name)
            }
        }
    }

    fun confirmedTonight() {
        ui.waitUntil { ui.graph.actions.status.value.confidence == Confidence.CONFIRMED }
        tonight(R.string.parity_end_night, "end")
    }

    private fun tonight(primaryId: Int, name: String) {
        val primary = button(primaryId).performScrollTo()
        val quick = listOf("TURBO", "HEAT", "COOL", "DRY", "BOOST", "STANDBY").map { mode ->
            compose.onNodeWithTag("quick-mode-$mode")
        }
        compose.onNodeWithTag("quick-controls").performScrollTo()
        compose.waitForIdle()
        // The six-mode grid has three rows. Record one complete grid when it fits;
        // otherwise record each complete two-column row, without clipping a target.
        if (quick.all(::fullyVisible)) {
            ui.capture("tonight-quick")
            quick.forEach { node ->
                node.assertIsDisplayed()
                assertTrue("Every Quick target is fully visible in the grid capture", fullyVisible(node))
            }
        } else quick.chunked(2).forEachIndexed { row, nodes ->
            nodes.first().performScrollTo()
            nodes.last().performScrollTo()
            compose.waitForIdle()
            nodes.forEach { node ->
                node.assertIsDisplayed()
                assertTrue("Both targets must be fully visible in row $row", fullyVisible(node))
            }
            val left = nodes.first().fetchSemanticsNode().boundsInRoot
            val right = nodes.last().fetchSemanticsNode().boundsInRoot
            assertTrue("Quick controls retain two distinct columns", left.right <= right.left)
            ui.capture("tonight-quick-row-${row + 1}")
        }
        if (fullyVisible(primary)) {
            ui.capture("tonight-$name-and-quick")
        } else {
            primary.performScrollTo()
            compose.waitForIdle()
            ui.capture("tonight-$name")
        }
    }

    private fun button(id: Int) = compose.onNode(hasText(ui.s(id)) and hasClickAction())

    private fun fullyVisible(node: SemanticsNodeInteraction): Boolean {
        if (!node.isDisplayed()) return false
        val semantics = node.fetchSemanticsNode()
        val full = Rect(semantics.positionInRoot.x, semantics.positionInRoot.y,
            semantics.positionInRoot.x + semantics.size.width, semantics.positionInRoot.y + semantics.size.height)
        val visible = semantics.boundsInRoot
        return full.left >= visible.left - 1f && full.top >= visible.top - 1f &&
            full.right <= visible.right + 1f && full.bottom <= visible.bottom + 1f
    }

    private fun captureTop(name: String) {
        compose.waitForIdle()
        val scrollable = SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange) and
            SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollBy)
        compose.onAllNodes(scrollable, useUnmergedTree = true).fetchSemanticsNodes().forEach { semantics ->
            val node = compose.onNode(SemanticsMatcher("capture scroll area ${semantics.id}") { it.id == semantics.id },
                useUnmergedTree = true)
            if (semantics.config.contains(SemanticsActions.ScrollToIndex)) {
                node.performScrollToIndex(0)
            } else {
                val range = semantics.config[SemanticsProperties.VerticalScrollAxisRange]
                if (range.value() > 0f) node.performSemanticsAction(SemanticsActions.ScrollBy) {
                    it(0f, -range.value() * if (range.reverseScrolling) -1f else 1f)
                }
            }
        }
        compose.waitForIdle()
        ui.capture("tab-$name-top")
    }

    fun acceptLossDialog() {
        button(R.string.originals_accept_button).performScrollTo().performClick()
        val text = ui.s(R.string.originals_accept_text)
        ui.waitUntil { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        ui.capture("accept-loss-start")
        val body = compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()
        val scroller = generateSequence(body) { it.parent }.firstOrNull {
            it.config.contains(SemanticsProperties.VerticalScrollAxisRange) && it.config.contains(SemanticsActions.ScrollBy)
        } ?: return // The baseline has no route to the clipped final warning; its capture must still complete.
        val range = scroller.config[SemanticsProperties.VerticalScrollAxisRange]
        if (range.maxValue() <= 0f) return // At normal size the complete explanation can fit without scrolling.
        val target = range.maxValue()
        val delta = (target - range.value()) * if (range.reverseScrolling) -1f else 1f
        compose.onNode(SemanticsMatcher("loss explanation scroll area") { it.id == scroller.id }, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, delta) }
        ui.waitUntil { abs(range.value() - target) <= 1f }
        ui.capture("accept-loss-end")
    }
}
