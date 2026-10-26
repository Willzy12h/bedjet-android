package io.github.willzy12h.nocturne.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.willzy12h.nocturne.App
import io.github.willzy12h.nocturne.GraphLifecycle
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.link.DeviceId
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.protocol.ProgramStep
import io.github.willzy12h.nocturne.core.protocol.StoredProgram
import io.github.willzy12h.nocturne.core.store.BackupCodec
import io.github.willzy12h.nocturne.core.store.Migrations
import io.github.willzy12h.nocturne.core.store.SlotBackup
import io.github.willzy12h.nocturne.core.store.ThemeMode
import io.github.willzy12h.nocturne.graph
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** IR-31: the saved original's uncertainty remains visible wherever Settings presents it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class UncertainOriginalsSettingsTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val graphs = GraphLifecycle()
    private val fixture = TestRule { base, _ -> object : Statement() {
        override fun evaluate() {
            val graph = (RuntimeEnvironment.getApplication() as App).graph
            graph.clock = Clock.fixed(Instant.parse("2026-10-24T22:30:00Z"), ZoneOffset.UTC)
            runBlocking {
                graph.repository.updateSettings { it.copy(demoMode = true, themeMode = ThemeMode.DARK) }
            }
            base.evaluate()
        }
    } }
    @get:Rule val rules: TestRule = RuleChain.outerRule(graphs).around(fixture).around(compose)
    private val graph get() = compose.activity.graph
    private fun s(id: Int) = compose.activity.getString(id)

    private fun showOriginal(deviceId: String, uncertain: Boolean) {
        val original = SlotBackup(
            deviceId,
            0,
            StoredProgram("Saved original", listOf(ProgramStep(Mode.HEAT, 5, 70, 0, 1))),
            graph.now().toString(),
            uncertain = uncertain,
        )
        compose.runOnUiThread { runBlocking {
            val repo = graph.repository
            repo.restore(repo.importFrom(BackupCodec.encode(repo.bundle().copy(slotBackups = listOf(original))).byteInputStream()))
        } }
        compose.waitForIdle()
        compose.onNodeWithText("Settings").performClick()
        compose.onNode(hasText("Saved original", substring = true)).performScrollTo().assertIsDisplayed()
    }

    private fun previewOriginal() {
        compose.onNodeWithText(s(R.string.settings_preview_put_back)).performScrollTo().performClick()
        compose.onNode(hasText(s(R.string.settings_put_original_back)) and hasAnyAncestor(isDialog())).assertIsDisplayed()
    }

    @Test fun matchingUncertainOriginalDisplaysItsWarning() {
        showOriginal(DeviceId.DEMO, uncertain = true)

        compose.onNodeWithText(UNCERTAINTY_WARNING).performScrollTo().assertIsDisplayed()
    }

    @Test fun matchingCertainOriginalDoesNotDisplayAWarning() {
        showOriginal(DeviceId.DEMO, uncertain = false)

        compose.onNodeWithText(UNCERTAINTY_WARNING).assertDoesNotExist()
    }

    @Test fun fromBackupUncertainOriginalDisplaysItsWarning() {
        showOriginal(Migrations.UNKNOWN_DEVICE, uncertain = true)

        compose.onNodeWithText("From backup").assertExists()
        compose.onNodeWithText(UNCERTAINTY_WARNING).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(s(R.string.settings_preview_put_back)).assertDoesNotExist()
    }

    @Test fun fromBackupCertainOriginalDoesNotDisplayAWarning() {
        showOriginal(Migrations.UNKNOWN_DEVICE, uncertain = false)

        compose.onNodeWithText("From backup").assertExists()
        compose.onNodeWithText(UNCERTAINTY_WARNING).assertDoesNotExist()
        compose.onNodeWithText(s(R.string.settings_preview_put_back)).assertDoesNotExist()
    }

    @Test fun uncertainOriginalRestoreDialogDisplaysItsWarning() {
        showOriginal(DeviceId.DEMO, uncertain = true)
        previewOriginal()

        compose.onNode(hasText(UNCERTAINTY_WARNING) and hasAnyAncestor(isDialog())).assertIsDisplayed()
    }

    @Test fun certainOriginalRestoreDialogDoesNotDisplayAWarning() {
        showOriginal(DeviceId.DEMO, uncertain = false)
        previewOriginal()

        compose.onNode(hasText(UNCERTAINTY_WARNING) and hasAnyAncestor(isDialog())).assertDoesNotExist()
    }

    companion object {
        private const val UNCERTAINTY_WARNING = "May not be the true original: the slot may already have held one of Nocturne's programmes."
    }
}
