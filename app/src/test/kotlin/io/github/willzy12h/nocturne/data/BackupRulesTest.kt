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

/** The update token's preferences stay on the phone: no cloud backup, no copy to a new phone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupRulesTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    /** Each `<exclude>` as "parent/domain/path". */
    private fun excludes(xml: Int): List<String> {
        val parser = context.resources.getXml(xml)
        val parents = ArrayDeque<String>()
        val found = mutableListOf<String>()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "exclude") {
                        found += "${parents.lastOrNull()}/${parser.getAttributeValue(null, "domain")}/${parser.getAttributeValue(null, "path")}"
                    }
                    parents.addLast(parser.name)
                }
                XmlPullParser.END_TAG -> parents.removeLast()
            }
        }
        return found
    }

    @Test
    fun secretsAreKeptOutOfCloudBackupAndDeviceTransfer() {
        val rules = excludes(R.xml.data_extraction_rules)
        assertTrue(rules.toString(), "cloud-backup/sharedpref/secrets.xml" in rules)
        assertTrue(rules.toString(), "device-transfer/sharedpref/secrets.xml" in rules)
    }

    @Test
    fun secretsAreKeptOutOfOlderAndroidBackups() {
        assertTrue("full-backup-content/sharedpref/secrets.xml" in excludes(R.xml.backup_rules))
    }

    @Test
    fun theAppUsesTheRules() {
        // Robolectric doesn't load these two attributes into ApplicationInfo, so read the manifest itself.
        val application = File("src/main/AndroidManifest.xml").readText().substringAfter("<application").substringBefore(">")
        assertTrue(application.contains("""android:dataExtractionRules="@xml/data_extraction_rules""""))
        assertTrue(application.contains("""android:fullBackupContent="@xml/backup_rules""""))
    }
}
