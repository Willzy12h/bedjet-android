package io.github.willzy12h.nocturne.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.edit
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
import io.github.willzy12h.nocturne.night.CommandOrigin
import io.github.willzy12h.nocturne.service.BedJetService
import io.github.willzy12h.nocturne.ui.MainActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
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
    private const val INFO_ID = 5
    private const val BLUETOOTH_OFF_ID = 8
    /** Repeated background taps or alarm deliveries share one notice during this window. */
    internal const val BLUETOOTH_NOTICE_WINDOW_MS = 60_000L

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_WAKE, context.getString(R.string.notify_channel_wake), NotificationManager.IMPORTANCE_HIGH).apply {
                    description = context.getString(R.string.notify_channel_wake_help)
                    setSound(null, null)
                },
                NotificationChannel(CHANNEL_WORKING, context.getString(R.string.notify_channel_working), NotificationManager.IMPORTANCE_LOW).apply {
                    description = context.getString(R.string.notify_channel_working_help)
                },
                NotificationChannel(CHANNEL_ALERTS, context.getString(R.string.notify_channel_alerts), NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = context.getString(R.string.notify_channel_alerts_help)
                },
            ),
        )
    }

    fun working(context: Context): Notification =
        Notification.Builder(context, CHANNEL_WORKING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notify_working))
            .setOngoing(true)
            .build()

    /** The wake-up notification. It stays until the night ends or Off is pressed. */
    fun showWakeUp(context: Context, night: ActiveNight, settings: Settings, status: String? = null, logNotice: Boolean = true) {
        val wake = ZonedDateTime.parse(night.wakeAt).format(DateTimeFormatter.ofPattern("HH:mm"))
        // Only a kept, confirmed manual run has a captured end; an uncertain adjustment can retain an old timestamp.
        val ordinaryTitle = when {
            night.hasPlannedManualEnd -> context.getString(R.string.feature_manual_until,
                ZonedDateTime.parse(night.endsAt).format(DateTimeFormatter.ofPattern("HH:mm")))
            night.trackingManual -> context.getString(R.string.runtime_manual_unconfirmed)
            else -> context.getString(R.string.notify_waking, wake)
        }
        val title = if (!night.isTestRun) ordinaryTitle else if (night.trackingManual)
            context.getString(R.string.short_test_state, ordinaryTitle) else context.getString(R.string.short_test_waking, wake)
        val text = status ?: context.getString(if (night.isTestRun) R.string.short_test_waking_detail else R.string.notify_waking_text)
        if (logNotice) log(context, NoticeKind.WAKE_UP, title, text)
        if (!canPost(context)) return
        val builder = Notification.Builder(context, CHANNEL_WAKE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(openApp(context))
        settings.notificationActions.take(3).forEach { action ->
            builder.addAction(Notification.Action.Builder(null, title(context, action, settings), BedJetService.pendingIntent(context, command(action))).build())
        }
        context.getSystemService(NotificationManager::class.java).notify(WAKE_ID, builder.build())
    }

    fun showQuiet(context: Context, night: ActiveNight, settings: Settings, wakePending: Boolean = false) {
        if (!settings.quietNightNotification || night.trackingManual || night.confidence != io.github.willzy12h.nocturne.core.night.Confidence.CONFIRMED) { cancelQuiet(context); return }
        if (!canPost(context)) return
        val wake = ZonedDateTime.parse(night.wakeAt).format(DateTimeFormatter.ofPattern("HH:mm"))
        // While a wake-up change is pending, say so, as Tonight does, instead of a plain "Waking at".
        val notification = Notification.Builder(context, CHANNEL_WORKING).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(if (wakePending) R.string.quiet_pending else if (night.isTestRun)
                R.string.short_test_saved else R.string.runtime_quiet, wake)).setContentIntent(openApp(context)).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, context.getString(R.string.runtime_end), confirmation(context, NightActions.Command.END_NIGHT)).build())
            .build()
        context.getSystemService(NotificationManager::class.java).notify(6, notification)
    }
    fun cancelQuiet(context: Context) = context.getSystemService(NotificationManager::class.java).cancel(6)
    fun showResume(context: Context) {
        if (!canPost(context)) return
        val confirm = confirmation(context, NightActions.Command.RESUME)
        val notification = Notification.Builder(context, CHANNEL_ALERTS).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.runtime_resume_title)).setContentIntent(confirm).setAutoCancel(true)
            .addAction(Notification.Action.Builder(null, context.getString(R.string.runtime_resume_title), confirm).build())
            .build()
        context.getSystemService(NotificationManager::class.java).notify(7, notification)
    }

    private fun confirmation(context: Context, command: NightActions.Command): PendingIntent {
        val captured = context.graph.actions.capture(command, requireIdentity = true)
        return PendingIntent.getActivity(context, ("$command:${captured.deviceId}:${captured.expected}").hashCode(),
            io.github.willzy12h.nocturne.ui.ShortcutActivity.confirmationIntent(context, captured),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
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

    /** Expiring notices need a deadline check; an idle process has no recurring clock job. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    internal suspend fun observeWakeResume(context: Context, ticks: Flow<Unit>? = null) {
        val repo = context.graph.repository
        val expiryTicks = ticks ?: combine(repo.activeNight, repo.runtime) { night, runtime ->
            // A resume action ends at the first boundary, not at both independently. A retained expired
            // offer must not keep the process checking every minute until its original morning wake.
            listOfNotNull(runtime.resumeOffer?.let {
                minOf(ZonedDateTime.parse(it.expiresAt), ZonedDateTime.parse(it.night.wakeAt))
            }, night?.takeIf { runtime.wakeShown == it.identity }?.endsAt?.let(ZonedDateTime::parse)).distinct()
        }.distinctUntilChanged().flatMapLatest { deadlines ->
            flow {
                emit(Unit)
                while (deadlines.isNotEmpty()) {
                    val now = context.graph.now()
                    val remaining = deadlines.map { Duration.between(now, it).toMillis() }
                        .filter { it > 0 }.minOrNull() ?: break
                    // A bounded check also accounts for a wall-clock change while a notice exists.
                    delay(remaining.coerceAtMost(60_000))
                    emit(Unit)
                }
            }
        }
        combine(repo.activeNight, repo.runtime, expiryTicks) { _, _, _ -> Unit }.collect {
            // Separate file publications can be conflated; decide from the latest pair at reconciliation time.
            val runtime = repo.runtime.value
            reconcileWakeResume(context, repo.activeNight.value, runtime.resumeOffer, runtime.wakeShown, runtime.manualWakeShown)
        }
    }

    fun showError(context: Context, title: String, text: String) = showAlert(context, NoticeKind.PROBLEM, title, text, ERROR_ID)

    fun showRetimeError(context: Context, title: String, text: String, target: String?) =
        showAlert(context, NoticeKind.PROBLEM, title, text, ERROR_ID, BedJetService.checkPendingIntent(context, target))

    fun showBluetoothFailure(context: Context, origin: CommandOrigin, failure: io.github.willzy12h.nocturne.ble.BluetoothCommandException) {
        if (origin == CommandOrigin.APP || origin == CommandOrigin.SETTINGS) return
        val now = context.graph.clock.millis()
        val prefs = context.getSharedPreferences("bluetooth_notices", Context.MODE_PRIVATE)
        val claimed = synchronized(this) {
            val previous = if (prefs.contains("off_at")) prefs.getLong("off_at", 0) else null
            if (previous != null && now >= previous && now - previous < BLUETOOTH_NOTICE_WINDOW_MS) false
                else { prefs.edit { putLong("off_at", now) }; true }
        }
        if (claimed) {
            val interrupted = failure is io.github.willzy12h.nocturne.ble.BluetoothInterruptedException
            showAlert(context, NoticeKind.PROBLEM,
                context.getString(if (interrupted) R.string.bluetooth_not_sent else R.string.bluetooth_off),
                if (interrupted) context.getString(R.string.bluetooth_interrupted)
                else context.getString(R.string.bluetooth_off) + ". " + context.getString(R.string.bluetooth_off_help), BLUETOOTH_OFF_ID)
        }
    }

    fun clearBluetoothOff(context: Context) = context.getSystemService(NotificationManager::class.java).cancel(BLUETOOTH_OFF_ID)

    /** Something changed without the person doing it, e.g. the wake-up followed a new alarm. */
    fun showInfo(context: Context, title: String, text: String) = showAlert(context, NoticeKind.INFO, title, text, INFO_ID)

    private fun showAlert(context: Context, kind: NoticeKind, title: String, text: String, id: Int, check: PendingIntent? = null) {
        log(context, kind, title, text)
        if (!canPost(context)) return
        val builder = Notification.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
        if (check != null) builder.addAction(Notification.Action.Builder(null,
            context.getString(R.string.runtime_command_check_bedjet), check).build())
        context.getSystemService(NotificationManager::class.java).notify(id, builder.build())
    }

    fun title(context: Context, action: WakeAction, settings: Settings): String = when (action) {
        WakeAction.SNOOZE_WARM -> context.getString(R.string.notify_action_snooze_warm, settings.snoozeWarm.minutes)
        WakeAction.SNOOZE_COOL -> context.getString(R.string.notify_action_snooze_cool, settings.snoozeCool.minutes)
        WakeAction.EXTEND_WARM -> context.getString(R.string.notify_action_warm, settings.extendWarm.minutes)
        WakeAction.EXTEND_COOL -> context.getString(R.string.notify_action_cool, settings.extendCool.minutes)
        WakeAction.OFF -> context.getString(R.string.runtime_command_off)
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
