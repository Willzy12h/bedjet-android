package io.github.willzy12h.nocturne

import android.app.PendingIntent
import org.robolectric.shadows.ShadowAlarmManager

/**
 * Robolectric 4.16.1 deprecates ScheduledAlarm.operation but exposes no replacement getter.
 * Keep this exception at the external API boundary; assertions still inspect the actual scheduled PendingIntent.
 */
internal val ShadowAlarmManager.ScheduledAlarm.operationIntent: PendingIntent?
    @Suppress("DEPRECATION") get() = operation
