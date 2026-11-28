package io.github.willzy12h.nocturne.platform

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.*
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.graph
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.time.Instant
import java.time.ZonedDateTime

object BedtimeReminders {
    private const val REQUEST=6103
    private fun pending(graph: Graph,intent: Intent)=PendingIntent.getBroadcast(graph.context,REQUEST,intent,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    fun sync(graph: Graph) {
        val alarms=graph.context.getSystemService(AlarmManager::class.java)
        val intent=Intent(graph.context,BedtimeReminderReceiver::class.java)
        val settings=graph.repository.settings.value;val now=graph.now()
        alarms.cancel(pending(graph,intent))
        if (!settings.platform.bedtimeReminder || settings.platform.validationErrors().isNotEmpty()) return
        val profiles = graph.repository.profiles.value
        val leadMinutes = settings.platform.reminderMinutes.toLong()
        val firstDate = now.plusMinutes(leadMinutes).toLocalDate()
        // A weekday may be the sole assignment, with a reminder due on the preceding calendar day.
        val next=(0L..7L).mapNotNull { offset ->
            val date=firstDate.plusDays(offset)
            val profile=settings.resolveProfile(profiles,date.atTime(12,0).atZone(now.zone)) ?: return@mapNotNull null
            val bedtime=date.atStartOfDay().plusMinutes(profile.bedtimeMinute.toLong()).atZone(now.zone)
            val due=bedtime.minusMinutes(leadMinutes)
            if (due.isAfter(now)) Triple(profile,bedtime,due) else null
        }.minByOrNull {it.third.toInstant()} ?: return
        intent.putExtra("profile",next.first.id).putExtra("bedtime",next.second.toString())
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,next.third.toInstant().toEpochMilli(),pending(graph,intent))
    }
    suspend fun deliver(graph: Graph,profileId: String,bedtime: ZonedDateTime): Boolean {
        val repo=graph.repository;val settings=repo.settings.value;val profile=repo.profile(profileId) ?: return false
        val now=graph.now();val due=bedtime.minusMinutes(settings.platform.reminderMinutes.toLong())
        if (!settings.platform.bedtimeReminder || settings.platform.validationErrors().isNotEmpty() ||
            now.isBefore(due) || !now.isBefore(bedtime.plusMinutes(120)) ||
            profile.bedtimeMinute != bedtime.hour*60+bedtime.minute ||
            settings.resolveProfile(repo.profiles.value,bedtime)?.id != profileId || !PlatformNotifications.available(graph.context)) return false
        if (!repo.claimAutomation("reminder:${bedtime.toLocalDate()}")) return false
        val preview=Intent(graph.context,PlatformActionActivity::class.java).putExtra("profile",profileId)
        val builder=NotificationCompat.Builder(graph.context,PlatformNotifications.CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(graph.context.getString(R.string.feature_reminder_title,profile.name))
            .setContentText(graph.context.getString(R.string.feature_reminder_preview)).setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(graph.context,REQUEST,preview,PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        // "Ask each night": an alarm Android didn't name can be used for tonight from here, with one tap.
        UnnamedAlarmOffer.current(graph,profileId)?.let { offer ->
            val use=Intent(graph.context,UnnamedAlarmReceiver::class.java).putExtra("profile",profileId).putExtra("wake",offer.toString())
            builder.addAction(0,graph.context.getString(R.string.missing_alarm_use_unnamed,io.github.willzy12h.nocturne.ui.Format.clock(offer)),
                PendingIntent.getBroadcast(graph.context,REQUEST,use,PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        }
        return try {
            NotificationManagerCompat.from(graph.context).notify(PlatformNotifications.REMINDER_ID,builder.build())
            true
        } catch (_: SecurityException) { false } // Permission can be revoked after the eligibility check.
    }
}
class BedtimeReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context,intent: Intent) {
        val pending=goAsync()
        context.graph.scope.launch {
            try { withTimeout(8_000) {
                val bedtime=intent.getStringExtra("bedtime")?.let { runCatching {ZonedDateTime.parse(it)}.getOrNull() }
                val profile=intent.getStringExtra("profile")
                if (bedtime != null && profile != null) BedtimeReminders.deliver(context.graph,profile,bedtime)
                BedtimeReminders.sync(context.graph)
            } } finally {pending.finish()}
        }
    }
}

/** The unnamed alarm "Ask each night" would offer now, before tonight's night starts; null when there's none to offer. */
object UnnamedAlarmOffer {
    fun current(graph: Graph,profileId: String): ZonedDateTime? {
        val repo=graph.repository
        if (repo.activeNight.value != null) return null
        val profile=repo.profile(profileId) ?: return null
        val settings=repo.settings.value
        val alarm=io.github.willzy12h.nocturne.night.AlarmSource.describe(graph.context,settings)
        return io.github.willzy12h.nocturne.night.AlarmExplanation.resolve(settings,profile,graph.now(),alarm).unnamedOffer
    }
}

/** The reminder's "Use the 06:30 alarm tonight": checked again at the tap, then saved as tonight's wake time. */
class UnnamedAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context,intent: Intent) {
        val pending=goAsync()
        context.graph.scope.launch {
            try { withTimeout(8_000) {
                val graph=context.graph
                val profile=intent.getStringExtra("profile")
                val wake=intent.getStringExtra("wake")?.let { runCatching {ZonedDateTime.parse(it)}.getOrNull() }
                // Only the same alarm, still offered: the mode, the alarm or a started night may have changed since.
                if (profile != null && wake != null && UnnamedAlarmOffer.current(graph,profile)?.toInstant() == wake.toInstant())
                    graph.actions.setWakeOverride(wake,null)
                NotificationManagerCompat.from(context).cancel(PlatformNotifications.REMINDER_ID)
            } } finally {pending.finish()}
        }
    }
}
