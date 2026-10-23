package io.github.willzy12h.nocturne.ui

import io.github.willzy12h.nocturne.data.RestoreUnfinished
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

/** A restore that is kept but not yet finished says so in its own words, not as a failed save. */
class SnapshotRestoreMessageTest {
    @Test fun aRestoreThatIsKeptButUnfinishedShowsItsOwnMessage() = runBlocking {
        val unfinished = RestoreUnfinished(IOException("Disk full"))
        assertEquals(unfinished.message, snapshotRestoreMessage { throw unfinished })
        assertEquals("Restored. What you had before is saved as a snapshot.", snapshotRestoreMessage { true })
        assertEquals("That snapshot couldn't be read.", snapshotRestoreMessage { false })
        assertThrows(IOException::class.java) { runBlocking { snapshotRestoreMessage { throw IOException("Disk full") } } }
        Unit
    }
}
