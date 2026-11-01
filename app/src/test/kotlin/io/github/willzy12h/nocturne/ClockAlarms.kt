package io.github.willzy12h.nocturne

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import org.robolectric.Shadows.shadowOf

/**
 * A show intent that names Google Clock as the alarm's app. Since 0.3.0-acceptance.3 an alarm whose app Android
 * doesn't name is never used, so fixtures that mean "the person's clock alarm" must say which app set it.
 */
fun clockAlarmShow(context: Context, requestCode: Int = 7_700): PendingIntent =
    PendingIntent.getActivity(context, requestCode, Intent("test.clock.alarm.$requestCode"), PendingIntent.FLAG_IMMUTABLE)
        .also { shadowOf(it).setCreatorPackage("com.google.android.deskclock") }
