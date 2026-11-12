package io.github.willzy12h.nocturne.night

import android.content.Context
import androidx.core.content.edit

/**
 * The apps Android has named as setting the phone's next alarm, remembered on this phone only so the alarm-app list
 * can offer them later. Android never lists which apps have alarms set; it only names the app behind the next one.
 * Kept out of [io.github.willzy12h.nocturne.core.store.Settings], so backups and their schema are unchanged.
 */
object SeenAlarmApps {
    private const val PREFS = "seen_alarm_apps"
    private const val KEY = "packages"
    /** Enough for every alarm app a person plausibly uses; the oldest are forgotten first. */
    internal const val LIMIT = 24

    fun all(context: Context): List<String> = runCatching {
        prefs(context).getString(KEY, null)?.split('\n')?.filter { it.isNotBlank() }.orEmpty()
    }.getOrDefault(emptyList())

    /** Remembers [creator], newest first; nothing for an unnamed alarm, Android itself or Nocturne. */
    fun record(context: Context, creator: String?) {
        if (creator.isNullOrBlank() || creator == "android" || creator == context.packageName) return
        val current = all(context)
        if (current.firstOrNull() == creator) return
        val updated = remember(current, creator)
        runCatching { prefs(context).edit { putString(KEY, updated.joinToString("\n")) } }
    }

    internal fun remember(current: List<String>, creator: String): List<String> =
        (listOf(creator) + current.filter { it != creator }).take(LIMIT)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
