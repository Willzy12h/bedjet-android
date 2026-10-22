package io.github.willzy12h.nocturne.night

import android.app.AlarmManager
import android.content.Context
import io.github.willzy12h.nocturne.core.store.Settings
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/** The next alarm set in the phone's clock app. Android only exposes the next one, not a list. */
object AlarmSource {
    data class NextAlarm(val time: ZonedDateTime, val creator: String?, val counts: Boolean)

    fun describe(context: Context, settings: Settings): NextAlarm? {
        val info = context.getSystemService(AlarmManager::class.java)?.nextAlarmClock ?: return null
        val creator = info.showIntent?.creatorPackage
        val counts = io.github.willzy12h.nocturne.core.plan.AlarmPolicy(settings).acceptsSource(creator)
        return NextAlarm(Instant.ofEpochMilli(info.triggerTime).atZone(ZoneId.systemDefault()), creator, counts)
    }

    fun next(context: Context, settings: Settings): ZonedDateTime? = describe(context, settings)?.takeIf { it.counts }?.time
}
