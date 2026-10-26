package io.github.willzy12h.nocturne.core.store

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NotificationLogTest {
    private fun notice(i: Int) = LoggedNotice(at = "2026-10-21T22:%02d+01:00".format(i % 60), kind = NoticeKind.INFO, title = "n$i", text = "")

    @Test
    fun newestFirstAndCapped() {
        var log = emptyList<LoggedNotice>()
        repeat(NotificationLog.KEEP + 5) { log = NotificationLog.add(log, notice(it)) }
        assertEquals(NotificationLog.KEEP, log.size)
        assertEquals("n${NotificationLog.KEEP + 4}", log.first().title)
        assertEquals("n5", log.last().title)
    }
}
