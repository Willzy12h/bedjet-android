package io.github.willzy12h.nocturne.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import io.github.willzy12h.nocturne.R

internal fun openSourceCode(context: Context): Boolean {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(context.getString(R.string.settings_source_url)))
    if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, R.string.settings_source_unavailable, Toast.LENGTH_SHORT).show()
        false
    } catch (_: SecurityException) {
        Toast.makeText(context, R.string.settings_source_unavailable, Toast.LENGTH_SHORT).show()
        false
    }
}
