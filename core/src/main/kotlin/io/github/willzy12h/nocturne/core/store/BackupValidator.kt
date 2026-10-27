package io.github.willzy12h.nocturne.core.store

import io.github.willzy12h.nocturne.core.link.DeviceId
import io.github.willzy12h.nocturne.core.plan.Phase
import io.github.willzy12h.nocturne.core.plan.Profile
import io.github.willzy12h.nocturne.core.protocol.Limits
import io.github.willzy12h.nocturne.core.protocol.Mode
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import kotlinx.serialization.json.*
import java.time.ZonedDateTime

/**
 * Checks a backup before anything is replaced with it, so a damaged or hand-edited file can't leave the app holding
 * values the BedJet or the planner would refuse. Every problem is reported, each as a plain sentence.
 */
object BackupValidator {
    /** Bigger than any real backup: 60 nights of history come to well under 1 MB. */
    const val MAX_BYTES = 5 * 1024 * 1024
    const val MAX_PHASE_MINUTES = 720
    const val MAX_RAMP_STEPS = 8
    const val MAX_PROFILES = 128
    const val MAX_HISTORY = 2_048
    const val MAX_ORIGINALS = 384
    private const val MAX_ID = 128
    private const val MAX_NAME = 120
    private const val MAX_DETAIL = 2_048
    private const val MAX_PHASES = 64
    private const val MAX_EVENTS = 1_024
    private const val MAX_SAMPLES = 4_096
    private const val MAX_REVISIONS = 256
    private const val MAX_RECORDED_STEPS = 256
    private val FAN = 5..100
    private val PHASE_MINUTES = 1..MAX_PHASE_MINUTES
    private val RAMP_STEPS = 1..MAX_RAMP_STEPS
    private val SLOTS = 0 until Limits.SLOTS
    private val SAVED_DEVICE = Regex("ble:(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}")
    private fun validDevice(value: String) = value == DeviceId.DEMO || value == Migrations.UNKNOWN_DEVICE || SAVED_DEVICE.matches(value)
    private val BOOST_MINUTES = 10..30
    private val ACTION_MINUTES = 1..120
    private val MINUTE_OF_DAY = 0 until 24 * 60
    private val DAYS = 1..7
    private const val MAX_NOTIFICATION_ACTIONS = 3
    /** A stored programme step's temperature is one byte on the wire. */
    private val CODEC_BYTE = 0..255
    /** Every temperature any mode allows, Turbo's included. */
    private val allTemps = Mode.entries.minOf { it.temperatureRange.first }..Mode.entries.maxOf { it.temperatureRange.last }

    /**
     * Reads a backup file, refusing it once it passes [MAX_BYTES] without reading the rest or decoding any of it.
     * Throws [BackupException] when it is too big.
     */
    fun readWithinLimit(input: InputStream): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8_192)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            if (out.size() > MAX_BYTES) throw BackupException("This file is too big to be a Nocturne backup (over 5 MB).", failure = BackupFailure.TOO_LARGE)
        }
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(out.toByteArray())).toString()
        } catch (_: CharacterCodingException) {
            throw BackupException("BACKUP_UTF8_INVALID", failure = BackupFailure.DAMAGED)
        }
    }

    /** Every problem with [bundle]; empty when it is safe to restore. */
    fun validate(bundle: BackupBundle): List<String> = buildList {
        // Diagnostic codes are mapped to resource-backed import messages by the app, never shown as UI text.
        addAll(strictBounds(bundle))
        if (isNotEmpty()) return@buildList
        if (bundle.schema < 1) add("BACKUP_SCHEMA_INVALID")
        if (bundle.schema > BackupCodec.SCHEMA) add("This backup is from a newer version of Nocturne. Update the app first.")
        time("The backup's creation time", bundle.createdAt)

        bundle.profiles.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys
            .forEach { add("The profile id \"$it\" is used more than once.") }
        bundle.profiles.forEach { profile(it) }
        settings(bundle.settings)

        bundle.slotBackups.forEach { backup ->
            if (backup.slot !in SLOTS) add("A saved slot is numbered ${backup.slot + 1}; it must be 1 to ${Limits.SLOTS}.")
            time("The time slot ${backup.slot + 1} was saved", backup.savedAt)
            val device = backup.deviceId
            if (!validDevice(device))
                add("The original of slot ${backup.slot + 1} was saved for \"$device\", which isn't a BedJet this app knows.")
            if (backup.empty != (backup.program == null)) add("A saved slot's empty marker disagrees with its programme.")
            backup.program?.let { program ->
                if (program.name.toByteArray(Charsets.UTF_8).size > Limits.NAME_LENGTH)
                    add("A saved slot programme name is too long for the BedJet.")
                if (program.steps.size > Limits.STEPS_PER_PROGRAM)
                    add("A saved slot programme must have at most ${Limits.STEPS_PER_PROGRAM} steps.")
                program.steps.forEachIndexed { index, step ->
                    val what = "Saved slot ${backup.slot + 1}, step ${index + 1}"
                    // A recorded original is put back exactly as the BedJet held it, so it is checked against what
                    // the codec carries, not the official app's per-mode range or heat cut-off table: a BedJet can
                    // hold a programme another app wrote, and the app's own snapshots must restore (IR-16).
                    if (step.fanStep !in 0..19) add("$what has a fan setting outside 5 to 100%.")
                    if (step.temperature !in CODEC_BYTE) add("$what has a temperature value the BedJet cannot hold.")
                    if (step.minutes !in 0..59 || step.hours !in (if (step.endsAtClockTime) 0..23 else 0..11) ||
                        (!step.endsAtClockTime && step.durationMinutes !in 1..Limits.MAX_STEP_MINUTES))
                        add("$what has a time the BedJet cannot run.")
                }
            }
        }

        bundle.history.forEach { night ->
            val what = "The night \"${night.profileName}\""
            time("$what start time", night.startedAt)
            time("$what wake-up time", night.wakeAt)
            night.steps.forEach { time("$what step time", it.startsAt) }
            night.events.forEach { time("$what event time", it.at) }
            night.temperatures.forEach { time("$what temperature reading time", it.at) }
        }
    }

    /** Bound every independently growing field before the more descriptive existing checks iterate it. */
    private fun strictBounds(bundle: BackupBundle): List<String> = buildList {
        fun invalid(path: String) { add("BACKUP_FIELD_INVALID:$path") }
        fun bounded(value: String, path: String, maximum: Int, blank: Boolean = false) {
            if (value.length > maximum || (!blank && value.isBlank()) || value.any { it.isISOControl() }) invalid(path)
        }
        fun count(size: Int, maximum: Int, path: String) { if (size > maximum) invalid(path) }
        count(bundle.profiles.size, MAX_PROFILES, "profiles")
        count(bundle.history.size, MAX_HISTORY, "history")
        count(bundle.slotBackups.size, MAX_ORIGINALS, "slotBackups")
        if (isNotEmpty()) return@buildList
        bundle.profiles.forEach { profile ->
            bounded(profile.id, "profile.id", MAX_ID)
            bounded(profile.name, "profile.name", MAX_NAME)
            count(profile.start.size, MAX_PHASES, "profile.start")
            count(profile.wake.size, MAX_PHASES, "profile.wake")
            count(profile.afterWake.size, MAX_PHASES, "profile.afterWake")
        }
        fun recorded(step: RecordedStep) {
            if (step.fanPercent !in 0..100 || step.temperature !in CODEC_BYTE || step.minutes !in 0..1_080)
                invalid("history.step")
        }
        bundle.history.forEach { night ->
            bounded(night.id, "history.id", MAX_ID)
            bounded(night.profileId, "history.profileId", MAX_ID)
            bounded(night.profileName, "history.profileName", MAX_NAME)
            bounded(night.deviceId, "history.deviceId", MAX_ID)
            if (!validDevice(night.deviceId))
                invalid("history.deviceId")
            count(night.steps.size, MAX_RECORDED_STEPS, "history.steps")
            count(night.events.size, MAX_EVENTS, "history.events")
            count(night.temperatures.size, MAX_SAMPLES, "history.temperatures")
            count(night.revisions.size, MAX_REVISIONS, "history.revisions")
            if (night.steps.size <= MAX_RECORDED_STEPS) night.steps.forEach(::recorded)
            if (night.events.size <= MAX_EVENTS) night.events.forEach { event ->
                if (event.detail.length > MAX_DETAIL || event.detail.any { it.isISOControl() && it != '\n' && it != '\t' }) invalid("history.event.detail")
            }
            if (night.temperatures.size <= MAX_SAMPLES) night.temperatures.forEach {
                if (it.room !in CODEC_BYTE || it.air !in CODEC_BYTE) invalid("history.temperature")
            }
            if (night.revisions.size <= MAX_REVISIONS) night.revisions.forEach { revision ->
                if (revision.revision < 0) invalid("history.revision")
                if (revision.start.isNotEmpty() && runCatching { ZonedDateTime.parse(revision.start) }.isFailure) invalid("history.revision.start")
                if (revision.wake.isNotEmpty() && runCatching { ZonedDateTime.parse(revision.wake) }.isFailure) invalid("history.revision.wake")
                count(revision.steps.size, MAX_RECORDED_STEPS, "history.revision.steps")
                count(revision.programSteps.size, Limits.STEPS_PER_PROGRAM, "history.revision.programSteps")
                if (revision.steps.size <= MAX_RECORDED_STEPS) revision.steps.forEach {
                    recorded(it)
                    if (runCatching { ZonedDateTime.parse(it.startsAt) }.isFailure) invalid("history.revision.step.time")
                }
                if (revision.programSteps.size <= Limits.STEPS_PER_PROGRAM) revision.programSteps.forEach { step ->
                    if (step.fanStep !in 0..19 || step.temperature !in CODEC_BYTE || step.minutes !in 0..59 ||
                        step.hours !in (if (step.endsAtClockTime) 0..23 else 0..11) ||
                        (!step.endsAtClockTime && step.durationMinutes !in 1..Limits.MAX_STEP_MINUTES)) invalid("history.revision.programStep")
                }
            }
        }
        if (bundle.history.map { it.id }.distinct().size != bundle.history.size) invalid("history.duplicateId")
        bundle.slotBackups.forEach {
            bounded(it.deviceId, "slotBackups.deviceId", MAX_ID)
            it.program?.let { program -> bounded(program.name, "slotBackups.program.name", Limits.NAME_LENGTH, blank = true) }
        }
        if (bundle.slotBackups.map { it.deviceId.lowercase(java.util.Locale.ROOT) to it.slot }.distinct().size != bundle.slotBackups.size)
            invalid("slotBackups.duplicateIdentity")
        val settings = bundle.settings
        if (!QuickDefaults.validOverrides(settings.quickDefaults)) invalid("settings.quickDefaults")
        if (settings.setupDismissedOptional.any { it !in setOf("health", "widget") }) invalid("settings.setupDismissedOptional")
        settings.selectedProfileId?.let { bounded(it, "settings.selectedProfileId", MAX_ID) }
        settings.tonightChoice?.let { bounded(it.profileId, "settings.tonightChoice.profileId", MAX_ID) }
        settings.targetDevice?.let { bounded(it, "settings.targetDevice", MAX_ID); if (!SAVED_DEVICE.matches(it)) invalid("settings.targetDevice") }
        settings.setupFanCheckedDevice?.let { bounded(it, "settings.setupFanCheckedDevice", MAX_ID); if (!SAVED_DEVICE.matches(it)) invalid("settings.setupFanCheckedDevice") }
        count(settings.alarmPackages.size, 128, "settings.alarmPackages")
        settings.alarmPackages.take(129).forEach { bounded(it, "settings.alarmPackages", 255) }
        count(settings.tonightSectionOrder.size, 64, "settings.tonightSectionOrder")
        settings.tonightSectionOrder.take(65).forEach { bounded(it, "settings.tonightSectionOrder", MAX_ID) }
        // Also bound additive schema-2 fields introduced alongside this importer without depending on their
        // compile-time model revision. Local device remembrance and Health proof still have strict import types.
        if (isNotEmpty()) return@buildList
        val encoded = BackupCodec.json.encodeToJsonElement(BackupBundle.serializer(), bundle).jsonObject
        fun inspect(value: JsonElement, path: String, key: String = "") {
            when (value) {
                is JsonObject -> {
                    val exported = (value["healthExportedVersion"] as? JsonPrimitive)?.longOrNull
                    val version = (value["healthExportVersion"] as? JsonPrimitive)?.longOrNull
                    if (exported != null && version != null && exported > version) invalid("$path.healthExportedVersion")
                    value.forEach { (name, child) -> inspect(child, "$path.$name", name) }
                }
                is JsonArray -> {
                    if (key == "rememberedBedJets" && (value.size > 16 || value.any {
                            val id = (it as? JsonPrimitive)?.content.orEmpty()
                            !Regex("ble:(?:[0-9A-F]{2}:){5}[0-9A-F]{2}").matches(id)
                        } || value.distinct().size != value.size)) invalid(path)
                    if (key == "collapsedTonightSections" && value.any { (it as? JsonPrimitive)?.content !in setOf("night", "wake", "connection") }) invalid(path)
                    if (value.size > MAX_SAMPLES) invalid(path)
                    else value.forEach { inspect(it, path, key) }
                }
                is JsonPrimitive -> if (value !is JsonNull) {
                    if (value.isString && value.content.length > MAX_DETAIL) invalid(path)
                    if (key == "profileId" && value.isString && (value.content.isBlank() || value.content.length > MAX_ID ||
                            value.content.any { it.isISOControl() })) invalid(path)
                    if (key in setOf("healthExportVersion", "healthExportedVersion") && (value.longOrNull ?: -1) < 0) invalid(path)
                    if (key == "confirmedCompletionAt" && runCatching { ZonedDateTime.parse(value.content) }.isFailure) invalid(path)
                }
            }
        }
        inspect(encoded, "backup")
    }

    private fun MutableList<String>.profile(profile: Profile) {
        val name = profile.name.ifBlank { profile.id }
        listOf("start" to profile.start, "wake-up" to profile.wake, "after wake-up" to profile.afterWake).forEach { (block, phases) ->
            phases.forEachIndexed { i, phase -> phase(phase, "$name: step ${i + 1} of the $block") }
        }
        val middle = profile.middle
        fan("$name: the middle's starting fan", middle.fanStart)
        fan("$name: the middle's ending fan", middle.fanEnd)
        temperature("$name: the middle's starting temperature", middle.mode, middle.tempStart)
        temperature("$name: the middle's ending temperature", middle.mode, middle.tempEnd)
        if (middle.rampSteps !in RAMP_STEPS) add("$name: the middle has ${middle.rampSteps} ramp steps; it must be 1 to $MAX_RAMP_STEPS.")
        val limits = profile.limits
        if (limits.minFan !in FAN || limits.maxFan !in FAN || limits.minFan > limits.maxFan) {
            add("$name: the fan limits ${limits.minFan}% to ${limits.maxFan}% must be in order, within 5 to 100%.")
        }
        if (limits.minTemp !in allTemps || limits.maxTemp !in allTemps || limits.minTemp > limits.maxTemp) {
            add("$name: the temperature limits ${celsius(limits.minTemp)} to ${celsius(limits.maxTemp)} must be in order, within ${celsius(allTemps.first)} to ${celsius(allTemps.last)}.")
        }
        if (profile.wakeSource.defaultWakeMinute !in MINUTE_OF_DAY) add("$name: the wake-up time of day is out of range.")
        if (profile.bedtimeMinute !in MINUTE_OF_DAY) add("$name: the bedtime time of day is out of range.")
        if (profile.days.any { it !in DAYS }) add("$name: the days of the week must be Monday to Sunday.")
    }

    private fun MutableList<String>.phase(phase: Phase, what: String) {
        if (phase.minutes !in PHASE_MINUTES) add("$what runs for ${phase.minutes} minutes; it must be 1 to $MAX_PHASE_MINUTES.")
        fan("$what: the fan", phase.fanPercent)
        temperature("$what: the temperature", phase.mode, phase.temperature)
    }

    private fun MutableList<String>.settings(settings: Settings) {
        addAll(settings.platform.validationErrors())
        settings.tonightChoice?.let { time("Tonight-only profile expiry", it.expiresAt) }
        settings.tonightOverride?.let {
            time("Tonight-only wake time", it.wakeAt)
            time("Tonight-only wake selection time", it.setAt)
        }
        if (settings.scheduledDarkMinute !in MINUTE_OF_DAY || settings.scheduledLightMinute !in MINUTE_OF_DAY)
            add("Scheduled theme times must be within the day.")
        if (settings.workingSlot !in SLOTS) add("The working slot is ${settings.workingSlot + 1}; it must be 1 to ${Limits.SLOTS}.")
        if (settings.boostMinutes !in BOOST_MINUTES) add("Boost lasts ${settings.boostMinutes} minutes; it must be 10 to 30.")
        if (settings.notificationActions.size > MAX_NOTIFICATION_ACTIONS) {
            add("The wake-up notification has ${settings.notificationActions.size} buttons; it can have up to $MAX_NOTIFICATION_ACTIONS.")
        }
        listOf(
            "Snooze warm" to settings.snoozeWarm, "Snooze cool" to settings.snoozeCool,
            "Extend warm" to settings.extendWarm, "Extend cool" to settings.extendCool,
        ).forEach { (what, preset) ->
            if (preset.minutes !in ACTION_MINUTES) add("$what runs for ${preset.minutes} minutes; it must be 1 to ${ACTION_MINUTES.last}.")
            fan("$what: the fan", preset.fanPercent)
            temperature("$what: the temperature", preset.mode, preset.temperature)
        }
    }

    private fun MutableList<String>.fan(what: String, percent: Int) {
        if (percent !in FAN) add("$what is $percent%; it must be 5 to 100%.")
    }

    /** Modes with a single fixed temperature (Turbo, Standby, Wait) ignore the stored one. */
    private fun MutableList<String>.temperature(what: String, mode: Mode, value: Int) {
        val range = mode.temperatureRange
        if (mode.usesTemperature && value !in range) {
            add("$what is ${celsius(value)}; ${mode.displayName} allows ${celsius(range.first)} to ${celsius(range.last)}.")
        }
    }

    private fun MutableList<String>.time(what: String, value: String) {
        if (runCatching { ZonedDateTime.parse(value) }.isFailure) add("$what \"$value\" isn't a date and time.")
    }

    private fun celsius(halfDegrees: Int) = "${halfDegrees / 2}${if (halfDegrees % 2 != 0) ".5" else ""} °C"
}
