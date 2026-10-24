package io.github.willzy12h.nocturne.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.graph
import io.github.willzy12h.nocturne.core.night.ActiveNight
import io.github.willzy12h.nocturne.core.night.Confidence
import io.github.willzy12h.nocturne.core.night.NightIdentity
import io.github.willzy12h.nocturne.core.night.ResumeOffer
import io.github.willzy12h.nocturne.core.store.LoggedNotice
import io.github.willzy12h.nocturne.core.store.NoticeKind
import io.github.willzy12h.nocturne.core.store.Settings
import io.github.willzy12h.nocturne.core.store.WakeAction
import io.github.willzy12h.nocturne.night.NightActions
import io.github.willzy12h.nocturne.service.BedJetService
import io.github.willzy12h.nocturne.ui.MainActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import java.time.Duration
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
    private const val INFO_ID = 5

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
                NotificationChannel(CHANNEL_ALERTS, "Changes, problems and updates", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "When your wake-up moves with your alarm, when something didn't work, and new versions."
                },
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
    fun showWakeUp(context: Context, night: ActiveNight, settings: Settings, status: String? = null, logNotice: Boolean = true) {
        val wake = ZonedDateTime.parse(night.wakeAt).format(DateTimeFormatter.ofPattern("HH:mm"))
        // Only a kept, confirmed manual run has a captured end; an uncertain adjustment can retain an old timestamp.
        val title = when {
            night.hasPlannedManualEnd -> context.getString(R.string.feature_manual_until,
                ZonedDateTime.parse(night.endsAt).format(DateTimeFormatter.ofPattern("HH:mm")))
            night.trackingManual -> context.getString(R.string.runtime_manual_unconfirmed)
            else -> "Waking you at $wake"
        }
        if (logNotice) log(context, NoticeKind.WAKE_UP, title, status ?: "Your BedJet is getting you ready to wake up.")
        if (!canPost(context)) return
        val builder = Notification.Builder(context, CHANNEL_WAKE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
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

    fun showQuiet(context: Context, night: ActiveNight, settings: Settings, wakePending: Boolean = false) {
        if (!settings.quietNightNotification || night.trackingManual || night.confidence != io.github.willzy12h.nocturne.core.night.Confidence.CONFIRMED) { cancelQuiet(context); return }
        if (!canPost(context)) return
        val wake = ZonedDateTime.parse(night.wakeAt).format(DateTimeFormatter.ofPattern("HH:mm"))
        // While a wake-up change is pending, say so, as Tonight does, instead of a plain "Waking at".
        val notification = Notification.Builder(context, CHANNEL_WORKING).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(if (wakePending) R.string.quiet_pending else R.string.runtime_quiet, wake)).setContentIntent(openApp(context)).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, context.getString(R.string.runtime_end), BedJetService.pendingIntent(context, NightActions.Command.END_NIGHT)).build())
            .build()
        context.getSystemService(NotificationManager::class.java).notify(6, notification)
    }
    fun cancelQuiet(context: Context) = context.getSystemService(NotificationManager::class.java).cancel(6)
    fun showResume(context: Context) {
        if (!canPost(context)) return
        val captured = context.graph.actions.capture(NightActions.Command.RESUME, requireIdentity = true)
        val confirm = PendingIntent.getActivity(context, ("resume:${captured.expected}").hashCode(),
            io.github.willzy12h.nocturne.ui.ShortcutActivity.confirmationIntent(context, captured),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(context, CHANNEL_ALERTS).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.runtime_resume_title)).setContentIntent(confirm).setAutoCancel(true)
            .addAction(Notification.Action.Builder(null, context.getString(R.string.runtime_resume_title), confirm).build())
            .build()
        context.getSystemService(NotificationManager::class.java).notify(7, notification)
    }

    fun cancelWakeUp(context: Context) = context.getSystemService(NotificationManager::class.java).cancel(WAKE_ID)
    fun cancelResume(context: Context) = context.getSystemService(NotificationManager::class.java).cancel(7)

    /** Withdraw actions whose captured night or resume offer is no longer current. Posting remains at its event. */
    fun reconcileWakeResume(context: Context, night: ActiveNight?, resumeOffer: ResumeOffer?, wakeShown: NightIdentity?,
        manualWakeShown: Boolean = false) {
        val now = context.graph.now()
        val wakeCurrent = night != null && wakeShown == night.identity && now.isBefore(ZonedDateTime.parse(night.endsAt)) &&
            if (manualWakeShown) night.trackingManual && night.confidence in setOf(Confidence.UNKNOWN, Confidence.CONFIRMED)
            else night.confidence == Confidence.CONFIRMED && !night.trackingManual &&
                !now.isBefore(ZonedDateTime.parse(night.wakeUpStart))
        if (!wakeCurrent) cancelWakeUp(context)
        val offerCurrent = resumeOffer != null && now.isBefore(ZonedDateTime.parse(resumeOffer.expiresAt)) &&
            now.isBefore(ZonedDateTime.parse(resumeOffer.night.wakeAt))
        if (!offerCurrent) cancelResume(context)
    }

    /** A clock tick also withdraws notices when no repository state changes at their expiry. */
    internal suspend fun observeWakeResume(context: Context, ticks: Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            val remaining = context.graph.repository.runtime.value.resumeOffer?.let {
                Duration.between(context.graph.now(), ZonedDateTime.parse(it.expiresAt)).toMillis()
            }
            delay(if (remaining != null && remaining > 0) remaining.coerceAtMost(60_000) else 60_000)
        }
    }) {
        val repo = context.graph.repository
        combine(repo.activeNight, repo.runtime, ticks) { _, _, _ -> Unit }.collect {
            // Separate file publications can be conflated; decide from the latest pair at reconciliation time.
            val runtime = repo.runtime.value
            reconcileWakeResume(context, repo.activeNight.value, runtime.resumeOffer, runtime.wakeShown, runtime.manualWakeShown)
        }
    }

    fun showError(context: Context, title: String, text: String) = showAlert(context, NoticeKind.PROBLEM, title, text, ERROR_ID)

    /** Something changed without the person doing it, e.g. the wake-up followed a new alarm. */
    fun showInfo(context: Context, title: String, text: String) = showAlert(context, NoticeKind.INFO, title, text, INFO_ID)

    private fun showAlert(context: Context, kind: NoticeKind, title: String, text: String, id: Int) {
        log(context, kind, title, text)
        if (!canPost(context)) return
        val notification = Notification.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(id, notification)
    }

    /** Opens the release page: the APK link itself needs signing in when the repository is private. */
    fun showUpdate(context: Context, tag: String, pageUrl: String) {
        log(context, NoticeKind.UPDATE, "Nocturne $tag is available", pageUrl)
        if (!canPost(context)) return
        val open = PendingIntent.getActivity(context, 0, Intent(Intent.ACTION_VIEW, Uri.parse(pageUrl)), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Nocturne $tag is available")
            .setContentText("Tap to open the release and download it.")
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

    /** Kept even when notifications are off, so Settings can show what would have been said. */
    private fun log(context: Context, kind: NoticeKind, title: String, text: String) {
        val graph = context.graph
        val notice = LoggedNotice(graph.now().toString(), kind, title, text)
        graph.scope.launch { runCatching { graph.repository.addNotice(notice) } }
    }

    private fun openApp(context: Context) = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
    )

    /** Covers Android 12, which has no notification permission, and a person turning notifications off. */
    fun canPost(context: Context) = context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
}
