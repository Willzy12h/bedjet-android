package io.github.willzy12h.nocturne

import io.github.willzy12h.nocturne.ui.openSettingsPage
import io.github.willzy12h.nocturne.ui.backSettingsPage

import android.Manifest
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.*
import io.github.willzy12h.nocturne.ui.*
import io.github.willzy12h.nocturne.core.store.*
import io.github.willzy12h.nocturne.core.plan.Presets
import io.github.willzy12h.nocturne.core.night.Confidence
import io.github.willzy12h.nocturne.night.NightActions
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
            // Explicit policy: these captures use granted runtime permissions; no host defaults.
            shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS)
            shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_ADVERTISE,
                "android.permission.health.READ_SLEEP")
            val oldClock = app.graph.clock
            val oldScale = app.resources.configuration.fontScale
            app.graph.clock = Clock.fixed(captureInstant, captureZone)
            val combined = description.methodName.contains("combined320And200") || description.methodName.contains("Compact")
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
    @get:Rule val rules: TestRule = RuleChain.outerRule(GraphLifecycle()).around(pinClock).around(compose)

    private val graph get() = compose.activity.graph

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
        compose.onAllNodesWithText("History")[0].performClick()
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
            if (expectedWidthDp == 320) compose.onAllNodes(hasContentDescription(label))[0].performClick()
            else compose.onAllNodesWithText(label)[0].performClick()
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
        tab("Profiles")
        val profileId = graph.repository.profiles.value.first().id
        compose.onNode(hasScrollToKeyAction()).performScrollToKey(profileId)
        compose.onNode(hasText(s(R.string.recovery_more)) and hasClickAction() and
            hasAnyAncestor(hasTestTag("profile-card-$profileId"))).performScrollTo().performClick()
        settle()
        tap(s(R.string.pin_menu), scroll = false)
        compose.onNodeWithText(compose.activity.getString(R.string.pin_slot_help, graph.repository.settings.value.workingSlot + 1)).assertExists()
        shot("gaps-$look-01-slot-pin")
        tap(s(R.string.feature_cancel), scroll = false)
        tab("Tonight")
        save { graph.repository.updateSettings { it.setWakeOverride(graph.now().plusHours(7), graph.now()) } }
        val expiry = graph.now().plusHours(7)
        val note = compose.activity.getString(R.string.override_expiry, Format.clock(expiry), expiry.format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM")))
        compose.onNodeWithText(note).performScrollTo().assertIsDisplayed()
        shot("gaps-$look-02-wake-expiry")
        tap(compose.activity.getString(R.string.tonight_change_wake, Format.clock(expiry)))
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
        tab("History")
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
        compose.onNodeWithText(s(R.string.setup_use_demo)).performScrollTo().assertIsDisplayed()
        shot("gaps-$look-07-setup-demo")
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
        shot("$look-01-tonight")
        compose.onNodeWithText(compose.activity.getString(R.string.tonight_start_now)).performScrollTo()
        settle()
        compose.onNodeWithText(compose.activity.getString(R.string.tonight_start_now)).assertIsDisplayed()
        shot("$look-01b-tonight-lower")
        // Remote is a Tonight sub-page opened from the status line.
        compose.onNodeWithTag("open-remote").performScrollTo().performClick()
        settle()
        shot("$look-03-remote")
        compose.onNodeWithTag("remote-back").performClick()
        settle()
        for ((tab, name) in listOf("Profiles" to "02-profiles", "History" to "04-history", "Settings" to "05-settings")) {
            if (look.startsWith("combined-")) compose.onAllNodes(hasContentDescription(tab))[0].performClick()
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

    private fun settle(ms: Long = 500) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
    }

    /** The editor, one step's sheet and the Add a step sheet, then back to Profiles. */
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
        val dir = File("build/screenshots/$tag").apply { mkdirs() }
        val config = compose.activity.resources.configuration
        assertEquals("Capture must use the pinned Graph.clock instant", captureInstant, graph.clock.instant())
        assertEquals("Capture must use the pinned Graph.clock zone", captureZone, graph.clock.zone)
        assertEquals("Capture width in dp", expectedWidthDp, config.screenWidthDp)
        assertEquals("Capture font scale", expectedFontScale, config.fontScale, 0.001f)
        // Drawing the window directly; captureToImage waits on frame callbacks Robolectric's paused clock never sends.
        val view = windowView ?: org.robolectric.shadows.ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }?.window?.decorView ?: compose.activity.window.decorView
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
             "runtimePermissions":"BLUETOOTH_SCAN,BLUETOOTH_CONNECT,POST_NOTIFICATIONS explicitly granted; BLUETOOTH_ADVERTISE,android.permission.health.READ_SLEEP explicitly denied",
             "theme":"${graph.repository.settings.value.themeMode}",
             "simpleView":${graph.repository.settings.value.simpleView},"demoMode":${graph.repository.settings.value.demoMode}}
        """.trimIndent() + "\n")
        bitmap.recycle()
    }
}
