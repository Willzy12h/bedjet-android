package io.github.willzy12h.nocturne.ui

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Intent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import io.github.willzy12h.nocturne.App
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.night.ActiveNight
import io.github.willzy12h.nocturne.core.night.AppliedProgram
import io.github.willzy12h.nocturne.core.night.Confidence
import io.github.willzy12h.nocturne.core.plan.PlanCompiler
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
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
import org.robolectric.util.ReflectionHelpers
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.TimeZone

/** Acceptance regression coverage uses the real screen, persisted settings and Android's next alarm. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class TonightAcceptanceTest {
    private val fixedClock = Clock.fixed(Instant.parse("2026-10-22T22:30:00Z"), ZoneOffset.UTC)
    private val ui = U06Fixture(U06Look.SIMPLE_MIDNIGHT, widthDp = 411, fontScale = 1f) { fixture, description ->
        val graph = fixture.graph
        graph.clock = fixedClock
        runBlocking {
            val profile = graph.repository.tonightsProfile()!!.let {
                it.copy(wakeSource = it.wakeSource.copy(useAlarm = false, defaultWakeMinute = 7 * 60))
            }
            graph.repository.saveProfile(profile)
            graph.repository.updateSettings { it.chooseTonight(profile.id, graph.now()).copy(tonightOverride = null) }
            if (description.methodName.startsWith("acceptedAlarmBeatsTheStoredNight")) {
                val wake = graph.now().plusHours(8).plusMinutes(30)
                val plan = PlanCompiler.compile(profile, graph.now(), wake)
                graph.repository.setActiveNight(ActiveNight("saved", profile.id, graph.now().toString(),
                    wake.toString(), false, 2, plan.wakeUpStart.toString(), plan.end.toString(),
                    deviceId = "demo", profile = profile, applied = AppliedProgram.from(plan), confidence = Confidence.CONFIRMED))
            }
            if (description.methodName.startsWith("explicitTonightOverride")) {
                graph.repository.updateSettings { it.setWakeOverride(graph.now().plusHours(8).plusMinutes(15), graph.now()) }
            }
        }
        if (description.methodName.startsWith("acceptedAlarm") || description.methodName.startsWith("explicitTonightOverride") ||
            description.methodName.startsWith("tooSoon") || description.methodName.startsWith("tooLate")) {
            val app = RuntimeEnvironment.getApplication() as App
            val pending = PendingIntent.getBroadcast(app, 1201, Intent("acceptance.next.alarm"), PendingIntent.FLAG_IMMUTABLE)
            shadowOf(pending).setCreatorPackage("com.sec.android.app.clockpackage")
            val at = when {
                description.methodName.startsWith("tooSoon") -> "2026-10-22T23:00:00Z"
                description.methodName.startsWith("tooLate") -> "2026-10-23T16:31:00Z"
                else -> "2026-10-23T06:30:00Z"
            }
            app.getSystemService(AlarmManager::class.java).setAlarmClock(
                AlarmManager.AlarmClockInfo(Instant.parse(at).toEpochMilli(), pending), pending)
        }
    }
    private val timezone = TestRule { base, _ -> object : Statement() {
        override fun evaluate() {
            val previous = TimeZone.getDefault()
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            try { base.evaluate() } finally { TimeZone.setDefault(previous) }
        }
    } }
    @get:Rule val rules: TestRule = RuleChain.outerRule(timezone).around(ui.rules)

    // The approved compact Tonight card removed independently collapsible sections. Old saved
    // collapse preferences must never hide the replacement controls after a process restart.
    @Test fun legacyNightCollapseCannotHideTheCompactPlanAfterRestart() {
        restartWithLegacyCollapse("night")
        ui.compose.onNodeWithTag("tonight-card").assertExists()
        ui.compose.openTonightDetail()
        ui.compose.onNodeWithText("Wake time", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test fun legacyWakeCollapseCannotHideTheWakePickerAfterRestart() {
        restartWithLegacyCollapse("wake")
        ui.checkControl(ui.compose.onNodeWithTag("tonight-fixed-wake"), "Wake picker").performClick()
        val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog()
        assertTrue(dialog is android.app.TimePickerDialog && dialog.isShowing)
        ui.compose.runOnUiThread { dialog.dismiss() }
    }

    @Test fun legacyConnectionCollapseCannotHideRemoteAfterRestart() {
        restartWithLegacyCollapse("connection")
        ui.compose.openRemote()
        ui.compose.onNodeWithTag("remote-back").assertIsDisplayed()
        ui.compose.onNodeWithText(ui.s(R.string.remote_title)).assertExists()
    }

    @Test fun legacyQuickCollapseCannotHideTheApprovedPresetsAfterRestart() {
        restartWithLegacyCollapse("quick")
        listOf("TURBO", "HEAT", "COOL", "DRY", "BOOST", "STANDBY").forEach {
            ui.checkControl(ui.compose.onNodeWithTag("quick-mode-$it"), "Quick $it")
        }
        ui.compose.onNodeWithTag("quick-mode-HEAT").performScrollTo().performClick()
        ui.waitUntil { ui.graph.link.status.value?.mode == io.github.willzy12h.nocturne.core.protocol.Mode.HEAT }
    }

    private fun restartWithLegacyCollapse(section: String) {
        ui.compose.runOnUiThread { runBlocking {
            ui.graph.repository.updateSettings {
                if (section == "quick") it.copy(quickControlsCollapsed = true)
                else it.copy(collapsedTonightSections = setOf(section))
            }
        } }
        val app = RuntimeEnvironment.getApplication() as App
        ui.compose.runOnUiThread { ui.compose.activity.viewModelStore.clear() }
        val replacement = ui.graphs.fresh { Graph(app).also { it.clock = fixedClock } }
        ReflectionHelpers.setField(app, "graph", replacement)
        ui.compose.activityRule.scenario.recreate()
        assertTrue(if (section == "quick") replacement.repository.settings.value.quickControlsCollapsed
            else section in replacement.repository.settings.value.collapsedTonightSections)
    }

    @Test fun acceptedAlarmBeatsAProfileThatUsesAFixedWake() {
        ui.compose.onNodeWithTag("tonight-fixed-wake").assertTextContains("06:30", substring = true)
        ui.compose.onNodeWithTag("tonight-fixed-wake").assertTextContains("Next phone alarm", substring = true)
        assertEquals("The displayed phone alarm must not silently rewrite the profile", false,
            ui.model.tonightsProfile()!!.wakeSource.useAlarm)
    }

    @Test fun acceptedAlarmBeatsTheStoredNightEndWithoutClaimingARetime() {
        ui.compose.onNodeWithTag("tonight-fixed-wake").assertTextContains("06:30", substring = true)
        ui.compose.onNodeWithTag("tonight-fixed-wake").assertTextContains("Next phone alarm", substring = true)
        assertEquals("2026-10-23T07:00Z", ui.graph.repository.activeNight.value!!.wakeAt)
    }

    @Test fun noAlarmShowsTheProfileFallbackWithAReason() {
        ui.compose.onNodeWithTag("tonight-fixed-wake").assertTextContains("07:00", substring = true)
        ui.compose.onNodeWithTag("tonight-fixed-wake").assertTextContains("Profile fallback", substring = true)
        ui.checkLayout(ui.compose.onNodeWithText("No phone alarm available"), "No-alarm fallback reason")
    }

    @Test fun explicitTonightOverrideWinsOverAnAcceptedPhoneAlarm() {
        ui.compose.onNodeWithTag("tonight-fixed-wake").assertTextContains("06:45", substring = true)
        ui.compose.onNodeWithTag("tonight-fixed-wake").assertTextContains("Tonight-only wake", substring = true)
    }

    @Test fun tooSoonPhoneAlarmShowsTheWindowFallback() {
        ui.compose.onNodeWithTag("tonight-fixed-wake").assertTextContains("07:00", substring = true)
        ui.compose.onNodeWithTag("tonight-fixed-wake").assertTextContains("Profile fallback", substring = true)
        ui.checkLayout(ui.compose.onNodeWithText("Next alarm is less than 1 hour after the night starts"), "Too-soon alarm fallback reason")
    }

    @Test fun tooLatePhoneAlarmShowsTheWindowFallback() {
        ui.compose.onNodeWithTag("tonight-fixed-wake").assertTextContains("07:00", substring = true)
        ui.compose.onNodeWithTag("tonight-fixed-wake").assertTextContains("Profile fallback", substring = true)
        ui.checkLayout(ui.compose.onNodeWithText("Next alarm is beyond the 18-hour night window"), "Too-late alarm fallback reason")
    }

    @Test fun profilesUseTheReadableSimpleTimelineEvenWhenTheCurveStyleIsSaved() {
        ui.compose.runOnIdle { runBlocking { ui.graph.repository.updateSettings { it.copy(simpleView = false) } } }
        ui.compose.onNode(hasText(ui.s(R.string.tab_profiles)) and hasClickAction()).performClick()
        ui.compose.onAllNodesWithTag("night-curve").assertCountEquals(0)
        ui.compose.onNodeWithTag("profile-timeline-${ui.graph.repository.profiles.value.first().id}", useUnmergedTree = true)
            .assertExists().assertContentDescriptionContains("Wake time", substring = true)
    }
}
