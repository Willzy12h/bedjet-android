package io.github.willzy12h.nocturne.data

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File

/** SEC-01: a picker's answer is checked before anything is opened or truncated. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExportDestinationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun rejected(uri: Uri) {
        var opened = false
        val error = runCatching { ExportDestination.open(context, uri) { opened = true; ByteArrayOutputStream() } }.exceptionOrNull()
        assertTrue("$uri must be refused, got $error", error is UnsafeExportDestination)
        assertFalse("$uri must not be opened", opened)
    }

    @Test fun aFileUriIntoPrivateStorageIsRefusedBeforeOpening() {
        val private = File(context.filesDir, "settings.json").apply { writeText("keep me") }
        rejected(Uri.fromFile(private))
        assertEquals("keep me", private.readText())
    }

    @Test fun nocturnesOwnProvidersAreRefused() {
        rejected(Uri.parse("content://${context.packageName}.androidx-startup/x"))
        rejected(Uri.parse("content://${context.packageName}/files/settings.json"))
    }

    @Test fun otherSchemesAndMissingAuthoritiesAreRefused() {
        rejected(Uri.parse("android.resource://${context.packageName}/raw/x"))
        rejected(Uri.parse("content:///settings.json"))
        rejected(Uri.parse("/data/data/${context.packageName}/files/settings.json"))
    }

    @Test fun aNormalDocumentProviderStillSaves() {
        val out = ByteArrayOutputStream()
        val uri = Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload%2Fnocturne.json")
        ExportDestination.open(context, uri) { out }.use { it.write("ok".toByteArray()) }
        assertEquals("ok", out.toString())
    }
}
