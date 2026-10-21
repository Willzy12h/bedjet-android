package io.github.willzy12h.nocturne.core.store

import kotlinx.serialization.Serializable

@Serializable
enum class NoticeKind { INFO, WAKE_UP, PROBLEM, UPDATE }

/** A notification the app showed, kept so it can be looked back on in Settings. `at` is an ISO zoned date-time. */
@Serializable
data class LoggedNotice(val at: String, val kind: NoticeKind, val title: String, val text: String)

object NotificationLog {
    const val KEEP = 200

    /** Newest first, keeping the latest [KEEP]. */
    fun add(log: List<LoggedNotice>, notice: LoggedNotice): List<LoggedNotice> = (listOf(notice) + log).take(KEEP)
}
