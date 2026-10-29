package io.github.willzy12h.nocturne.ui

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.lifecycle.ViewModelProvider
import io.github.willzy12h.nocturne.App
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.GraphLifecycle
import io.github.willzy12h.nocturne.NativePngCapture
import io.github.willzy12h.nocturne.core.store.ThemeMode
import io.github.willzy12h.nocturne.data.Repository
import io.github.willzy12h.nocturne.night.CommandOutcome
import io.github.willzy12h.nocturne.night.OutcomeKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** Simple view is exercised with both palettes; it is an independent preference, not a third theme. */
enum class U06Look(val theme: ThemeMode, val simple: Boolean) {
    MIDNIGHT(ThemeMode.DARK, false), DAYLIGHT(ThemeMode.LIGHT, false),
    SIMPLE_MIDNIGHT(ThemeMode.DARK, true), SIMPLE_DAYLIGHT(ThemeMode.LIGHT, true),
}

/**
 * Real Activity, screens, repository and demo connection; defaults to 320 dp / 200% text. Seeded states deliberately
 * do not start the background reconciler: these tests prove layout, while AppSmokeTest covers runtime transitions.
 * Every graph is finished before replacing it or tearing down Robolectric storage.
 */
internal class U06Fixture(
    val look: U06Look,
    private val widthDp: Int = 320,
    private val fontScale: Float = 2f,
    beforeLaunch: (U06Fixture, Description) -> Unit = { _, _ -> },
) {
    val compose = createAndroidComposeRule<MainActivity>()
    val graphs = GraphLifecycle()
    val graph: Graph get() = (RuntimeEnvironment.getApplication() as App).graph
    val model: AppViewModel get() = ViewModelProvider(compose.activity)[AppViewModel::class.java]
    private val fixedClock = Clock.fixed(Instant.parse("2026-10-22T22:30:00Z"), ZoneId.of("Europe/London"))
    private var method = ""
    private var feedback: String? = null
    private var captureNumber = 0

    private val configuration = TestRule { base, _ -> object : Statement() {
        override fun evaluate() {
            val app = RuntimeEnvironment.getApplication() as App
            val previous = Configuration(app.resources.configuration)
            @Suppress("DEPRECATION")
            app.resources.updateConfiguration(Configuration(previous).apply {
                fontScale = this@U06Fixture.fontScale
            }, app.resources.displayMetrics)
            // Keep real SnackbarHost feedback visible while semantic scrolling advances Compose's clock.
            // This is Android's accessibility "time to take action" setting, not a frozen animation clock.
            shadowOf(app.getSystemService(android.view.accessibility.AccessibilityManager::class.java)).apply {
                setInteractiveUiTimeout(60_000)
                setNonInteractiveUiTimeout(60_000)
            }
            try { base.evaluate() } finally {
                @Suppress("DEPRECATION")
                app.resources.updateConfiguration(previous, app.resources.displayMetrics)
            }
        }
    } }
    private val seed = TestRule { base, description -> object : Statement() {
        override fun evaluate() {
            method = description.methodName.substringBefore('[')
            val app = RuntimeEnvironment.getApplication() as App
            // Layout tests must not resume Compose's test continuation on a real writer thread.
            // Keep the real repository and durable operations, but schedule this fixture's writes
            // on the Android main thread. Repository/runtime concurrency tests retain the real writer.
            val replacement = graphs.fresh {
                Graph(app, repositoryFactory = { scope, now ->
                    Repository(app, scope, writer = Dispatchers.Main.immediate, now = now)
                }).also { it.clock = fixedClock }
            }
            ReflectionHelpers.setField(app, "graph", replacement)
            runBlocking {
                replacement.repository.updateSettings { it.copy(
                    demoMode = true, themeMode = look.theme, simpleView = look.simple,
                    fahrenheit = false,
                ) }
                replacement.repository.setActiveNight(null)
            }
            beforeLaunch(this@U06Fixture, description)
            base.evaluate()
        }
    } }
    val rules: TestRule = RuleChain.outerRule(configuration).around(graphs).around(seed).around(compose)

    fun s(id: Int, vararg args: Any): String = RuntimeEnvironment.getApplication().getString(id, *args)

    fun waitUntil(condition: () -> Boolean) = compose.waitUntil(10_000) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
        if (!compose.mainClock.autoAdvance) compose.mainClock.advanceTimeByFrame()
        condition()
    }

    fun tab(label: String) {
        compose.navTab(label)
        layout()
    }

    fun quick(preset: io.github.willzy12h.nocturne.core.store.ActionPreset): SemanticsNodeInteraction =
        checkControl(compose.onNodeWithTag("quick-mode-${preset.mode.name}"), "Quick ${preset.mode}").assertHasClickAction()

    fun control(label: String, scroll: Boolean = true): SemanticsNodeInteraction =
        checkControl(compose.onNode(hasText(label) and hasClickAction()), label, scroll).assertHasClickAction()

    fun checkControl(node: SemanticsNodeInteraction, label: String, scroll: Boolean = true): SemanticsNodeInteraction {
        checkLayout(node, label, scroll)
        val semantics = node.fetchSemanticsNode()
        val density = semantics.layoutInfo.density.density
        val touch = semantics.touchBoundsInRoot
        val geometry = "touch=$touch, visible=${semantics.boundsInRoot}, size=${semantics.size}, position=${semantics.positionInRoot}"
        assertTrue("$label touch width ${touch.width / density} dp < 48 dp; $geometry", touch.width >= 48f * density - 1f)
        assertTrue("$label touch height ${touch.height / density} dp < 48 dp; $geometry", touch.height >= 48f * density - 1f)
        // The approved feedback bubble overlays content. A control beneath it is reached by dismissing
        // the bubble first, through the actual pointer route, rather than accidentally tapping the bubble.
        val bubble = compose.onAllNodesWithTag("feedback-bubble").fetchSemanticsNodes().singleOrNull()
        if (bubble != null && bubble.root === semantics.root &&
            generateSequence(semantics) { it.parent }.none { it.id == bubble.id } &&
            bubble.boundsInRoot.contains(semantics.boundsInRoot.center)) {
            val dismiss = compose.onAllNodesWithTag("snackbar-dismiss").fetchSemanticsNodes().isNotEmpty()
            if (dismiss) compose.onNodeWithTag("snackbar-dismiss").performClick()
            else compose.onNode(hasClickAction() and hasAnyAncestor(hasTestTag("feedback-bubble")), useUnmergedTree = true).performClick()
            waitUntil { compose.onAllNodesWithTag("feedback-bubble").fetchSemanticsNodes().isEmpty() }
            feedback = null
            checkLayout(node, label, scroll = false)
        }
        return node
    }

    /**
     * A partial semantics rectangle can pass assertIsDisplayed. Compare it with the actual un-clipped layout,
     * check the full text layout, and compare the visible control against its scroll viewport, bar and snackbar.
     * Material may expand a chip's touch target beyond its drawn bounds. performScrollTo aligns those drawn
     * bounds with a viewport edge; that extra touch overhang is allowed, but clipping the control itself is not.
     */
    fun checkLayout(node: SemanticsNodeInteraction, label: String, scroll: Boolean = true): SemanticsNodeInteraction {
        assertConfiguration()
        if (scroll) node.performScrollTo()
        layout()
        capture(label)
        node.assertIsDisplayed()
        val semantics = node.fetchSemanticsNode()
        assertEquals("The composed node must use the requested font scale", fontScale, semantics.layoutInfo.density.fontScale, .001f)
        val density = semantics.layoutInfo.density.density
        val uncut = node.getUnclippedBoundsInRoot().let { Rect(
            it.left.value * density, it.top.value * density, it.right.value * density, it.bottom.value * density,
        ) }
        val visible = semantics.boundsInRoot
        assertContained("$label layout is clipped", uncut, visible)
        val root = generateSequence(semantics) { it.parent }.last().boundsInRoot
        assertContained("$label visible control leaves its window", visible, root)
        generateSequence(semantics.parent) { it.parent }
            .filter { it.config.contains(SemanticsProperties.VerticalScrollAxisRange) }
            .forEach { viewport ->
                assertContained("$label visible control leaves its scroll viewport", visible, viewport.boundsInRoot)
            }
        val bar = compose.onAllNodesWithTag("main-navigation").fetchSemanticsNodes().singleOrNull()
        if (bar != null && semantics.root === bar.root) {
            assertTrue("$label is covered by bottom navigation: $visible / ${bar.boundsInRoot}", visible.bottom <= bar.boundsInRoot.top + 1f)
        }
        feedback?.let { message ->
            val notice = compose.onNodeWithText(message, useUnmergedTree = true).fetchSemanticsNode()
            if (semantics.root === notice.root) {
                val ancestors = generateSequence(notice) { it.parent }.toList()
                val liveRegion = ancestors.indexOfFirst { it.config.getOrNull(SemanticsProperties.LiveRegion) != null }
                check(liveRegion >= 0) { "Visible feedback must expose a live region" }
                // The host's live-region wrapper includes transparent outer padding. The inner Material
                // Surface is the actual obstruction and marks its own traversal group.
                val surface = ancestors.take(liveRegion).firstOrNull {
                    it.config.getOrNull(SemanticsProperties.IsTraversalGroup) == true
                } ?: error("Visible feedback must expose its opaque Material Surface")
                // The approved design floats feedback as a small bubble over the screen instead of moving it. A control
                // beneath it stays reachable: the bubble is dismissible and never taller than a quarter of the window.
                val closeButton = compose.onAllNodesWithTag("snackbar-dismiss", useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
                val tapToDismiss = compose.onAllNodesWithTag("feedback-bubble", useUnmergedTree = true)
                    .fetchSemanticsNodes().any { bubble ->
                        generateSequence(bubble) { it.parent }.any {
                            it.config.contains(SemanticsActions.OnClick)
                        } || bubble.children.any { it.config.contains(SemanticsActions.OnClick) }
                    }
                assertTrue("Visible feedback must be dismissible", closeButton || tapToDismiss)
                assertTrue("$label: feedback bubble is too tall: ${surface.boundsInRoot}",
                    surface.boundsInRoot.height <= root.height / 4f + 1f)
            }
        }
        assertTextFits(semantics, label)
        return node
    }

    private fun assertContained(message: String, inside: Rect, outside: Rect) {
        assertTrue("$message: expected $inside inside $outside", inside.left >= outside.left - 1f &&
            inside.top >= outside.top - 1f && inside.right <= outside.right + 1f && inside.bottom <= outside.bottom + 1f)
    }

    private fun assertConfiguration() {
        assertEquals("The Activity must really use the requested width", widthDp, compose.activity.resources.configuration.screenWidthDp)
        assertEquals("The Activity must really use the requested font scale", fontScale, compose.activity.resources.configuration.fontScale, .001f)
        assertEquals("Compose must inherit the requested font scale", fontScale, compose.density.fontScale, .001f)
    }

    /** Shared by control labels and the scrollable loss explanation; no ellipsis, lost characters or clipped lines. */
    fun assertLinesFit(result: TextLayoutResult, label: String) {
        assertTrue("$label must render at least one line", result.lineCount > 0)
        // Compose 1.7.6 rebuilds the semantic MultiParagraph at its original maximum width even if Text
        // shrank to its contents. Check actual line spans instead of hasVisualOverflow's false width result.
        assertFalse("$label loses text lines", result.multiParagraph.didExceedMaxLines)
        assertEquals("$label must render every character", result.layoutInput.text.length,
            result.getLineEnd(result.lineCount - 1))
        (0 until result.lineCount).forEach { line ->
            assertFalse("$label ellipsises line $line", result.isLineEllipsized(line))
            val width = result.getLineRight(line) - result.getLineLeft(line)
            assertTrue("$label clips line $line horizontally: $width > ${result.size.width}", width <= result.size.width + 1f)
            assertTrue("$label clips line $line vertically: ${result.getLineBottom(line)} > ${result.size.height}",
                result.getLineTop(line) >= -1f && result.getLineBottom(line) <= result.size.height + 1f)
        }
    }

    private fun assertTextFits(control: SemanticsNode, label: String) {
        // Use the unmerged descendants: the button's merged text does not itself own a TextLayoutResult.
        val texts = compose.onAllNodes(SemanticsMatcher("text inside control ${control.id}") { node ->
            node.config.contains(SemanticsActions.GetTextLayoutResult) &&
                generateSequence(node) { it.parent }.any { it.id == control.id }
        }, useUnmergedTree = true).fetchSemanticsNodes()
        if (!control.config.getOrNull(SemanticsProperties.Text).isNullOrEmpty()) {
            assertTrue("$label must have rendered text descendants", texts.isNotEmpty())
        }
        texts.forEach { text ->
            val layouts = mutableListOf<TextLayoutResult>()
            compose.runOnIdle { text.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts) }
            assertTrue("$label must expose its rendered text layout", layouts.isNotEmpty())
            layouts.forEach { result -> assertLinesFit(result, label) }
            val fullText = Rect(text.positionInRoot.x, text.positionInRoot.y,
                text.positionInRoot.x + text.size.width, text.positionInRoot.y + text.size.height)
            assertContained("$label text is clipped", fullText, text.boundsInRoot)
            assertContained("$label text extends outside its control", fullText, control.boundsInRoot)
        }
    }

    /** Show actual AppViewModel feedback; the accessibility timeout keeps it visible through scrolling. */
    fun snackbar(): String {
        val message = "U06 layout feedback"
        compose.runOnIdle { model.report(CommandOutcome(OutcomeKind.LOCAL, message)) }
        waitUntil { compose.onAllNodesWithText(message).fetchSemanticsNodes().isNotEmpty() }
        compose.mainClock.advanceTimeBy(300) // Finish the snackbar entrance animation, not its display duration.
        feedback = message
        layout()
        compose.onNodeWithText(message).assertIsDisplayed()
        return message
    }

    private fun layout() {
        if (!compose.mainClock.autoAdvance) compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    /** Opt-in evidence is separate from assertions: these tests still run without SCREENSHOT_TAG. */
    fun capture(name: String) {
        val tag = System.getenv("SCREENSHOT_TAG")?.takeIf { it.isNotBlank() } ?: return
        layout() // A click can schedule recomposition; draw the destination rather than the preceding screen.
        assertConfiguration()
        require(tag.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]*")))
        val safeName = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(90)
        val directory = File("build/screenshots/$tag/u06").apply { mkdirs() }
        val stem = "${look.name.lowercase()}-$method-${++captureNumber}-$safeName"
        // A dialog owns another Compose window; draw each currently visible window, preserving its identity.
        val views = android.view.inspector.WindowInspector.getGlobalWindowViews().filter { it.isShown && it.width > 0 && it.height > 0 }
        assertTrue("Native capture must include at least one visible window", views.isNotEmpty())
        assertTrue("Native capture must include the Activity", views.any { it === compose.activity.window.decorView })
        views.forEachIndexed { index, view ->
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            try {
                compose.runOnUiThread { view.draw(Canvas(bitmap)) }
                NativePngCapture.write(bitmap, File(directory, "$stem-window$index.png"))
            } finally { bitmap.recycle() }
        }
        val config = compose.activity.resources.configuration
        val dimensions = views.joinToString(",") { "{\"widthPx\":${it.width},\"heightPx\":${it.height}}" }
        File(directory, "$stem.json").writeText("""{"renderer":"Robolectric native graphics","sdk":34,"widthDp":${config.screenWidthDp},"heightDp":${config.screenHeightDp},"fontScale":${config.fontScale},"density":${compose.density.density},"look":"$look","clock":"${graph.clock.instant()}","windows":[$dimensions]}""")
    }
}
