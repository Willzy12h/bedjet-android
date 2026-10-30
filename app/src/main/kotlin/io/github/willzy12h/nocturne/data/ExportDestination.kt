package io.github.willzy12h.nocturne.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import io.github.willzy12h.nocturne.R
import java.io.IOException
import java.io.OutputStream

/** A save location returned by a picker that Nocturne must not write to with its own permissions. */
class UnsafeExportDestination(message: String) : IOException(message)

/**
 * The one way an export opens the file a picker returned. A picker is another app, so its answer is untrusted: a
 * `file://` URI or one of Nocturne's own providers would let it point the write at the app's private data. Only a
 * `content://` document from another app's provider is opened, and the check runs before anything is opened or
 * truncated. Other providers are not otherwise restricted: they only expose what their own app allows.
 */
object ExportDestination {
    fun check(context: Context, uri: Uri) {
        val authority = uri.authority.orEmpty()
        val own = context.packageName
        val ownProvider = authority == own || authority.startsWith("$own.") ||
            runCatching { context.packageManager.resolveContentProvider(authority, 0)?.packageName == own }.getOrDefault(false)
        if (uri.scheme != ContentResolver.SCHEME_CONTENT || authority.isEmpty() || ownProvider)
            throw UnsafeExportDestination(context.getString(R.string.export_destination_unsafe))
    }

    /** Checks [uri], then opens it for a fresh write ("wt"). Throws rather than returning null, so nothing claims it saved. */
    fun open(context: Context, uri: Uri, open: (Uri) -> OutputStream? = { context.contentResolver.openOutputStream(it, "wt") }): OutputStream {
        check(context, uri)
        return open(uri) ?: throw IOException(context.getString(R.string.runtime_export_open_failed))
    }
}
