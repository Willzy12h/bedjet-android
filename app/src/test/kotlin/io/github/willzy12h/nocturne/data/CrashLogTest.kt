package io.github.willzy12h.nocturne.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.ZonedDateTime

class CrashLogTest {
    @get:Rule val folder = TemporaryFolder()
    private val dir get() = folder.root

    @Test
    fun noCrashReadsAsNothing() {
        assertNull(CrashLog.read(dir))
    }

    @Test
    fun keepsOnlyTheNewestCrashWithItsTimeVersionAndTrace() {
        val at = ZonedDateTime.parse("2026-11-14T03:12:00Z")
        CrashLog.record(dir, "1.1.0-beta.4", "main", IllegalStateException("older-crash-marker"), at)
        CrashLog.record(dir, "1.1.0-beta.4", "main", IllegalArgumentException("second"), at.plusMinutes(1))
        val text = CrashLog.read(dir)!!
        assertTrue(text, text.startsWith("2026-11-14T03:13Z\nversion 1.1.0-beta.4\nthread main\n"))
        assertTrue(text, "IllegalArgumentException: second" in text)
        assertTrue(text, "older-crash-marker" !in text)
        assertEquals(listOf("last-crash.txt"), dir.list()!!.toList())
    }

    @Test
    fun aVeryLongTraceIsCapped() {
        val deep = generateSequence(RuntimeException("root") as Throwable) { RuntimeException("x".repeat(500), it) }.take(100).last()
        CrashLog.record(dir, "v", "t", deep, ZonedDateTime.parse("2026-11-14T03:12:00Z"))
        assertEquals(16_000, CrashLog.read(dir)!!.length)
    }
}
