package io.github.willzy12h.nocturne.platform

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.job.JobScheduler
import android.content.Intent
import android.os.PersistableBundle
import io.github.willzy12h.nocturne.Graph
import io.github.willzy12h.nocturne.core.plan.AlarmPolicy
import io.github.willzy12h.nocturne.core.plan.Profile
import io.github.willzy12h.nocturne.core.plan.WakeResolver
import io.github.willzy12h.nocturne.core.store.AutoStartMode
import io.github.willzy12h.nocturne.core.store.AutoStartSettings
import io.github.willzy12h.nocturne.core.store.BackupCodec
import io.github.willzy12h.nocturne.core.store.deviceId
import io.github.willzy12h.nocturne.night.AlarmSource
import java.security.MessageDigest
import java.time.ZonedDateTime

enum class AutoStartWakeSource { ALARM, PROFILE, OVERRIDE }

/** Immutable inputs are checked again inside the command owner; an alarm never authorises a stale plan. */
data class AutoStartPlan(val settings: AutoStartSettings, val profile: Profile, val startAt: ZonedDateTime,
    val wakeAt: ZonedDateTime?, val wakeSource: AutoStartWakeSource?, val workingSlot: Int) {
    // A relative start at midnight belongs to the morning it serves, not the following evening's night.
    // Subtracting one wake date preserves the established key for ordinary pre-midnight starts.
    val key: String get() = "${settings.deviceId}:${wakeAt?.toLocalDate()?.minusDays(1) ?: startAt.toLocalDate()}"
    val fingerprint: String get() {
        val source = listOf(BackupCodec.json.encodeToString(AutoStartSettings.serializer(), settings),
            BackupCodec.json.encodeToString(Profile.serializer(), profile), startAt.toString(), wakeAt?.toString().orEmpty(),
            wakeSource?.name.orEmpty(), workingSlot.toString()).joinToString("\n")
        return MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
    fun ticket() = AutoStartTicket(startAt.toString(), key, fingerprint)
}

/** The only receiver/job extras: bounded time, night key and a digest of the reviewed settings/profile. */
data class AutoStartTicket(val startsAt: String, val key: String, val fingerprint: String) {
    fun extras() = PersistableBundle().apply {
        putString("start", startsAt); putString("key", key); putString("fingerprint", fingerprint)
    }
    fun putInto(intent: Intent): Intent = intent.putExtra("start", startsAt).putExtra("key", key).putExtra("fingerprint", fingerprint)
    companion object {
        fun read(start: String?, key: String?, fingerprint: String?): AutoStartTicket? {
            if (start == null || start.length > 100 || key == null || key.length > 160 ||
                fingerprint == null || !fingerprint.matches(Regex("[0-9a-f]{64}"))) return null
            if (runCatching { ZonedDateTime.parse(start) }.getOrNull() == null) return null
            return AutoStartTicket(start, key, fingerprint)
        }
        fun from(intent: Intent): AutoStartTicket? = runCatching {
            read(intent.getStringExtra("start"), intent.getStringExtra("key"), intent.getStringExtra("fingerprint"))
        }.getOrNull()
        fun from(extras: PersistableBundle): AutoStartTicket? = read(extras.getString("start"), extras.getString("key"), extras.getString("fingerprint"))
    }
}

object AutoStartSchedule {
    const val WINDOW_MS = 15 * 60 * 1000L
    private const val REQUEST = 6104
    private val claimPrefixes = listOf("charger:", "auto-skip:", "cancel:", "charger-unreadable:")

    internal fun claimed(graph: Graph, key: String): Boolean =
        claimPrefixes.any { "$it$key" in graph.repository.automation.value.claims }

    /** One-way interpretation of the old charger setting; a subsequent explicit choice always wins. */
    fun settings(graph: Graph): AutoStartSettings {
        val saved = graph.repository.settings.value
        saved.platform.autoStart?.let { return it }
        val profile = saved.resolveProfile(graph.repository.profiles.value, graph.now())
        return AutoStartSettings(enabled = saved.platform.chargerAutoStart,
            fixedMinute = profile?.bedtimeMinute ?: 22 * 60 + 30, profileId = profile?.id,
            deviceId = saved.platform.chargerDevice ?: saved.targetDevice,
            onlyIfCharging = saved.platform.chargerAutoStart)
    }

    suspend fun migrate(graph: Graph) {
        if (graph.repository.settings.value.demoMode || graph.repository.settings.value.platform.autoStart != null ||
            !graph.repository.settings.value.platform.chargerAutoStart) return
        graph.repository.updateSettings { saved ->
            if (saved.demoMode || saved.platform.autoStart != null || !saved.platform.chargerAutoStart) saved
            else saved.copy(platform = saved.platform.copy(autoStart = settings(graph), chargerAutoStart = false, chargerDevice = null))
        }
    }

    /** Earliest future alarm. An unaccepted phone alarm falls back to this explicitly chosen profile. */
    fun next(graph: Graph, after: ZonedDateTime = graph.now()): AutoStartPlan? {
        val saved = graph.repository.settings.value
        val config = settings(graph)
        if (saved.demoMode || !config.enabled || config.validationErrors().isNotEmpty() ||
            saved.deviceId() != config.deviceId) return null
        val profile = graph.repository.profile(config.profileId!!) ?: return null
        if (config.mode == AutoStartMode.FIXED) {
            val at = WakeResolver.nextOccurrence(config.fixedMinute, after)
            return AutoStartPlan(config, profile, at, null, null, saved.workingSlot)
        }
        val override = saved.resolveWakeOverride(after)?.wakeAt?.let { runCatching { ZonedDateTime.parse(it) }.getOrNull() }
        val alarm = AlarmSource.describe(graph.context, saved)
        val offset = config.minutesBeforeWake.toLong()
        val accepted = alarm?.takeIf {
            val plannedStart = it.time.minusMinutes(offset)
            AlarmPolicy(saved).accept(it.time, it.creator, plannedStart) && !plannedStart.isAfter(after.plusHours(24))
        }?.time
        val preferred = override ?: accepted
        val start = preferred?.minusMinutes(offset)
        if (preferred != null && start != null && !start.isBefore(after) && !start.isAfter(after.plusHours(24)))
            return AutoStartPlan(config, profile, start.withZoneSameInstant(after.zone), preferred.withZoneSameInstant(after.zone),
                if (override != null) AutoStartWakeSource.OVERRIDE else AutoStartWakeSource.ALARM, saved.workingSlot)
        // A missed alarm-relative start cannot turn into a second, later fallback for that same wake date.
        val fallbackFrom = if (preferred != null && preferred.isAfter(after) && start!!.isBefore(after))
            maxOf(after.plusMinutes(offset), preferred.withZoneSameInstant(after.zone).toLocalDate().plusDays(1).atStartOfDay(after.zone))
            else after.plusMinutes(offset)
        val wake = WakeResolver.nextOccurrence(profile.wakeSource.defaultWakeMinute, fallbackFrom)
        return AutoStartPlan(config, profile, wake.minusMinutes(config.minutesBeforeWake.toLong()), wake,
            AutoStartWakeSource.PROFILE, saved.workingSlot)
    }

    fun due(graph: Graph, ticket: AutoStartTicket? = null): AutoStartPlan? {
        val now = graph.now()
        val candidate = if (ticket == null) next(graph, now.minusMinutes(15).minusNanos(1)) else {
            val start = runCatching { ZonedDateTime.parse(ticket.startsAt) }.getOrNull() ?: return null
            if (start.zone != now.zone) return null
            next(graph, start.minusNanos(1))?.takeIf { it.ticket() == ticket }
        } ?: return null
        if (now.isBefore(candidate.startAt) || !now.isBefore(candidate.startAt.plusMinutes(15))) return null
        return candidate
    }

    fun stillCurrent(graph: Graph, plan: AutoStartPlan): Boolean = due(graph, plan.ticket()) == plan

    private fun pending(graph: Graph, ticket: AutoStartTicket? = null): PendingIntent {
        val intent = Intent(graph.context, AutoStartReceiver::class.java)
        ticket?.putInto(intent)
        return PendingIntent.getBroadcast(graph.context, REQUEST, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
    fun exact(graph: Graph): Boolean = graph.context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    /** The UI and AlarmManager share this next eligible night; due() still validates an already owned attempt. */
    fun nextScheduled(graph: Graph): AutoStartPlan? {
        val queued = graph.context.getSystemService(JobScheduler::class.java).getPendingJob(ChargerJobs.ID)
            ?.takeUnless { it.isPeriodic }?.extras?.let(AutoStartTicket::from)?.key
        val blocked = graph.repository.automation.value.claims.mapNotNull { claim ->
            claimPrefixes.firstOrNull { claim.startsWith(it) }?.let { claim.removePrefix(it) }
        }.toSet() + listOfNotNull(queued)
        var after = graph.now()
        repeat(blocked.size + 1) {
            val candidate = next(graph, after) ?: return null
            if (candidate.key !in blocked) return candidate
            after = candidate.startAt.plusNanos(1)
        }
        return null
    }

    fun syncAlarm(graph: Graph) {
        val alarms = graph.context.getSystemService(AlarmManager::class.java)
        alarms.cancel(pending(graph))
        val upcoming = nextScheduled(graph) ?: return
        val operation = pending(graph, upcoming.ticket())
        val at = upcoming.startAt.toInstant().toEpochMilli()
        if (exact(graph)) {
            try { alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation); return }
            catch (_: SecurityException) { /* Permission can be revoked between the query and the call. */ }
        }
        alarms.setWindow(AlarmManager.RTC_WAKEUP, at, WINDOW_MS, operation)
    }
}
