package io.github.willzy12h.nocturne.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.ui.test.*
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.store.BackupCodec
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h891dp-notnight-xhdpi")
class FinalSettingsCleanupTest {
    private val ui = U06Fixture(U06Look.SIMPLE_MIDNIGHT)
    @get:Rule val rules = ui.rules

    private fun settings() = ui.tab("Settings")

    @Test fun updatesOpenTheFixedGitHubReleasesPageInTheBrowser() {
        settings()
        ui.control("Check GitHub for a new APK").performClick()
        val intent = shadowOf(ui.compose.activity).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("https://github.com/Willzy12h/bedjet-android/releases", intent.dataString)
    }

    @Test fun aboutKeepsAttributionVersionAndProjectHelpReadableAtTwoHundredPercent() {
        settings()
        listOf("Nocturne controls BedJet 3 nights over Bluetooth.", "Not affiliated with BedJet LLC",
            "BedJet protocol work by the Lull project (github.com/rkanjani/lull, MIT)").forEach {
            ui.checkLayout(ui.compose.onNodeWithText(it), it)
        }
        ui.checkLayout(ui.compose.onNodeWithText(ui.model.version, substring = true), "Installed version")
        ui.control("Project and help on GitHub").performClick()
        val intent = shadowOf(ui.compose.activity).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("https://github.com/Willzy12h/bedjet-android", intent.dataString)
    }

    @Test fun obsoleteUpdaterAndIphoneHandoverControlsAreAbsent() {
        settings()
        listOf("Lull phone hand-over", "Check weekly", "GitHub token", "Repository").forEach {
            ui.compose.onNodeWithText(it).assertDoesNotExist()
        }
        // Concrete old descriptions guard against label-only removal.
        ui.compose.onNodeWithText("Only when the app is opened. This is the app's only internet use.").assertDoesNotExist()
        ui.compose.onNodeWithText("Uses the BedJet name to request a short hand-over. No shared night state is exchanged. Android may not see background iPhone advertisements. Foreground two-phone acceptance is pending.").assertDoesNotExist()
    }

    @Test fun theAppHasNoNetworkOrBleAdvertisingPermission() {
        @Suppress("DEPRECATION")
        val permissions = ui.compose.activity.packageManager.getPackageInfo(
            ui.compose.activity.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty().toSet()
        assertFalse("The external browser owns network access", Manifest.permission.INTERNET in permissions)
        assertFalse("Removed iPhone handover must not retain advertising access", Manifest.permission.BLUETOOTH_ADVERTISE in permissions)
    }

    @Test fun backupsNoLongerContainUpdaterOrLullSettings() {
        val text = BackupCodec.encode(ui.graph.repository.bundle())
        listOf("updateRepo", "autoUpdateCheck", "lullHandover").forEach {
            assertFalse("Removed setting $it must not be exported", text.contains("\"$it\""))
        }
    }
}
