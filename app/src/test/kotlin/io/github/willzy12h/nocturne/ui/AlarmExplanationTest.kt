package io.github.willzy12h.nocturne.ui

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.TimePickerDialog
import android.content.Intent
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.willzy12h.nocturne.App
import io.github.willzy12h.nocturne.core.night.ActiveNight
import io.github.willzy12h.nocturne.core.night.AppliedProgram
import io.github.willzy12h.nocturne.core.night.ChangeKind
import io.github.willzy12h.nocturne.core.night.Confidence
import io.github.willzy12h.nocturne.core.night.PendingChange
import io.github.willzy12h.nocturne.core.night.WakeOrigin
import io.github.willzy12h.nocturne.core.plan.PlanCompiler
import io.github.willzy12h.nocturne.night.AlarmSource
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runners.model.Statement
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.TimeZone

/** Host-side Tonight rendering and policy; no Samsung or physical BedJet result is claimed. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class AlarmExplanationTest(private val scenario: Scenario) {
    enum class Scenario(val expected: String, val expectedWake: String = "07:00") {
        SAMSUNG("Next alarm 06:30 · Samsung Clock — used", "06:30"),
        NULL_DENIED("Alarm 06:30 ignored: source unknown — tap to change"),
        NULL_ALLOWED("Next alarm 06:30 · Unknown app — used", "06:30"),
        APP_DENIED("Alarm 06:30 ignored: app not allowed — tap to change"),
        TOO_SOON("Alarm 23:00 ignored: outside tonight’s 1–16 hour window — tap to change"),
        TOO_LATE("Alarm 14:31 ignored: outside tonight’s 1–16 hour window — tap to change"),
        MINIMUM_BOUNDARY("Next alarm 23:30 · Samsung Clock — used", "23:30"),
        MAXIMUM_BOUNDARY("Next alarm 14:30 · Samsung Clock — used", "14:30"),
        NO_ALARM("No alarm set — profile wake 07:00"),
        PROFILE_ONLY("Alarm 06:30 ignored: alarms off for this profile — tap to change"),
        PROFILE_ONLY_NO_ALARM("Profile wake 07:00 — alarms off for this profile"),
        OVERRIDE("Tonight-only wake 06:45 — alarm not used", "06:45"),
        SAVED_MATCH("Saved alarm wake 06:30 · Next alarm 06:30 from Samsung Clock matches", "06:30"),
        SAVED_CHANGED("Saved alarm wake 07:00 · Next alarm 06:30 from Samsung Clock not applied"),
        SAVED_PENDING("Saved wake 07:00 — change pending · Next alarm 06:30 from Samsung Clock"),
        SAVED_MANUAL_PENDING("Saved wake 07:00 — change pending · Next alarm 06:30 from Samsung Clock"),
        SAVED_START_PENDING("Saved wake 06:30 — change pending · Next alarm 06:30 from Samsung Clock", "06:30"),
        SAVED_UNKNOWN("Saved wake 07:00 — BedJet wake unknown · Next alarm 06:30 from Samsung Clock"),
        SAVED_MANUAL_UNKNOWN("Saved wake 07:00 — BedJet wake unknown · Next alarm 06:30 from Samsung Clock"),
        SAVED_FAILED("Saved wake 07:00 — wake change failed · Next alarm 06:30 from Samsung Clock"),
        SAVED_OVERRIDE("Saved tonight-only wake 07:00 · Alarm 06:30 ignored: tonight-only wake set — tap to change"),
        SAVED_OVERRIDE_NO_ALARM("Saved tonight-only wake 07:00"),
        MANUAL("Manual control — no alarm wake programme · Next alarm 06:30 from Samsung Clock"),
    }

    private val ui = U06Fixture(U06Look.MIDNIGHT, widthDp = 411, fontScale = 1f) { fixture, _ ->
        val graph = fixture.graph
        graph.clock = Clock.fixed(Instant.parse("2026-10-22T22:30:00Z"), ZoneOffset.UTC)
        runBlocking {
            val profile = graph.repository.tonightsProfile()!!.let {
                it.copy(wakeSource = it.wakeSource.copy(defaultWakeMinute = 7 * 60,
                    useAlarm = scenario !in setOf(Scenario.PROFILE_ONLY, Scenario.PROFILE_ONLY_NO_ALARM)))
            }
            graph.repository.saveProfile(profile)
            graph.repository.updateSettings {
                it.chooseTonight(profile.id, graph.now()).copy(
                    acceptUnknownAlarmSource = scenario == Scenario.NULL_ALLOWED,
                    alarmPackages = setOf(SAMSUNG_PACKAGE),
                ).let { settings ->
                    if (scenario == Scenario.OVERRIDE) settings.setWakeOverride(
                        ZonedDateTime.parse("2026-10-23T06:45:00Z"), graph.now()) else settings
                }
            }
            if (scenario.name.startsWith("SAVED_") || scenario == Scenario.MANUAL) {
                val wake = ZonedDateTime.parse(if (scenario in setOf(Scenario.SAVED_MATCH, Scenario.SAVED_START_PENDING))
                    "2026-10-23T06:30:00Z" else "2026-10-23T07:00:00Z")
                val plan = PlanCompiler.compile(profile, graph.now(), wake)
                graph.repository.setActiveNight(ActiveNight(
                    recordId = "saved-night", profileId = profile.id, startedAt = graph.now().toString(),
                    wakeAt = wake.toString(), wakeFromAlarm = scenario !in setOf(Scenario.SAVED_OVERRIDE, Scenario.SAVED_OVERRIDE_NO_ALARM),
                    slot = 2, wakeUpStart = plan.wakeUpStart.toString(), endsAt = plan.end.toString(),
                    deviceId = "demo", profile = profile, applied = AppliedProgram.from(plan),
                    wakeOrigin = if (scenario in setOf(Scenario.SAVED_OVERRIDE, Scenario.SAVED_OVERRIDE_NO_ALARM)) WakeOrigin.OVERRIDE else WakeOrigin.ALARM,
                    confidence = when (scenario) {
                        Scenario.SAVED_UNKNOWN, Scenario.SAVED_MANUAL_UNKNOWN -> Confidence.UNKNOWN
                        Scenario.SAVED_FAILED -> Confidence.FAILED
                        else -> Confidence.CONFIRMED
                    }, trackingManual = scenario in setOf(Scenario.MANUAL, Scenario.SAVED_MANUAL_PENDING, Scenario.SAVED_MANUAL_UNKNOWN),
                ))
            }
        }
        if (scenario !in setOf(Scenario.NO_ALARM, Scenario.PROFILE_ONLY_NO_ALARM, Scenario.SAVED_OVERRIDE_NO_ALARM)) {
            val app = RuntimeEnvironment.getApplication() as App
            val pending = PendingIntent.getBroadcast(app, 81, Intent("test.alarm.explanation"), PendingIntent.FLAG_IMMUTABLE)
            shadowOf(pending).setCreatorPackage(if (scenario == Scenario.APP_DENIED) "unapproved.clock" else SAMSUNG_PACKAGE)
            val time = when (scenario) {
                Scenario.TOO_SOON -> "2026-10-22T23:00:00Z"
                Scenario.TOO_LATE -> "2026-10-23T14:31:00Z"
                Scenario.MINIMUM_BOUNDARY -> "2026-10-22T23:30:00Z"
                Scenario.MAXIMUM_BOUNDARY -> "2026-10-23T14:30:00Z"
                else -> "2026-10-23T06:30:00Z"
            }
            // A missing show intent is one route to creator == null; do not fabricate Samsung provenance.
            val showIntent = pending.takeUnless { scenario in setOf(Scenario.NULL_DENIED, Scenario.NULL_ALLOWED) }
            app.getSystemService(AlarmManager::class.java).setAlarmClock(
                AlarmManager.AlarmClockInfo(Instant.parse(time).toEpochMilli(), showIntent), pending)
        }
    }
    private val timezone = TestRule { base, _ -> object : Statement() {
        override fun evaluate() {
            val original = TimeZone.getDefault()
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            try { base.evaluate() } finally { TimeZone.setDefault(original) }
        }
    } }
    @get:Rule val rules: TestRule = RuleChain.outerRule(timezone).around(ui.rules)

    @Test fun explainsTheWakeActuallyShownWithoutChangingAlarmPolicy() {
        if (scenario in setOf(Scenario.SAVED_PENDING, Scenario.SAVED_MANUAL_PENDING, Scenario.SAVED_START_PENDING)) ui.compose.runOnIdle { runBlocking {
            val night = ui.graph.repository.activeNight.value!!
            val kind = when (scenario) {
                Scenario.SAVED_MANUAL_PENDING -> ChangeKind.MANUAL
                Scenario.SAVED_START_PENDING -> ChangeKind.START
                else -> ChangeKind.RETIME
            }
            ui.graph.repository.setActiveNight(night.copy(confidence = Confidence.PENDING,
                applied = if (kind == ChangeKind.START) AppliedProgram() else night.applied,
                pending = PendingChange("change", kind,
                    if (kind == ChangeKind.MANUAL) night.wakeAt else "2026-10-23T06:30:00Z",
                    ui.graph.now().toString(), ui.graph.now().plusMinutes(10).toString(),
                    if (kind == ChangeKind.START) null else night.identity)))
        } }
        ui.compose.waitForIdle()
        val night = ui.graph.repository.activeNight.value
        val wake = night?.wakeAt?.let(ZonedDateTime::parse)
            ?: ui.model.startPreview(ui.model.tonightsProfile()!!).wake.time
        assertEquals("The explanatory text must not loosen the existing wake policy", scenario.expectedWake, Format.clock(wake))
        if (scenario in setOf(Scenario.NULL_DENIED, Scenario.NULL_ALLOWED)) {
            val next = AlarmSource.describe(ui.compose.activity, ui.graph.repository.settings.value)!!
            assertEquals(null, next.creator)
            assertEquals(scenario == Scenario.NULL_ALLOWED, next.counts)
        }
        ui.compose.onNodeWithText(scenario.expected).assertExists().assertHasClickAction()
        val diagnostic = AlarmSource.diagnostics(ui.compose.activity, ui.graph.repository.settings.value,
            night?.let { ui.graph.repository.profile(it.profileId) } ?: ui.model.tonightsProfile(), night,
            ui.graph.repository.runtime.value, ui.model.nightStatus.value, ui.graph.now())
        assertEquals("Exported evidence must give the same explanation as Tonight", scenario.expected,
            diagnostic.getValue("explanation").jsonPrimitive.content)
        assertEquals(when (scenario) {
            Scenario.NULL_DENIED -> "UNKNOWN_SOURCE"
            Scenario.APP_DENIED -> "SOURCE_NOT_ALLOWED"
            Scenario.TOO_SOON, Scenario.TOO_LATE -> "OUTSIDE_WINDOW"
            Scenario.NO_ALARM -> "NO_ALARM"
            Scenario.PROFILE_ONLY, Scenario.PROFILE_ONLY_NO_ALARM -> "PROFILE_ALARMS_OFF"
            Scenario.OVERRIDE, Scenario.SAVED_OVERRIDE, Scenario.SAVED_OVERRIDE_NO_ALARM -> "OVERRIDE"
            else -> "ELIGIBLE"
        }, diagnostic.getValue("reason").jsonPrimitive.content)
        assertEquals(when (scenario) {
            Scenario.SAVED_PENDING, Scenario.SAVED_MANUAL_PENDING, Scenario.SAVED_START_PENDING -> "PENDING"
            Scenario.SAVED_UNKNOWN, Scenario.SAVED_MANUAL_UNKNOWN -> "UNKNOWN"
            Scenario.SAVED_FAILED -> "FAILED"
            Scenario.MANUAL -> "MANUAL"
            Scenario.SAVED_MATCH, Scenario.SAVED_CHANGED, Scenario.SAVED_OVERRIDE, Scenario.SAVED_OVERRIDE_NO_ALARM -> "SAVED"
            else -> "PREVIEW"
        }, diagnostic.getValue("wakeState").jsonPrimitive.content)
        assertEquals(scenario == Scenario.SAVED_MATCH, diagnostic.getValue("matchesSavedWake").jsonPrimitive.boolean)
        assertTrue(diagnostic.getValue("onlyNextAlarmExposed").jsonPrimitive.boolean)
        if (scenario in setOf(Scenario.NULL_DENIED, Scenario.NULL_ALLOWED)) {
            assertEquals(JsonNull, diagnostic.getValue("creatorPackage"))
        }
        if (scenario in setOf(Scenario.TOO_SOON, Scenario.TOO_LATE, Scenario.OVERRIDE, Scenario.SAVED_OVERRIDE, Scenario.SAVED_OVERRIDE_NO_ALARM)) {
            ui.compose.onNodeWithText(scenario.expected).performScrollTo().performClick()
            assertTrue("A wake choice must open the existing tonight-only picker", ShadowDialog.getLatestDialog() is TimePickerDialog)
        }
    }

    companion object {
        private const val SAMSUNG_PACKAGE = "com.sec.android.app.clockpackage"
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun scenarios(): List<Array<Any>> = Scenario.entries.map { arrayOf(it) }
    }
}
