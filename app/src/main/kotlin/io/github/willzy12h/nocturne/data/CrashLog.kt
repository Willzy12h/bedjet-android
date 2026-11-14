package io.github.willzy12h.nocturne.data

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.ZonedDateTime

/**
 * Keeps the last crash on the phone, so a diagnostics export can show what went wrong. Nothing leaves the phone
 * unless the person exports diagnostics themselves. Only the newest crash is kept, and its trace is capped.
 */
object CrashLog {
    private const val FILE = "last-crash.txt"
    private const val MAX_CHARS = 16_000
    private val installed = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Records an uncaught exception, then hands it on to the handler that was already there. */
    fun install(dir: File, version: String, now: () -> ZonedDateTime = { ZonedDateTime.now() }) {
        // Once per process: tests create the application many times in one process.
        if (!installed.compareAndSet(false, true)) return
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { record(dir, version, thread.name, error, now()) }
            previous?.uncaughtException(thread, error)
        }
    }

    internal fun record(dir: File, version: String, threadName: String, error: Throwable, at: ZonedDateTime) {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val text = "$at\nversion $version\nthread $threadName\n$trace".take(MAX_CHARS)
        val target = File(dir, FILE)
        val temp = File(dir, "$FILE.tmp")
        temp.writeText(text)
        if (!temp.renameTo(target)) { target.writeText(text); temp.delete() }
    }

    /** The last crash, or null when there has been none. */
    fun read(dir: File): String? = runCatching { File(dir, FILE).takeIf { it.isFile }?.readText() }.getOrNull()
}
