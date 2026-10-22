package io.github.willzy12h.nocturne.night

import android.content.Context
import android.os.SystemClock
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.core.link.BedJetError
import io.github.willzy12h.nocturne.core.night.ActiveNight
import io.github.willzy12h.nocturne.core.plan.ProfileLimits
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.store.ActionPreset
import io.github.willzy12h.nocturne.core.store.EventKind
import io.github.willzy12h.nocturne.core.store.HistoryEvent
import io.github.willzy12h.nocturne.core.store.Migrations
import io.github.willzy12h.nocturne.core.store.WakeAction
import io.github.willzy12h.nocturne.core.store.deviceId
import io.github.willzy12h.nocturne.notify.Notifications
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * What every button, widget, shortcut and alarm does. Every entry point goes through [perform] or [exclusive], so
 * only one thing talks to the BedJet at a time: a night is several writes that must not mix with anything else.
 */
class NightActions(private val graph: Graph) {
    private val repo get() = graph.repository
    private val context get() = graph.context
    private val prefs get() = context.getSharedPreferences("night-actions", Context.MODE_PRIVATE)
    private val lock = Mutex()
    @Volatile private var retiming = false

    enum class Command { START_NIGHT, END_NIGHT, OFF, TURBO, BOOST, COOL, WARM, RETIME, REAPPLY, SNOOZE_WARM, SNOOZE_COOL, EXTEND_WARM, EXTEND_COOL }

    /**
     * Connects, runs [command] and lets go, one command at a time. A failure during a night is noted in its history
     * and thrown on. A re-time keeps trying for a minute and reports its own failure, so it never throws.
     */
    suspend fun perform(command: Command, profileId: String? = null): String {
        if (command == Command.RETIME) return retimeWithin(RETRY_MS)
        if (command == Command.START_NIGHT && profileId != null && repo.profile(profileId) == null) return PROFILE_DELETED
        return try {
            exclusive { run(command, profileId) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            recordError("${label(command)} didn't work: ${e.message.orEmpty()}")
            throw e
        }
    }

    /** Runs [work] while connected and with nothing else talking to the BedJet. Don't nest these. */
    suspend fun <T> exclusive(work: suspend () -> T): T = lock.withLock { graph.sessions.withConnection { work() } }

    /** True while an alarm change couldn't be put on the BedJet yet. Retried when the app opens or the alarm changes. */
    val retimePending: Boolean get() = prefs.getBoolean(KEY_RETIME_PENDING, false)

    /** Retries a re-time that failed earlier, unless one is already under way. Null when there is nothing to retry. */
    suspend fun retryPendingRetime(): String? = if (retimePending && !retiming) perform(Command.RETIME) else null

    /**
     * Follows an alarm change, trying to reach the BedJet until [patienceMs] has passed. Work already under way when
     * time runs out is finished rather than cut off halfway through writing a program. On failure the person is
     * told the wake-up didn't move, and the re-time is kept to try again later.
     */
    suspend fun retimeWithin(patienceMs: Long): String {
        if (repo.activeNight.value == null) return "No night running.".also { setRetimePending(false) }
        if (alarmIgnored()) return UNCHANGED.also { setRetimePending(false) }
        val deadline = SystemClock.elapsedRealtime() + patienceMs
        retiming = true
        try {
            while (true) {
                val failure = try {
                    return exclusiveUntil(deadline) { retime() }.also { setRetimePending(false) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    e
                }
                val retryable = failure is BedJetError && failure !is BedJetError.ProgramTooLong && failure !is BedJetError.InvalidProgram
                if (!retryable || deadline - SystemClock.elapsedRealtime() < RETRY_GAP_MS) return retimeFailed(failure, retryable)
                delay(RETRY_GAP_MS)
            }
        } finally {
            retiming = false
        }
    }

    /** Runs [command] against the connected BedJet and returns a short line saying what happened. */
    private suspend fun run(command: Command, profileId: String? = null): String = when (command) {
        Command.START_NIGHT -> startNight(profileId)
        Command.END_NIGHT, Command.OFF -> off()
        Command.TURBO -> quick(ActionPreset(Mode.TURBO, 86, 100, 10), "Turbo for 10 minutes")
        Command.BOOST -> boost()
        Command.COOL -> quick(repo.settings.value.extendCool, "Cooling")
        Command.WARM -> quick(repo.settings.value.extendWarm, "Warming")
        Command.RETIME -> retime()
        Command.REAPPLY -> reapply()
        Command.SNOOZE_WARM -> wakeAction(WakeAction.SNOOZE_WARM)
        Command.SNOOZE_COOL -> wakeAction(WakeAction.SNOOZE_COOL)
        Command.EXTEND_WARM -> wakeAction(WakeAction.EXTEND_WARM)
        Command.EXTEND_COOL -> wakeAction(WakeAction.EXTEND_COOL)
    }

    private suspend fun startNight(profileId: String?): String {
        // A widget or shortcut can outlive its profile; starting something else instead would surprise.
        val profile = (if (profileId != null) repo.profile(profileId) ?: return PROFILE_DELETED else repo.tonightsProfile())
            ?: return "Add a profile first."
        val settings = repo.settings.value
        val previous = repo.activeNight.value
        val device = settings.deviceId()
        val result = graph.runner().start(profile, AlarmSource.next(context, settings), settings.workingSlot, repo.originalsFor(device), device ?: Migrations.UNKNOWN_DEVICE)
        // Only now that the new night is running is the old one over.
        previous?.let { finish(it, EventKind.STOPPED, "Replaced by ${profile.name}") }
        // With no BedJet chosen there is no device to keep an original for.
        if (device != null) result.slotBackup?.let { repo.addSlotBackup(it) }
        repo.addRecord(result.record)
        repo.setActiveNight(result.night)
        NightAlarms.schedule(context, result.night)
        Notifications.cancelWakeUp(context)
        return "${profile.name} started. Waking you at ${clock(result.night.wakeAt)}."
    }

    /** Puts edits to the running profile on the BedJet for the rest of tonight, keeping the wake time. */
    private suspend fun reapply(): String {
        val night = repo.activeNight.value ?: return "No night running."
        val profile = repo.profile(night.profileId) ?: return "The night's profile was deleted."
        val result = graph.runner().retime(profile, night, ZonedDateTime.parse(night.wakeAt), force = true)
            ?: return "Tonight's wake-up has already started, so it's left as it is."
        repo.setActiveNight(result.night)
        repo.addEvent(night.recordId, result.event)
        NightAlarms.schedule(context, result.night)
        return "Changes applied to the rest of tonight. Waking you at ${clock(result.night.wakeAt)}."
    }

    private suspend fun retime(): String {
        val night = repo.activeNight.value ?: return "No night running."
        val profile = repo.profile(night.profileId) ?: return "The night's profile was deleted."
        // A calendar reminder or another app's alarm is next; the clock alarm may still be set behind it.
        if (alarmIgnored()) return UNCHANGED
        val result = graph.runner().retime(profile, night, AlarmSource.next(context, repo.settings.value)) ?: return UNCHANGED
        repo.setActiveNight(result.night)
        repo.addEvent(night.recordId, result.event)
        NightAlarms.schedule(context, result.night)
        // The person changed their alarm, not the night, so say what followed from it.
        Notifications.showInfo(
            context,
            "Wake-up moved to ${clock(result.night.wakeAt)}",
            "Your alarm changed, so tonight's BedJet plan now wakes you at ${clock(result.night.wakeAt)} instead of ${clock(night.wakeAt)}.",
        )
        return "Wake-up moved to ${clock(result.night.wakeAt)}."
    }

    private suspend fun retimeFailed(e: Exception, tryAgain: Boolean): String {
        val night = repo.activeNight.value ?: return "No night running."
        val title = "Wake-up still at ${clock(night.wakeAt)} — couldn't reach BedJet"
        val later = if (tryAgain) " Nocturne will try again when you open it." else ""
        Notifications.showError(context, title, "Your alarm changed but the BedJet didn't follow. ${e.message.orEmpty()}$later".trim())
        recordError("Alarm changed but the wake-up couldn't be moved: ${e.message.orEmpty()}")
        setRetimePending(tryAgain)
        return "$title."
    }

    private suspend fun wakeAction(action: WakeAction): String {
        val settings = repo.settings.value
        val (preset, kind, label) = when (action) {
            WakeAction.SNOOZE_WARM -> Triple(settings.snoozeWarm, EventKind.SNOOZED, "Snooze warm")
            WakeAction.SNOOZE_COOL -> Triple(settings.snoozeCool, EventKind.SNOOZED, "Snooze cool")
            WakeAction.EXTEND_WARM -> Triple(settings.extendWarm, EventKind.EXTENDED, "Extend warm")
            WakeAction.EXTEND_COOL -> Triple(settings.extendCool, EventKind.EXTENDED, "Extend cool")
            WakeAction.OFF -> return off()
        }
        graph.runner().quickAction(preset, limits())
        val text = "$label ${preset.minutes} min"
        repo.activeNight.value?.let { night ->
            repo.addEvent(night.recordId, HistoryEvent(graph.now().toString(), kind, text))
            // The notification stays until the extra time is over.
            val until = graph.now().plusMinutes(preset.minutes.toLong())
            if (until.isAfter(ZonedDateTime.parse(night.endsAt))) {
                val extended = night.copy(endsAt = until.toString())
                repo.setActiveNight(extended)
                NightAlarms.schedule(context, extended)
            }
            Notifications.showWakeUp(context, repo.activeNight.value!!, settings, status = "$text, until ${clock(until.toString())}")
        }
        return "$text."
    }

    // The night ends only once the BedJet has taken the new command; if it can't be reached the night carries on.

    private suspend fun quick(preset: ActionPreset, label: String): String {
        graph.runner().quickAction(preset, limits())
        repo.activeNight.value?.let { finish(it, EventKind.STOPPED, "Stopped for $label") }
        return "$label."
    }

    private suspend fun boost(): String {
        val settings = repo.settings.value
        val device = settings.deviceId()
        val backup = graph.runner().boost(settings.boostMinutes, limits(), settings.workingSlot, repo.originalsFor(device), device ?: Migrations.UNKNOWN_DEVICE)
        if (device != null) backup?.let { repo.addSlotBackup(it) }
        repo.activeNight.value?.let { finish(it, EventKind.STOPPED, "Stopped for Boost") }
        return "Boost: Turbo, then Heat, for ${settings.boostMinutes} minutes."
    }

    private suspend fun off(): String {
        graph.runner().stop()
        repo.activeNight.value?.let { finish(it, EventKind.STOPPED, "Turned off") }
        return "BedJet off."
    }

    /** Ends the night in the app without touching the BedJet: its plan has finished, or something replaced it. */
    suspend fun finish(night: ActiveNight, kind: EventKind, detail: String) {
        repo.addEvent(night.recordId, HistoryEvent(graph.now().toString(), kind, detail))
        repo.setActiveNight(null)
        NightAlarms.cancel(context)
        Notifications.cancelWakeUp(context)
        setRetimePending(false)
    }

    /** Like [exclusive], but stops waiting for the lock or the connection at [deadline]. */
    private suspend fun <T> exclusiveUntil(deadline: Long, work: suspend () -> T): T {
        withTimeoutOrNull(remaining(deadline)) { lock.lock() } ?: throw BedJetError.NotConnected()
        try {
            withTimeoutOrNull(remaining(deadline)) { graph.sessions.acquire() } ?: throw BedJetError.NotConnected()
            try {
                return work()
            } finally {
                graph.sessions.releaseLater()
            }
        } finally {
            lock.unlock()
        }
    }

    private fun remaining(deadline: Long) = maxOf(1L, deadline - SystemClock.elapsedRealtime())

    private fun alarmIgnored() = AlarmSource.describe(context, repo.settings.value)?.counts == false

    private fun setRetimePending(pending: Boolean) {
        if (retimePending != pending) prefs.edit().putBoolean(KEY_RETIME_PENDING, pending).apply()
    }

    private suspend fun recordError(detail: String) {
        repo.activeNight.value?.let { repo.addEvent(it.recordId, HistoryEvent(graph.now().toString(), EventKind.ERROR, detail.trim())) }
    }

    private fun label(command: Command) = command.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }

    private fun limits(): ProfileLimits =
        (repo.activeNight.value?.let { repo.profile(it.profileId) } ?: repo.tonightsProfile())?.limits ?: ProfileLimits()

    private fun clock(iso: String) = ZonedDateTime.parse(iso).format(DateTimeFormatter.ofPattern("HH:mm"))

    companion object {
        /** How long a re-time keeps trying to reach the BedJet. */
        const val RETRY_MS = 60_000L
        private const val RETRY_GAP_MS = 5_000L
        private const val KEY_RETIME_PENDING = "retimePending"
        private const val UNCHANGED = "Wake-up unchanged."
        private const val PROFILE_DELETED = "That profile was deleted."
    }
}
