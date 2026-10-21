package io.github.willzy12h.nocturne.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.night.ActiveNight
import io.github.willzy12h.nocturne.core.store.Settings
import io.github.willzy12h.nocturne.core.store.WakeAction
import io.github.willzy12h.nocturne.night.NightActions
import io.github.willzy12h.nocturne.service.BedJetService
import io.github.willzy12h.nocturne.ui.MainActivity
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

object Notifications {
    private const val CHANNEL_WAKE = "wake"
    private const val CHANNEL_WORKING = "working"
    private const val CHANNEL_ALERTS = "alerts"
    const val WORKING_ID = 1
    private const val WAKE_ID = 2
    private const val ERROR_ID = 3
    private const val UPDATE_ID = 4

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_WAKE, "Wake-up", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Shown while your BedJet is waking you, with snooze and extend buttons."
                    setSound(null, null)
                },
                NotificationChannel(CHANNEL_WORKING, "Talking to BedJet", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown briefly while a command is sent."
                },
                NotificationChannel(CHANNEL_ALERTS, "Problems and updates", NotificationManager.IMPORTANCE_DEFAULT),
            ),
        )
    }

    fun working(context: Context): Notification =
        Notification.Builder(context, CHANNEL_WORKING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Talking to your BedJet…")
            .setOngoing(true)
            .build()

    /** The wake-up notification. It stays until the night ends or Off is pressed. */
    fun showWakeUp(context: Context, night: ActiveNight, settings: Settings, status: String? = null) {
        if (!canPost(context)) return
        val wake = ZonedDateTime.parse(night.wakeAt).format(DateTimeFormatter.ofPattern("HH:mm"))
        val builder = Notification.Builder(context, CHANNEL_WAKE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Waking you at $wake")
            .setContentText(status ?: "Your BedJet is getting you ready to wake up.")
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(openApp(context))
        settings.notificationActions.take(3).forEach { action ->
            builder.addAction(Notification.Action.Builder(null, title(action, settings), BedJetService.pendingIntent(context, command(action))).build())
        }
        context.getSystemService(NotificationManager::class.java).notify(WAKE_ID, builder.build())
    }

    fun cancelWakeUp(context: Context) = context.getSystemService(NotificationManager::class.java).cancel(WAKE_ID)

    fun showError(context: Context, title: String, text: String) {
        if (!canPost(context)) return
        val notification = Notification.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(ERROR_ID, notification)
    }

    fun showUpdate(context: Context, tag: String, url: String) {
        if (!canPost(context)) return
        val open = PendingIntent.getActivity(context, 0, Intent(Intent.ACTION_VIEW, Uri.parse(url)), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Nocturne $tag is available")
            .setContentText("Tap to download the new APK.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(UPDATE_ID, notification)
    }

    fun title(action: WakeAction, settings: Settings): String = when (action) {
        WakeAction.SNOOZE_WARM -> "Snooze warm ${settings.snoozeWarm.minutes}m"
        WakeAction.SNOOZE_COOL -> "Snooze cool ${settings.snoozeCool.minutes}m"
        WakeAction.EXTEND_WARM -> "Warm ${settings.extendWarm.minutes}m"
        WakeAction.EXTEND_COOL -> "Cool ${settings.extendCool.minutes}m"
        WakeAction.OFF -> "Off"
    }

    private fun command(action: WakeAction) = when (action) {
        WakeAction.SNOOZE_WARM -> NightActions.Command.SNOOZE_WARM
        WakeAction.SNOOZE_COOL -> NightActions.Command.SNOOZE_COOL
        WakeAction.EXTEND_WARM -> NightActions.Command.EXTEND_WARM
        WakeAction.EXTEND_COOL -> NightActions.Command.EXTEND_COOL
        WakeAction.OFF -> NightActions.Command.OFF
    }

    private fun openApp(context: Context) = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
    )

    /** Covers Android 12, which has no notification permission, and a person turning notifications off. */
    fun canPost(context: Context) = context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
}
