package io.github.willzy12h.nocturne

import io.github.willzy12h.nocturne.ui.openSettingsPage
import io.github.willzy12h.nocturne.ui.backSettingsPage

import android.Manifest
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.getOrNull
import io.github.willzy12h.nocturne.ui.*
import io.github.willzy12h.nocturne.core.store.*
import io.github.willzy12h.nocturne.core.plan.Presets
import io.github.willzy12h.nocturne.core.plan.Block
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.night.Confidence
import io.github.willzy12h.nocturne.night.NightActions
import io.github.willzy12h.nocturne.data.Repository
import io.github.willzy12h.nocturne.widget.WidgetUpdater
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
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.time.Duration
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible

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
    private val graphs = GraphLifecycle()
    private val compose = createAndroidComposeRule<MainActivity>()
    private val pinClock = TestRule { base, description ->
        object : org.junit.runners.model.Statement() {
          override fun evaluate() {
            assumeTrue("set SCREENSHOT_TAG to capture", !tag.isNullOrBlank())
            require(tag!!.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]*"))) { "SCREENSHOT_TAG must be a simple directory name" }
            val app = RuntimeEnvironment.getApplication() as App
            // Explicit policy: these captures use granted runtime permissions; no host defaults.
            shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS)
            shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_ADVERTISE,
                "android.permission.health.READ_SLEEP")
            val oldClock = app.graph.clock
            val oldScale = app.resources.configuration.fontScale
            // The Compose test frame clock can resume inline from a StateFlow writer. Keep capture
            // publications on Main, as LargeTextActivityFixture does, without disabling the real runtime collectors.
            val captureGraph = graphs.fresh {
                Graph(app, repositoryFactory = { scope, now ->
                    Repository(app, scope, writer = Dispatchers.Main.immediate, now = now)
                }).also { it.clock = Clock.fixed(captureInstant, captureZone) }
            }
            ReflectionHelpers.setField(app, "graph", captureGraph)
            // The old graph's updater was cancelled by fresh(); allow actual startup to bind the new graph.
            val widgetStarted = WidgetUpdater::class.java.getDeclaredField("started").let {
                it.isAccessible = true
                it.get(WidgetUpdater) as AtomicBoolean
            }
            widgetStarted.set(false)
            val combined = description.methodName.contains("combined320And200") || description.methodName.contains("Compact")
            expectedWidthDp = if (combined) 320 else 411
            expectedFontScale = if (combined) 2f else 1f
            if (oldScale != expectedFontScale) {
                val config = android.content.res.Configuration(app.resources.configuration).apply { fontScale = expectedFontScale }
                @Suppress("DEPRECATION")
                app.resources.updateConfiguration(config, app.resources.displayMetrics)
            }
            try {
                startCaptureRuntime(captureGraph)
                assertTrue("Capture runtime must start the widget updater", widgetStarted.get())
                base.evaluate()
            } finally {
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
    @get:Rule val rules: TestRule = RuleChain.outerRule(graphs).around(pinClock).around(compose)

    private val graph get() = compose.activity.graph

    private fun startCaptureRuntime(captureGraph: Graph) {
        // startRuntime blocks on migration's IO coroutine, which now needs Main for repository work.
        // Pump Main before Compose launches; lifecycle cancellation can interrupt the blocking startup.
        val startup = captureGraph.scope.async(Dispatchers.Default) {
            runInterruptible { captureGraph.startRuntime() }
        }
        val deadline = System.nanoTime() + 30_000_000_000L
        while (!startup.isCompleted && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.yield()
        }
        assertTrue("Capture runtime startup must complete before Compose launches", startup.isCompleted)
        runBlocking { startup.await() }
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun dark() = capture("dark", ThemeMode.DARK, simple = false)
    @Test fun light() = capture("light", ThemeMode.LIGHT, simple = false)
    @Test fun simpleDark() = capture("simple-dark", ThemeMode.DARK, simple = true)
    @Test fun simpleLight() = capture("simple-light", ThemeMode.LIGHT, simple = true)
    @Test @Config(qualifiers = "w320dp-h891dp-xxhdpi")
    fun combined320And200() = capture("combined-320dp-200pct", ThemeMode.DARK, simple = false)

    @Test fun featureViews() {
        compose.runOnUiThread { runBlocking {
            graph.repository.updateSettings {
                it.copy(demoMode = true, themeMode = ThemeMode.DARK)
            }
            graph.repository.setActiveNight(null)
        } }
        settle()
        compose.onAllNodesWithText("Settings")[0].performClick()
        compose.openSettingsPage("bedjet")
        compose.onNodeWithText("Release BedJet for another phone").performScrollTo()
        settle()
        shot("features-01-manual-connection-release")
        compose.backSettingsPage()
        compose.openSettingsPage("night")
        compose.onAllNodesWithText("Bedtime reminder")[0].performScrollTo()
        settle()
        shot("features-02-platform-settings")
        compose.navTab("History")
        settle()
        shot("features-03-history-export")
    }

    @Test fun gapsDark() = captureGaps("dark", ThemeMode.DARK, false)
    @Test fun gapsLight() = captureGaps("light", ThemeMode.LIGHT, false)
    @Test fun gapsSimpleDark() = captureGaps("simple-dark", ThemeMode.DARK, true)
    @Test fun gapsSimpleLight() = captureGaps("simple-light", ThemeMode.LIGHT, true)
    @Test @Config(qualifiers = "w320dp-h891dp-xxhdpi")
    fun gapsCompactDark() = captureGaps("compact-dark", ThemeMode.DARK, false)
    @Test @Config(qualifiers = "w320dp-h891dp-xxhdpi")
    fun gapsCompactLight() = captureGaps("compact-light", ThemeMode.LIGHT, false)
    @Test @Config(qualifiers = "w320dp-h891dp-xxhdpi")
    fun gapsCompactSimpleDark() = captureGaps("compact-simple-dark", ThemeMode.DARK, true)
    @Test @Config(qualifiers = "w320dp-h891dp-xxhdpi")
    fun gapsCompactSimpleLight() = captureGaps("compact-simple-light", ThemeMode.LIGHT, true)

    /** G12: eight actual UI states in each of four looks at both requested sizes. */
    private fun captureGaps(look: String, theme: ThemeMode, simple: Boolean) {
        val graph = graph
        fun s(id: Int) = compose.activity.getString(id)
        fun tab(label: String) {
            compose.onNode((hasText(label) or hasContentDescription(label) or hasAnyDescendant(hasText(label))) and hasClickAction() and
                hasAnyAncestor(hasTestTag("main-navigation")), useUnmergedTree = true).performClick()
            settle()
        }
        fun tap(text: String, scroll: Boolean = true) {
            val node = compose.onAllNodesWithText(text)[0]
            if (scroll) node.performScrollTo()
            node.performClick(); settle()
        }
        fun save(block: suspend () -> Unit) { compose.runOnUiThread { runBlocking { block() } }; settle() }
        fun waitFor(text: String) = compose.waitUntil(15_000) {
            settle(100); compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
        save {
            graph.repository.updateSettings { it.copy(demoMode = true, themeMode = theme, simpleView = simple) }
            graph.repository.setActiveNight(null)
        }
        acceptLight(theme)
        tab("Profiles")
        val profileId = graph.repository.profiles.value.first().id
        compose.onNode(hasScrollToKeyAction()).performScrollToKey(profileId)
        compose.onNodeWithTag("profile-more-$profileId", useUnmergedTree = true).performScrollTo().performClick()
        settle()
        shot("gaps-$look-00-profile-menu")
        tap(s(R.string.pin_menu), scroll = false)
        compose.onNodeWithText(compose.activity.getString(R.string.pin_slot_help, graph.repository.settings.value.workingSlot + 1)).assertExists()
        shot("gaps-$look-01-slot-pin")
        tap(s(R.string.feature_cancel), scroll = false)
        tab("Tonight")
        save { graph.repository.updateSettings { it.setWakeOverride(graph.now().plusHours(7), graph.now()) } }
        val expiry = graph.now().plusHours(7)
        compose.onNodeWithTag("tonight-fixed-wake").performScrollTo()
            .assert(hasText(Format.clock(expiry), substring = true))
        shot("gaps-$look-02-wake-expiry")
        compose.onNodeWithTag("tonight-fixed-wake").performClick()
        settle()
        // TimePickerDialog is a platform dialog, so its own window is captured, not the activity behind it.
        val picker = org.robolectric.shadows.ShadowDialog.getLatestDialog()
        assertTrue("Wake picker must be showing", picker.isShowing)
        shot("gaps-$look-03-wake-picker", picker.window!!.decorView)
        compose.runOnUiThread { picker.dismiss() }; settle()
        tap(s(R.string.tonight_start_now))
        compose.waitUntil(15_000) { settle(100); graph.repository.activeNight.value?.confidence == Confidence.CONFIRMED && graph.actions.busy.value.runningLabel == null }
        save { graph.actions.execute(graph.actions.capture(NightActions.Command.STOP_TRACKING)) }
        waitFor(s(R.string.recovery_resume_previous))
        compose.mainClock.advanceTimeBy(6_000) // snackbar delay uses the Compose test clock
        settle()
        compose.onNodeWithText(s(R.string.recovery_resume_previous)).performScrollTo().assertIsDisplayed()
        shot("gaps-$look-04-resume")
        save {
            val profile = Presets.summerCool.copy(id = "gaps-checkin", name = "Summer comfort")
            graph.repository.saveProfile(profile)
            graph.repository.addRecord(NightRecord("gaps-checkin-night", profile.id, profile.name,
                "2026-10-21T22:30Z", "2026-10-22T07:00Z", false,
                events = listOf(HistoryEvent("2026-10-22T07:30Z", EventKind.ENDED, "Night ended"))))
        }
        // At large text the earlier "Stopped tracking" message can still cover the Sleep row in Settings.
        compose.onAllNodesWithTag("snackbar-dismiss").fetchSemanticsNodes().forEach { _ ->
            compose.onAllNodesWithTag("snackbar-dismiss")[0].performClick(); settle()
        }
        compose.navTab("History")
        // At 320 dp / 200% text the older card is not composed until the lazy list scrolls.
        // Scroll its container first; keep the same button/dialog assertions and all captures.
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(s(R.string.checkin_button)))
        tap(s(R.string.checkin_button))
        compose.onNodeWithText(s(R.string.checkin_title)).assertIsDisplayed()
        shot("gaps-$look-05-checkin-dialog")
        tap(s(R.string.checkin_too_hot), scroll = false)
        waitFor(s(R.string.checkin_change_title))
        shot("gaps-$look-06-checkin-suggestion")
        tap(s(R.string.checkin_keep), scroll = false)
        tab("Tonight")
        save { graph.repository.updateSettings { it.copy(demoMode = false, targetDevice = null) } }
        compose.onNodeWithText(s(R.string.setup_choose_title)).performScrollTo().assertIsDisplayed()
        shot("gaps-$look-07-setup-title")
        compose.onNodeWithText(s(R.string.setup_find)).performScrollTo().assertIsDisplayed()
        shot("gaps-$look-07-setup-chooser")
        // The production banner itself, with a documented unreadable-history fixture.
        val bannerSettings = graph.repository.settings.value
        val bannerNow = graph.now()
        compose.runOnUiThread { compose.activity.setContent {
            NocturneTheme(bannerSettings, bannerNow) {
                androidx.compose.material3.Surface(modifier = androidx.compose.ui.Modifier.fillMaxSize()) {
                    androidx.compose.foundation.layout.Column {
                        UnreadableDataBanner(UnreadableBannerState(listOf("history.json"), false, false),
                            onDismiss = {}, onRestore = {}, onAcceptLoss = {})
                    }
                }
            }
        } }
        settle()
        compose.onNodeWithText(s(R.string.unreadable_banner)).assertIsDisplayed()
        shot("gaps-$look-08-unreadable-banner")
    }

    private fun capture(look: String, theme: ThemeMode, simple: Boolean) {
        compose.runOnUiThread {
            runBlocking {
                graph.repository.updateSettings { it.copy(demoMode = true, themeMode = theme, simpleView = simple) }
                graph.repository.setActiveNight(null)
            }
        }
        settle()
        acceptLight(theme)
        shot("$look-01-tonight")
        compose.onNodeWithText(compose.activity.getString(R.string.tonight_start_now)).performScrollTo()
        settle()
        compose.onNodeWithText(compose.activity.getString(R.string.tonight_start_now)).assertIsDisplayed()
        shot("$look-01b-tonight-lower")
        // Remote is a main tab; the status line also opens it.
        compose.onNodeWithTag("open-remote").performScrollTo().performClick()
        settle()
        shot("$look-03-remote")
        seedHistory()
        for ((tab, name) in listOf("Profiles" to "02-profiles", "History" to "04-history", "Settings" to "05-settings")) {
            if (tab == "History") compose.navTab(tab)
            else if (look.startsWith("combined-")) compose.onAllNodes(hasContentDescription(tab))[0].performClick()
            else compose.onAllNodesWithText(tab)[0].performClick()
            settle()
            shot("$look-$name")
            if (tab == "Profiles") captureEditor(look)
            if (tab == "Settings" && !look.startsWith("combined-")) captureSettingsPages(look)
        }
        if (look.startsWith("combined-")) compose.onAllNodes(hasContentDescription("Tonight"))[0].performClick()
        else compose.onAllNodesWithText("Tonight")[0].performClick()
        settle()
        compose.onAllNodesWithText("Start night now")[0].performScrollTo().performClick()
        compose.waitUntil(15_000) {
            settle(250)
            // Stop remains available while a request is pending; its label alone does not prove a running night.
            graph.repository.activeNight.value?.confidence == Confidence.CONFIRMED &&
                graph.actions.status.value.confidence == Confidence.CONFIRMED && graph.actions.busy.value.runningLabel == null &&
                compose.onAllNodes(hasText(compose.activity.getString(R.string.parity_end_night))).fetchSemanticsNodes().isNotEmpty()
        }
        shot("$look-06a-feedback-bubble")
        settle(6_000) // let the confirmation message bar time out
        assertEquals("Running capture needs a confirmed night", Confidence.CONFIRMED, graph.repository.activeNight.value?.confidence)
        assertEquals("Running capture must not show a pending adjustment", Confidence.CONFIRMED, graph.actions.status.value.confidence)
        assertEquals("Running capture waits for its command owner", null, graph.actions.busy.value.runningLabel)
        shot("$look-06-tonight-running")
    }

    /** The tab-bar, Remote, Boost and Sleep changes at a glance; run with --tests '*ScreenshotCapture.redesignTour'. */
    @Test fun redesignTour() {
        for (theme in listOf(ThemeMode.DARK, ThemeMode.LIGHT)) {
            val look = if (theme == ThemeMode.DARK) "tour-dark" else "tour-light"
            compose.runOnUiThread { runBlocking {
                graph.repository.updateSettings { it.copy(demoMode = true, themeMode = theme, simpleView = false) }
                graph.repository.setActiveNight(null)
            } }
            settle()
            acceptLight(theme)
            // Demo mode was switched on after launch; connect the demo BedJet as the status chip would.
            if (graph.link.status.value == null) compose.runOnUiThread { graph.sessions.reconnect() }
            compose.waitUntil(15_000) { settle(250); graph.link.status.value != null }
            compose.onAllNodesWithText("Tonight")[0].performClick()
            settle()
            compose.onNodeWithTag("quick-mode-BOOST", useUnmergedTree = true).performScrollTo()
            settle()
            shot("$look-01-tonight-presets")
            compose.onNodeWithTag("quick-mode-BOOST", useUnmergedTree = true).performTouchInput { longClick() }
            settle()
            shot("$look-02-boost-editor")
            compose.onAllNodesWithText(compose.activity.getString(R.string.feature_cancel))[0].performClick()
            settle()
            compose.onAllNodesWithText("Remote")[0].performClick()
            // Demo mode was switched on after launch; connect the demo BedJet as the status chip would.
            if (graph.link.status.value == null) compose.runOnUiThread { graph.sessions.reconnect() }
            compose.waitUntil(15_000) { settle(250); graph.link.status.value != null &&
                compose.onAllNodesWithTag("remote-mode-COOL").fetchSemanticsNodes().isNotEmpty() }
            if (graph.link.status.value?.isRunning == true) {
                compose.onNodeWithTag("remote-mode-STANDBY").performScrollTo().performClick()
                settle(1_500)
            }
            compose.onNodeWithTag("remote-status").performScrollTo()
            settle()
            shot("$look-03-remote-off")
            for (mode in listOf("COOL", "HEAT", "TURBO")) {
                compose.onNodeWithTag("remote-mode-$mode").performScrollTo().performClick()
                settle(1_500)
                compose.onNodeWithTag("remote-mode-COOL").performScrollTo()
                settle()
                shot("$look-04-remote-${mode.lowercase()}")
            }
            compose.onNodeWithContentDescription("Fan").performScrollTo()
            settle()
            shot("$look-05-remote-lower")
            compose.onNodeWithTag("remote-mode-STANDBY").performScrollTo().performClick()
            settle(1_500)
            seedHistory()
            compose.onAllNodesWithText("Tonight")[0].performClick()
            settle()
            compose.onNodeWithTag("last-night").performScrollTo()
            settle()
            shot("$look-08-tonight-last-night")
            compose.onAllNodesWithText("Settings")[0].performClick()
            settle()
            shot("$look-06-settings-home")
            compose.onNodeWithTag("settings-page-sleep").performScrollTo().performClick()
            settle()
            shot("$look-07-sleep-page")
            for (section in listOf("figures", "timeline", "history")) {
                compose.onNodeWithTag("sleep-list").performScrollToKey(section)
                settle()
                shot("$look-07-sleep-$section")
            }
            compose.onNodeWithTag("sleep-history-row").performScrollTo().performClick()
            settle()
            shot("$look-09-sleep-history-settings")
            // Tapping Settings again returns to its home from any page.
            compose.onAllNodesWithText("Settings")[0].performClick()
            settle()
        }
        // Health Connect cannot run here, so the chart is drawn on its own with a typical night's stages
        // against a seeded night's BedJet programme.
        val night = graph.repository.history.value.first { it.id == "capture-2" }
        val start = java.time.ZonedDateTime.parse(night.startedAt).toInstant()
        val end = java.time.ZonedDateTime.parse(night.wakeAt).toInstant()
        val pattern = listOf(1 to 12, 4 to 25, 5 to 45, 4 to 30, 6 to 20, 4 to 40, 5 to 35, 1 to 5, 4 to 45, 6 to 30, 4 to 50,
            5 to 20, 4 to 40, 6 to 35, 1 to 8, 4 to 30, 6 to 25)
        var at = start
        val stages = pattern.mapNotNull { (code, minutes) ->
            if (!at.isBefore(end)) null else io.github.willzy12h.nocturne.platform.SleepStage(at, minOf(at.plusSeconds(minutes * 60L), end), code)
                .also { at = at.plusSeconds(minutes * 60L) }
        }
        for (theme in listOf(ThemeMode.DARK, ThemeMode.LIGHT)) {
            val themed = graph.repository.settings.value.copy(themeMode = theme)
            compose.runOnUiThread {
                compose.activity.setContent {
                    NocturneTheme(themed, graph.now(), dark = theme == ThemeMode.DARK) {
                        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()
                            .background(paletteTokens.ground).padding(16.dp)) {
                            androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier
                                .background(paletteTokens.surface, androidx.compose.foundation.shape.RoundedCornerShape(20.dp)).padding(20.dp),
                                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                                androidx.compose.material3.Text(compose.activity.getString(R.string.feature_health_source),
                                    color = paletteTokens.ink, style = androidx.compose.material3.MaterialTheme.typography.labelLarge)
                                io.github.willzy12h.nocturne.ui.features.SleepStageChart(stages, start, end, historyPlan(night))
                            }
                        }
                    }
                }
            }
            settle(1_000)
            shot("tour-${if (theme == ThemeMode.DARK) "dark" else "light"}-08-sleep-chart")
        }
    }

    /**
     * Every tab, page, sheet and dialog in the Midnight Ink palette, for the public screen gallery. Runs only with
     * SCREENSHOT_TAG=gallery: `SCREENSHOT_TAG=gallery ./gradlew :app:testDebugUnitTest --tests '*ScreenshotCapture.gallery'`.
     * Long pages are captured in screen-sized parts. Each shot is listed, in order, in gallery/captions.tsv.
     */
    @Test fun gallery() {
        assumeTrue("the gallery renders only for SCREENSHOT_TAG=gallery", tag == "gallery")
        var count = 0
        val captions = File("build/screenshots/$tag/gallery").apply { mkdirs() }.resolve("captions.tsv")
        captions.writeText("")
        fun s(id: Int) = compose.activity.getString(id)
        fun named(section: String, caption: String): String {
            count++
            val name = "gallery-%02d".format(count)
            captions.appendText("$name\t$section\t$caption\n")
            return name
        }
        fun one(section: String, caption: String, windowView: android.view.View? = null) =
            shot(named(section, caption), windowView)
        /** A page and, when it scrolls, the rest of it a screen at a time. */
        fun page(section: String, caption: String) {
            one(section, caption)
            val vertical = androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange
            val scroller = compose.onAllNodes(SemanticsMatcher.keyIsDefined(vertical))
                .fetchSemanticsNodes().maxByOrNull { it.boundsInRoot.height } ?: return
            val step = scroller.boundsInRoot.height * 0.85f
            for (part in 2..6) {
                val before = compose.onAllNodes(SemanticsMatcher.keyIsDefined(vertical)).fetchSemanticsNodes()
                    .firstOrNull { it.id == scroller.id }?.config?.get(vertical)?.value?.invoke() ?: return
                compose.onAllNodes(SemanticsMatcher("scroller ${scroller.id}") { it.id == scroller.id })[0]
                    .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) { it(0f, step) }
                settle()
                val after = compose.onAllNodes(SemanticsMatcher.keyIsDefined(vertical)).fetchSemanticsNodes()
                    .firstOrNull { it.id == scroller.id }?.config?.get(vertical)?.value?.invoke() ?: return
                if (after <= before + 1f) return
                one(section, "$caption (continued)")
            }
        }
        fun save(block: suspend () -> Unit) { compose.runOnUiThread { runBlocking { block() } }; settle() }
        fun connectDemo() {
            if (graph.link.status.value == null) compose.runOnUiThread { graph.sessions.reconnect() }
            compose.waitUntil(15_000) { settle(250); graph.link.status.value != null }
        }
        fun top() = compose.onAllNodes(hasScrollAction()).fetchSemanticsNodes().forEach { node ->
            runCatching { compose.onAllNodes(SemanticsMatcher("scroller ${node.id}") { it.id == node.id })[0]
                .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) { it(0f, -100_000f) } }
        }.also { settle() }

        save {
            graph.repository.updateSettings { it.copy(demoMode = true, themeMode = ThemeMode.DARK, simpleView = false,
                nightPalette = Palette.MIDNIGHT_INK) }
            graph.repository.setActiveNight(null)
        }
        connectDemo()
        seedHistory()

        compose.navTab("Tonight"); top()
        page("Tonight", "Tonight, before bed")
        compose.onNodeWithTag("quick-mode-BOOST", useUnmergedTree = true).performScrollTo().performTouchInput { longClick() }
        settle()
        one("Tonight", "Editing a quick start (long-press a tile)")
        compose.onAllNodesWithText(s(R.string.feature_cancel))[0].performClick(); settle()

        compose.navTab("Remote")
        compose.waitUntil(15_000) { settle(250); compose.onAllNodesWithTag("remote-mode-COOL").fetchSemanticsNodes().isNotEmpty() }
        if (graph.link.status.value?.isRunning == true) {
            compose.onNodeWithTag("remote-mode-STANDBY").performScrollTo().performClick(); settle(1_500)
        }
        top()
        page("Remote", "Remote, BedJet off")
        compose.onNodeWithTag("remote-mode-COOL").performScrollTo().performClick(); settle(1_500)
        top()
        page("Remote", "Remote, cooling")
        compose.onNodeWithTag("remote-mode-STANDBY").performScrollTo().performClick(); settle(1_500)

        compose.navTab("Profiles"); top()
        page("Profiles", "Profiles")
        val profileId = graph.repository.profiles.value.first().id
        compose.onNode(hasScrollToKeyAction()).performScrollToKey(profileId)
        compose.onNodeWithTag("profile-more-$profileId", useUnmergedTree = true).performScrollTo().performClick(); settle()
        one("Profiles", "A profile's menu")
        compose.activity.onBackPressedDispatcher.let { d -> compose.runOnUiThread { d.onBackPressed() } }; settle()
        if (compose.onAllNodesWithTag("profile-more-$profileId", useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) compose.navTab("Profiles")
        compose.onAllNodes(hasTestTagPrefix("profile-open-"), useUnmergedTree = true)[0].performScrollTo().performClick(); settle()
        page("Profiles", "Editing a profile")
        top()
        compose.onNodeWithTag("editor-phase-START-0").performScrollTo().performClick(); settle()
        one("Profiles", "Editing one step")
        compose.onNodeWithTag("step-done").performClick(); settle()
        compose.onNodeWithTag("editor-add-START").performScrollTo().performClick(); settle()
        one("Profiles", "Adding a step")
        compose.onAllNodesWithText(s(R.string.feature_cancel))[0].performClick(); settle()
        compose.onNodeWithTag("editor-back").performClick(); settle()

        compose.navTab("Settings"); top()
        page("Settings", "Settings")
        val pages = listOf("bedjet" to "Your BedJet", "night" to "Night and wake", "appearance" to "Appearance",
            "permissions" to "Permissions", "backups" to "Backups", "notifications" to "Notifications",
            "sleep" to "Sleep and history", "health" to "Health Connect", "about" to "About")
        for ((id, caption) in pages) {
            // Return to the Settings home and bring the row into view; the home list composes lazily.
            repeat(3) {
                if (compose.onAllNodesWithTag("settings-page-bedjet").fetchSemanticsNodes().isEmpty() &&
                    compose.onAllNodesWithTag("settings-back").fetchSemanticsNodes().isNotEmpty()) { top(); compose.backSettingsPage(); settle() }
            }
            top()
            runCatching { compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("settings-page-$id")) }
            settle()
            check(compose.onAllNodesWithTag("settings-page-$id").fetchSemanticsNodes().isNotEmpty()) { "Settings page $id is not reachable" }
            compose.openSettingsPage(id); settle(); top()
            page("Settings", caption)
            top(); compose.backSettingsPage(); settle()
        }

        save {
            val profile = Presets.summerCool.copy(id = "gallery-checkin", name = "Summer comfort")
            graph.repository.saveProfile(profile)
            graph.repository.addRecord(NightRecord("gallery-checkin-night", profile.id, profile.name,
                "2026-10-21T23:45Z", "2026-10-22T08:00Z", false,
                events = listOf(HistoryEvent("2026-10-22T08:05Z", EventKind.ENDED, "Night ended"))))
        }
        // The newest night is the one waiting for a check-in; older demo nights already have answers.
        compose.navTab("History")
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(s(R.string.checkin_button)))
        compose.onAllNodesWithText(s(R.string.checkin_button))[0].performScrollTo().performClick(); settle()
        one("Sleep", "Morning check-in")
        compose.onNode(hasText(s(R.string.checkin_too_hot)) and hasAnyAncestor(isDialog())).performClick()
        compose.waitUntil(15_000) { settle(100); compose.onAllNodesWithText(s(R.string.checkin_change_title)).fetchSemanticsNodes().isNotEmpty() }
        one("Sleep", "A suggested change after a check-in")
        compose.onNode(hasText(s(R.string.checkin_keep)) and hasAnyAncestor(isDialog())).performClick(); settle()

        compose.navTab("Settings")
        compose.navTab("Tonight"); top()
        compose.onAllNodesWithText(s(R.string.tonight_start_now))[0].performScrollTo().performClick()
        compose.waitUntil(15_000) {
            settle(250)
            graph.repository.activeNight.value?.confidence == Confidence.CONFIRMED &&
                graph.actions.status.value.confidence == Confidence.CONFIRMED && graph.actions.busy.value.runningLabel == null
        }
        settle(6_000) // let the confirmation message bar time out
        top()
        page("Tonight", "Tonight, while a night runs")
        save { graph.actions.execute(graph.actions.capture(NightActions.Command.STOP_TRACKING)) }
        compose.mainClock.advanceTimeBy(6_000); settle()

        save { graph.repository.updateSettings { it.copy(demoMode = false, targetDevice = null) } }
        compose.navTab("Tonight"); top()
        page("First run", "Choosing a BedJet")
        assertTrue("The gallery must capture every page", count >= 20)
    }

    private fun acceptLight(theme: ThemeMode) {
        if (theme == ThemeMode.LIGHT) {
            val switch = compose.activity.getString(R.string.theme_switch_now)
            if (compose.onAllNodesWithText(switch).fetchSemanticsNodes().isNotEmpty()) {
                compose.onNodeWithText(switch).performClick()
                settle()
            }
            compose.onNodeWithText(switch).assertDoesNotExist()
        }
    }

    private fun settle(ms: Long = 500) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
    }

    /** The editor, one step's sheet and the Add a step sheet, then back to Profiles. */
    /** A few finished nights so History shows the design's cards; only added once per run. */
    private fun seedHistory() {
        if (graph.repository.history.value.any { it.id.startsWith("capture-") }) return
        fun night(id: String, daysAgo: Long, name: String, steps: List<Triple<Mode, Int, Int>>,
            comfort: MorningComfort?, errors: Int) {
            val start = graph.now().minusDays(daysAgo).withHour(22).withMinute(31 + daysAgo.toInt() * 7 % 29)
            var at = start
            val recorded = steps.mapIndexed { index, (mode, temp, minutes) ->
                val block = when (index) { 0, 1 -> Block.START; steps.lastIndex -> Block.AFTER_WAKE; else -> Block.MIDDLE }
                RecordedStep(block, mode, temp, 40, at.toString(), minutes).also { at = at.plusMinutes(minutes.toLong()) }
            }
            val end = at
            val events = buildList {
                add(HistoryEvent(start.toString(), EventKind.STARTED, "Started"))
                repeat(errors) { add(HistoryEvent(start.plusHours(3).toString(), EventKind.ERROR, "A change didn't send")) }
                add(HistoryEvent(end.toString(), EventKind.ENDED, "Ended"))
                comfort?.let { add(HistoryEvent(end.plusMinutes(10).toString(), EventKind.MORNING_FEEDBACK, it.name, it)) }
            }
            runBlocking { graph.repository.addRecord(NightRecord(id, "capture-$id", name, start.toString(), end.toString(), true,
                steps = recorded, events = events)) }
        }
        compose.runOnUiThread {
            night("capture-1", 1, "Summer comfort", listOf(Triple(Mode.COOL, 40, 30), Triple(Mode.COOL, 44, 400), Triple(Mode.HEAT, 60, 40), Triple(Mode.COOL, 44, 20)),
                MorningComfort.JUST_RIGHT, 0)
            night("capture-2", 2, "Winter warm", listOf(Triple(Mode.TURBO, 86, 15), Triple(Mode.HEAT, 80, 30), Triple(Mode.HEAT, 64, 400), Triple(Mode.HEAT, 70, 30), Triple(Mode.COOL, 44, 15)),
                MorningComfort.TOO_HOT, 0)
            night("capture-3", 3, "Winter warm", listOf(Triple(Mode.TURBO, 86, 15), Triple(Mode.HEAT, 80, 30), Triple(Mode.HEAT, 64, 200), Triple(Mode.WAIT, 0, 30),
                Triple(Mode.HEAT, 66, 170), Triple(Mode.HEAT, 72, 40)), null, 1)
            night("capture-4", 9, "Summer comfort", listOf(Triple(Mode.COOL, 44, 480)), null, 0)
            night("capture-5", 12, "Summer comfort", listOf(Triple(Mode.COOL, 44, 480)), null, 0)
        }
        settle()
    }

    private fun themeMode(mode: ThemeMode) {
        compose.runOnUiThread { runBlocking { graph.repository.updateSettings { it.copy(themeMode = mode) } } }
        settle()
    }

    private fun captureSettingsPages(look: String) {
        listOf("appearance", "night", "permissions", "backups", "notifications", "about").forEachIndexed { index, page ->
            compose.onNodeWithTag("settings-page-$page").performScrollTo().performClick()
            settle()
            if (page == "appearance" && look == "dark") themeMode(ThemeMode.SCHEDULED)
            shot("$look-05${'a' + index}-settings-$page")
            if (page == "appearance" && look == "dark") themeMode(ThemeMode.DARK)
            compose.onNodeWithTag("settings-back").performClick()
            settle()
        }
    }

    private fun captureEditor(look: String) {
        compose.onAllNodes(hasTestTagPrefix("profile-open-"), useUnmergedTree = true)[0].performClick()
        settle()
        shot("$look-02b-editor")
        compose.onNodeWithTag("editor-phase-START-0").performScrollTo().performClick()
        settle()
        shot("$look-02c-step-editor")
        compose.onNodeWithTag("step-done").performClick()
        settle()
        compose.onNodeWithTag("editor-add-START").performScrollTo().performClick()
        settle()
        shot("$look-02d-add-step")
        compose.onAllNodesWithText(compose.activity.getString(R.string.feature_cancel))[0].performClick()
        settle()
        compose.onNodeWithTag("editor-back").performClick()
        settle()
    }

    private fun hasTestTagPrefix(prefix: String) = androidx.compose.ui.test.SemanticsMatcher("tag starts with $prefix") {
        androidx.compose.ui.semantics.SemanticsProperties.TestTag in it.config && it.config[androidx.compose.ui.semantics.SemanticsProperties.TestTag].startsWith(prefix)
    }

    private fun shot(name: String, windowView: android.view.View? = null) {
        // The redesign tour is for review and the README, not the release inventory, so it gets its own folder.
        val dir = File("build/screenshots/$tag" + when {
            name.startsWith("tour-") -> "/tour"
            name.startsWith("gallery-") -> "/gallery"
            else -> ""
        }).apply { mkdirs() }
        val config = compose.activity.resources.configuration
        assertEquals("Capture must use the pinned Graph.clock instant", captureInstant, graph.clock.instant())
        assertEquals("Capture must use the pinned Graph.clock zone", captureZone, graph.clock.zone)
        assertEquals("Capture width in dp", expectedWidthDp, config.screenWidthDp)
        assertEquals("Capture font scale", expectedFontScale, config.fontScale, 0.001f)
        // Drawing the window directly; captureToImage waits on frame callbacks Robolectric's paused clock never sends.
        val views = windowView?.let(::listOf) ?: android.view.inspector.WindowInspector.getGlobalWindowViews()
            .filter { it.isShown && it.width > 0 && it.height > 0 }
        assertTrue("Capture must include a visible window", views.isNotEmpty())
        if (name.startsWith("gallery-") && windowView == null && views.size > 1) {
            // The gallery shows a dialog, sheet or menu where it sits, over the dimmed screen behind it.
            val base = views.first()
            val bitmap = Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ARGB_8888)
            try {
                compose.runOnUiThread {
                    val canvas = Canvas(bitmap)
                    base.draw(canvas)
                    canvas.drawColor(0x99000000.toInt())
                    val origin = IntArray(2).also(base::getLocationOnScreen)
                    views.drop(1).forEach { window ->
                        val at = IntArray(2).also(window::getLocationOnScreen)
                        canvas.save()
                        canvas.translate((at[0] - origin[0]).toFloat(), (at[1] - origin[1]).toFloat())
                        window.draw(canvas)
                        canvas.restore()
                    }
                }
                NativePngCapture.write(bitmap, File(dir, "$name.png"))
            } finally { bitmap.recycle() }
        } else views.forEachIndexed { index, view ->
            assertTrue("Capture window must be laid out", view.width > 0 && view.height > 0)
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            try {
                compose.runOnUiThread { view.draw(Canvas(bitmap)) }
                NativePngCapture.write(bitmap, File(dir, if (index == 0) "$name.png" else "$name-window$index.png"))
            } finally { bitmap.recycle() }
        }
        if (name.startsWith("gallery-")) writeTapTargets(File(dir, "$name.nodes.json"), views.first())
        val view = views.first()
        File(dir, "$name.json").writeText("""
            {"renderer":"Robolectric native graphics","sdk":34,"tag":"$tag",
             "clockInstant":"${graph.clock.instant()}","clockZone":"${graph.clock.zone}",
             "screenWidthDp":${config.screenWidthDp},"screenHeightDp":${config.screenHeightDp},
             "fontScale":${config.fontScale},"densityDpi":${config.densityDpi},
             "pixelWidth":${view.width},"pixelHeight":${view.height},
             "runtimePermissions":"BLUETOOTH_SCAN,BLUETOOTH_CONNECT,POST_NOTIFICATIONS explicitly granted; BLUETOOTH_ADVERTISE,android.permission.health.READ_SLEEP explicitly denied",
             "theme":"${graph.repository.settings.value.themeMode}",
             "simpleView":${graph.repository.settings.value.simpleView},"demoMode":${graph.repository.settings.value.demoMode}}
        """.trimIndent() + "\n")
    }

    /** Records every tappable element's box (as % of the screen) and label, so the interactive tour taps where the app does. */
    private fun writeTapTargets(file: File, base: android.view.View) {
        val origin = IntArray(2).also(base::getLocationOnScreen)
        val width = base.width.toFloat()
        val height = base.height.toFloat()
        val roots = compose.onAllNodes(isRoot()).fetchSemanticsNodes()
        // A dialog, sheet or menu blocks the screen behind it, so only its own buttons are live.
        val live = if (roots.size > 1) roots.drop(1) else roots
        val out = mutableListOf<String>()
        fun quote(text: String) = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\""
        fun walk(node: androidx.compose.ui.semantics.SemanticsNode) {
            val config = node.config
            if (androidx.compose.ui.semantics.SemanticsActions.OnClick in config) {
                val offset = node.positionOnScreen - node.positionInWindow
                val box = node.boundsInWindow.translate(offset)
                val left = maxOf(box.left - origin[0], 0f)
                val top = maxOf(box.top - origin[1], 0f)
                val right = minOf(box.right - origin[0], width)
                val bottom = minOf(box.bottom - origin[1], height)
                if (right - left >= 4f && bottom - top >= 4f) {
                    val text = config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)
                        ?.joinToString(" ") { it.text }.orEmpty()
                    val description = config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription)
                        ?.joinToString(" ").orEmpty()
                    val tag = config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag).orEmpty()
                    out += "{\"x\":%.2f,\"y\":%.2f,\"w\":%.2f,\"h\":%.2f,\"text\":%s,\"description\":%s,\"tag\":%s}".format(
                        java.util.Locale.ROOT, left / width * 100, top / height * 100,
                        (right - left) / width * 100, (bottom - top) / height * 100,
                        quote(text), quote(description), quote(tag))
                }
            }
            node.children.forEach(::walk)
        }
        live.forEach(::walk)
        file.writeText(out.joinToString(",\n", "[\n", "\n]\n"))
    }
}
