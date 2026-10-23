package io.github.willzy12h.nocturne.data

import java.io.IOException

/**
 * A restore failed part-way and some files couldn't be put back. What the app shows matches what is on disk, and
 * the next start puts back what was there before the restore.
 */
class RestoreInterrupted(cause: Throwable) :
    IOException("The restore didn't finish and couldn't be undone yet. It will be undone when the app next starts.", cause)
