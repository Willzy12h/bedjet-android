package io.github.willzy12h.nocturne.core.store

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UpdateCheckerTest {
    @Test
    fun comparesVersionsNumerically() {
        assertTrue(UpdateChecker.isNewer("v1.10.0", "1.9.2"))
        assertTrue(UpdateChecker.isNewer("v0.2", "0.1.9"))
        assertFalse(UpdateChecker.isNewer("v1.0.0", "1.0.0"))
        assertFalse(UpdateChecker.isNewer("1.0.0-beta", "1.0.0"))
        assertFalse(UpdateChecker.isNewer("v0.9", "1.0"))
    }

    @Test
    fun parsesLatestReleaseWithItsApk() {
        val json = """
            {"tag_name":"v0.2.0","name":"Nocturne 0.2.0","html_url":"https://github.com/o/r/releases/tag/v0.2.0","body":"Fixes",
             "assets":[{"name":"notes.txt","browser_download_url":"https://x/notes.txt"},
                       {"name":"nocturne-0.2.0.apk","browser_download_url":"https://x/nocturne-0.2.0.apk"}],
             "other":1}
        """.trimIndent()
        val release = UpdateChecker.parseLatest(json)!!
        assertEquals("v0.2.0", release.tag)
        assertEquals("https://x/nocturne-0.2.0.apk", release.apkUrl)
        assertEquals("https://github.com/o/r/releases/tag/v0.2.0", release.pageUrl)
    }

    @Test
    fun garbageIsNoRelease() {
        assertNull(UpdateChecker.parseLatest("""{"message":"Not Found"}"""))
        assertNull(UpdateChecker.parseLatest("<html>"))
    }

    @Test
    fun latestReleaseUrl() {
        assertEquals("https://api.github.com/repos/Willzy12h/bedjet-android/releases/latest", UpdateChecker.latestUrl("Willzy12h/bedjet-android"))
    }
}
