package io.github.willzy12h.nocturne.data

import java.io.IOException

/**
 * Thrown instead of reading or recording slot originals while `slot-backups.json` is listed in
 * [Repository.unreadable]. Recording now would save the app's own program as the "original" (IR-03), so no slot may be
 * written until a snapshot is restored or the loss is accepted with [Repository.dismissUnreadable].
 */
class SlotBackupsUnreadable : IOException("Saved slot backups couldn't be read; restore a snapshot or dismiss the warning first.")
