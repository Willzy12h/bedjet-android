package io.github.willzy12h.nocturne.night

import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.core.night.ActiveNight
import io.github.willzy12h.nocturne.core.plan.ProfileLimits
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.store.ActionPreset
import io.github.willzy12h.nocturne.core.store.EventKind
import io.github.willzy12h.nocturne.core.store.HistoryEvent
import io.github.willzy12h.nocturne.core.store.WakeAction
import io.github.willzy12h.nocturne.notify.Notifications
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** What every button, widget, shortcut and alarm does. Runs inside [LinkSessions.withConnection]. */
class NightActions(private val graph: Graph) {
    private val repo get() = graph.repository
    private val context get() = graph.context

    enum class Command { START_NIGHT, END_NIGHT, OFF, TURBO, BOOST, COOL, WARM, RETIME, SNOOZE_WARM, SNOOZE_COOL, EXTEND_WARM, EXTEND_COOL }

    /** Runs [command] against the BedJet and returns a short line saying what happened. */
    suspend fun run(command: Command, profileId: String? = null): String = when (command) {
        Command.START_NIGHT -> startNight(profileId)
        Command.END_NIGHT, Command.OFF -> off()
        Command.TURBO -> quick(ActionPreset(Mode.TURBO, 86, 100, 10), "Turbo for 10 minutes")
        Command.BOOST -> boost()
        Command.COOL -> quick(repo.settings.value.extendCool, "Cooling")
        Command.WARM -> quick(repo.settings.value.extendWarm, "Warming")
        Command.RETIME -> retime()
        Command.SNOOZE_WARM -> wakeAction(WakeAction.SNOOZE_WARM)
        Command.SNOOZE_COOL -> wakeAction(WakeAction.SNOOZE_COOL)
        Command.EXTEND_WARM -> wakeAction(WakeAction.EXTEND_WARM)
        Command.EXTEND_COOL -> wakeAction(WakeAction.EXTEND_COOL)
    }

    private suspend fun startNight(profileId: String?): String {
        val profile = repo.profile(profileId) ?: repo.tonightsProfile() ?: return "Add a profile first."
        val settings = repo.settings.value
        repo.activeNight.value?.let { finish(it, EventKind.STOPPED, "Replaced by ${profile.name}") }
        val result = graph.runner().start(profile, AlarmSource.next(context, settings), settings.workingSlot, repo.slotBackups.value)
        result.slotBackup?.let(repo::addSlotBackup)
        repo.addRecord(result.record)
        repo.setActiveNight(result.night)
        NightAlarms.schedule(context, result.night)
        Notifications.cancelWakeUp(context)
        return "${profile.name} started. Waking you at ${clock(result.night.wakeAt)}."
    }

    private suspend fun retime(): String {
        val night = repo.activeNight.value ?: return "No night running."
        val profile = repo.profile(night.profileId) ?: return "The night's profile was deleted."
        val result = try {
            graph.runner().retime(profile, night, AlarmSource.next(context, repo.settings.value))
        } catch (e: Exception) {
            Notifications.showError(context, "Wake-up still at ${clock(night.wakeAt)}", "Your alarm changed but the BedJet couldn't be reached. ${e.message.orEmpty()}")
            throw e
        } ?: return "Wake-up unchanged."
        repo.setActiveNight(result.night)
        repo.addEvent(night.recordId, result.event)
        NightAlarms.schedule(context, result.night)
        return "Wake-up moved to ${clock(result.night.wakeAt)}."
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
            repo.addEvent(night.recordId, HistoryEvent(ZonedDateTime.now().toString(), kind, text))
            // The notification stays until the extra time is over.
            val until = ZonedDateTime.now().plusMinutes(preset.minutes.toLong())
            if (until.isAfter(ZonedDateTime.parse(night.endsAt))) {
                val extended = night.copy(endsAt = until.toString())
                repo.setActiveNight(extended)
                NightAlarms.schedule(context, extended)
            }
            Notifications.showWakeUp(context, repo.activeNight.value!!, settings, status = "$text, until ${clock(until.toString())}")
        }
        return "$text."
    }

    private suspend fun quick(preset: ActionPreset, label: String): String {
        repo.activeNight.value?.let { finish(it, EventKind.STOPPED, "Stopped for $label") }
        graph.runner().quickAction(preset, limits())
        return "$label."
    }

    private suspend fun boost(): String {
        val settings = repo.settings.value
        repo.activeNight.value?.let { finish(it, EventKind.STOPPED, "Stopped for Boost") }
        graph.runner().boost(settings.boostMinutes, limits(), settings.workingSlot, repo.slotBackups.value)?.let(repo::addSlotBackup)
        return "Boost: Turbo, then Heat, for ${settings.boostMinutes} minutes."
    }

    private suspend fun off(): String {
        graph.runner().stop()
        repo.activeNight.value?.let { finish(it, EventKind.STOPPED, "Turned off") }
        return "BedJet off."
    }

    /** Ends the night in the app without touching the BedJet: its plan has finished, or something replaced it. */
    fun finish(night: ActiveNight, kind: EventKind, detail: String) {
        repo.addEvent(night.recordId, HistoryEvent(ZonedDateTime.now().toString(), kind, detail))
        repo.setActiveNight(null)
        NightAlarms.cancel(context)
        Notifications.cancelWakeUp(context)
    }

    private fun limits(): ProfileLimits =
        (repo.activeNight.value?.let { repo.profile(it.profileId) } ?: repo.tonightsProfile())?.limits ?: ProfileLimits()

    private fun clock(iso: String) = ZonedDateTime.parse(iso).format(DateTimeFormatter.ofPattern("HH:mm"))

}
