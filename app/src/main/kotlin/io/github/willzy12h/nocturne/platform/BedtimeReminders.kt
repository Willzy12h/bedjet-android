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
        val next=(0L..2L).mapNotNull { offset ->
            val date=now.toLocalDate().plusDays(offset)
            val profile=settings.resolveProfile(graph.repository.profiles.value,date.atTime(12,0).atZone(now.zone)) ?: return@mapNotNull null
            val bedtime=date.atStartOfDay().plusMinutes(profile.bedtimeMinute.toLong()).atZone(now.zone)
            val due=bedtime.minusMinutes(settings.platform.reminderMinutes.toLong())
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
        return try {
            NotificationManagerCompat.from(graph.context).notify(PlatformNotifications.REMINDER_ID,
            NotificationCompat.Builder(graph.context,PlatformNotifications.CHANNEL).setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(graph.context.getString(R.string.feature_reminder_title,profile.name))
                .setContentText(graph.context.getString(R.string.feature_reminder_preview)).setAutoCancel(true)
                .setContentIntent(PendingIntent.getActivity(graph.context,REQUEST,preview,PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build())
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
