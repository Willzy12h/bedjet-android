package io.github.willzy12h.nocturne.ui

import android.net.Uri
import android.os.Looper
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import io.github.willzy12h.nocturne.App
import io.github.willzy12h.nocturne.GraphLifecycle
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.store.ThemeMode
import io.github.willzy12h.nocturne.graph
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TemporaryFolder
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/** P5: Settings controls for the quiet status notification and diagnostics export, in the real app. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class SettingsControlsTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val graphs = GraphLifecycle()
    private val fixture = TestRule { base, _ -> object : Statement() {
        override fun evaluate() {
            val graph = (RuntimeEnvironment.getApplication() as App).graph
            val previous = graph.clock
            graph.clock = Clock.fixed(Instant.parse("2026-10-22T22:30:00Z"), ZoneOffset.UTC)
            runBlocking { graph.repository.updateSettings { it.copy(demoMode = true, themeMode = ThemeMode.DARK, quietNightNotification = false) } }
            try { base.evaluate() } finally { graph.clock = previous }
        }
    } }
    @get:Rule val rules: TestRule = RuleChain.outerRule(graphs).around(fixture).around(compose)
    @get:Rule val temp = TemporaryFolder()
    private val graph get() = compose.activity.graph
    private fun s(id: Int, vararg args: Any) = compose.activity.getString(id, *args)
    private fun waitUntil(condition: () -> Boolean) = compose.waitUntil(10_000) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
        condition()
    }

    @Test fun theQuietStatusNotificationCanBeSwitchedOnAndOffFromSettings() {
        val graph = graph
        compose.onNodeWithText("Settings").performClick()
        compose.openSettingsPage("notifications")
        val row = compose.onNode(hasContentDescription(s(R.string.settings_quiet_title)))
        row.performScrollTo()
        row.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
        row.assertIsOff()
        assertTrue(row.fetchSemanticsNode().boundsInRoot.height >= 48f * compose.activity.resources.displayMetrics.density - 1f)
        row.performClick()
        waitUntil { graph.repository.settings.value.quietNightNotification }
        row.assertIsOn()
        row.performClick()
        waitUntil { !graph.repository.settings.value.quietNightNotification }
    }

    @Test fun diagnosticsCanBeExportedFromSettings() {
        val graph = graph
        compose.onNodeWithText("Settings").performClick()
        compose.openSettingsPage("about")
        compose.onNodeWithText(s(R.string.settings_diagnostics_export)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(s(R.string.settings_diagnostics_help)).assertExists()
        val file = temp.newFile("diagnostics.json")
        val model = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        graph.context.getSharedPreferences("secrets", 0).edit().putString("updateToken", "secret-xyz-token").commit()
        try {
            val uri = Uri.parse("content://test.documents/diagnostics.json")
            shadowOf(graph.context.contentResolver).registerOutputStream(uri, java.io.FileOutputStream(file))
            compose.runOnUiThread { model.exportDiagnostics(uri) }
            waitUntil { compose.onAllNodesWithText(s(R.string.settings_diagnostics_saved)).fetchSemanticsNodes().isNotEmpty() }
            val text = file.readText()
            assertTrue(text, text.contains("\"linkState\""))
            assertFalse("the update token never leaves the phone", text.contains("secret-xyz-token"))
        } finally { graph.context.getSharedPreferences("secrets", 0).edit().remove("updateToken").commit() }
    }

    @Test fun aFileThatCannotBeOpenedIsReportedAsNotSaved() {
        compose.onNodeWithText("Settings").performClick()
        val model = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        val seen = java.util.Collections.synchronizedList(mutableListOf<String>())
        compose.runOnUiThread {
            graph.scope.launch { model.messages.collect { seen += it } }
            model.exportDiagnostics(Uri.parse("content://nowhere/diagnostics.json")) { null }
        }
        waitUntil { seen.isNotEmpty() }
        assertEquals(listOf(s(R.string.settings_diagnostics_failed, s(R.string.runtime_export_failed))), seen.toList())
    }

    @Test fun diagnosticsRefuseAPickerThatPointsAtPrivateStorage() {
        compose.onNodeWithText("Settings").performClick()
        val model = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        val private = java.io.File(graph.context.filesDir, "victim.json").apply { writeText("keep") }
        val seen = java.util.Collections.synchronizedList(mutableListOf<String>())
        compose.runOnUiThread {
            graph.scope.launch { model.messages.collect { seen += it } }
            model.exportDiagnostics(Uri.fromFile(private))
        }
        waitUntil { seen.isNotEmpty() }
        assertEquals(listOf(s(R.string.settings_diagnostics_failed, s(R.string.export_destination_unsafe))), seen.toList())
        assertEquals("SEC-01: nothing is truncated or written", "keep", private.readText())
    }
}
