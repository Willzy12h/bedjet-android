package io.github.willzy12h.nocturne.data

import android.content.Context
import io.github.willzy12h.nocturne.R
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser
import java.io.File

/** Explicit file allowlists exclude the update-token preferences and device-local state. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupRulesTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    /** Each matching rule as "parent/domain/path". */
    private fun entries(xml: Int, tag: String): List<String> {
        val parser = context.resources.getXml(xml)
        val parents = ArrayDeque<String>()
        val found = mutableListOf<String>()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == tag) {
                        found += "${parents.lastOrNull()}/${parser.getAttributeValue(null, "domain")}/${parser.getAttributeValue(null, "path")}"
                    }
                    parents.addLast(parser.name)
                }
                XmlPullParser.END_TAG -> parents.removeLast()
            }
        }
        return found
    }

    /** The originals travel with their barrier and with the record of an accepted loss, which marks later ones uncertain. */
    @Test fun onlyPortableFilesAndTheOriginalsBarrierAreTransferred() {
        val expected = setOf(
            "file/profiles.json", "file/history.json",
            "file/slot-backups.json", "file/slot-backups-integrity.json", "file/slot-backups-loss-accepted.json",
        )
        for (mode in listOf("cloud-backup", "device-transfer")) {
            val actual = entries(R.xml.data_extraction_rules, "include")
                .filter { it.startsWith("$mode/") }.map { it.removePrefix("$mode/") }.toSet()
            assertTrue("$mode: $actual", actual == expected)
        }
        assertTrue(entries(R.xml.backup_rules, "include").map { it.removePrefix("full-backup-content/") }.toSet() == expected)
    }

    @Test
    fun theAppUsesTheRules() {
        // Robolectric doesn't load these two attributes into ApplicationInfo, so read the manifest itself.
        val application = File("src/main/AndroidManifest.xml").readText().substringAfter("<application").substringBefore(">")
        assertTrue(application.contains("""android:dataExtractionRules="@xml/data_extraction_rules""""))
        assertTrue(application.contains("""android:fullBackupContent="@xml/backup_rules""""))
    }
}
