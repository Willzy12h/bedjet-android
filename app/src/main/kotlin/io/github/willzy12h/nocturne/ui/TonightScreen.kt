package io.github.willzy12h.nocturne.ui

import android.app.TimePickerDialog
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.night.WakeOrigin
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.willzy12h.nocturne.core.link.LinkState
import io.github.willzy12h.nocturne.core.link.DeviceId
import io.github.willzy12h.nocturne.core.night.Confidence
import io.github.willzy12h.nocturne.core.night.ChangeKind
import java.time.format.DateTimeFormatter
import io.github.willzy12h.nocturne.core.plan.Block
import io.github.willzy12h.nocturne.core.plan.Plan
import io.github.willzy12h.nocturne.core.plan.Profile
import io.github.willzy12h.nocturne.core.plan.WakeResolver
import io.github.willzy12h.nocturne.core.store.Settings
import io.github.willzy12h.nocturne.core.store.deviceId
import io.github.willzy12h.nocturne.night.NightActions.Command
import kotlinx.coroutines.delay
import java.time.ZonedDateTime

/**
 * Tonight, in three layouts over the same parts: Midnight Ink (dark) leads with a big wake time; Daylight (light)
 * puts start and wake in two stat tiles and labels the curve; simple view swaps the curve for a list of steps.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TonightScreen(model: AppViewModel, modifier: Modifier, onEdit: (Profile) -> Unit, onOpenBackups: () -> Unit = {}) {
    val profiles by model.profiles.collectAsState()
    val settings by model.settings.collectAsState()
    val night by model.activeNight.collectAsState()
    val alarm by model.nextAlarm.collectAsState()
    val busy by model.busy.collectAsState()
    val release by model.release.collectAsState()
    val nightStatus by model.nightStatus.collectAsState()
    var now by remember { mutableStateOf(model.now()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = model.now(); model.refreshAlarm() } }
    val guard = rememberNightGuard(model)
    val context = LocalContext.current
    val dark = nocturneColors.dark
    val simple = settings.simpleView

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.tonight_title), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
        ConnectionLine(model)
        BusyIndicator(model)
        val unreadable by model.unreadable.collectAsState()
        val restoreUnfinished by model.restoreUnfinished.collectAsState()
        val originalsBlocked by model.originalsBlocked.collectAsState()
        val bannerState = UnreadableBannerState(unreadable, restoreUnfinished, originalsBlocked)
        UnreadableDataBanner(bannerState, onDismiss = model::dismissUnreadable,
            onRestore = onOpenBackups.takeIf { bannerState.offersRestore }, onAcceptLoss = model::acceptLostOriginals)
        val runtime by model.runtime.collectAsState()
        runtime.unresolvedSlotWrite?.takeIf { DeviceId.same(it.deviceId, settings.deviceId()) }?.let { write ->
            SlotWriteNotice(write, onChecked = model::acknowledgeSlotWrite)
        }
        SetupChecks()
        if (settings.deviceId() == null) SetupFlow(model, compact = true)
        release?.let { latest ->
            val uri = LocalUriHandler.current
            AlertDialog(
                onDismissRequest = model::dismissRelease,
                title = { Text(stringResource(R.string.notify_update_title, latest.tag)) },
                text = { Text(latest.notes.ifBlank { stringResource(R.string.tonight_release_text) }) },
                confirmButton = { TextButton(onClick = { uri.openUri(latest.apkUrl ?: latest.pageUrl); model.dismissRelease() }) { Text(stringResource(R.string.tonight_release_download)) } },
                dismissButton = { TextButton(onClick = model::dismissRelease) { Text(stringResource(R.string.tonight_release_later)) } },
            )
        }

        val running = night
        var plannedWake = running?.wakeAt?.let { runCatching { ZonedDateTime.parse(it) }.getOrNull() }
        if (running != null) {
            val plan = remember(running, profiles) { model.activePlan() }
            val profile = profiles.firstOrNull { it.id == running.profileId }
            val startedAt = runCatching { ZonedDateTime.parse(running.startedAt) }.getOrNull()
            val confirmed = running.confidence == Confidence.CONFIRMED && nightStatus.confidence == Confidence.CONFIRMED
            Text(profile?.name ?: stringResource(R.string.tonights_night), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!confirmed || running.trackingManual) {
                val state = stringResource(when (nightStatus.confidence ?: running.confidence) {
                    Confidence.PENDING -> R.string.tonight_state_pending
                    Confidence.FAILED -> R.string.tonight_state_failed
                    Confidence.UNKNOWN -> R.string.tonight_state_unknown
                    Confidence.CONFIRMED -> R.string.tonight_state_manual
                })
                Note(state + (nightStatus.reason?.let { " $it" } ?: ""))
            }
            // Pending versus confirmed (U10): the headline keeps the wake the BedJet runs; the request is shown beside it.
            if (nightStatus.confidence == Confidence.PENDING) {
                val requested = running.pending?.requestedWake ?: runtime.retime?.alarmAt
                val wakeChange = requested != null || runtime.retime != null || runtime.alarmInbox != null ||
                    running.pending?.kind == ChangeKind.RETIME || running.pending?.kind == ChangeKind.REAPPLY
                if (wakeChange) Note(requested?.let { stringResource(R.string.wake_requested, Format.clock(it)) }
                    ?: stringResource(R.string.wake_requested_unknown))
            }
            // "Next night" edits don't touch tonight; say so while the saved profile differs from tonight's snapshot.
            val live = profiles.firstOrNull { it.id == running.profileId }
            // Not while "Update tonight" is applying those very edits to tonight.
            if (running.profile != null && live != null && live != running.profile && running.pending?.kind != ChangeKind.REAPPLY) Note(stringResource(R.string.tonight_edits_next_night, live.name))
            // A kept manual run has no wake programme: it runs until its own end, so that is what is shown.
            val manualUntil = if (!running.trackingManual) null
                else stringResource(if (confirmed) R.string.tonight_manual_until else R.string.planned_end)
            when {
                simple -> Unit // The timeline below ends with the wake time.
                manualUntil != null && dark -> WakeHeadline(manualUntil, Format.clock(running.endsAt), null)
                manualUntil != null -> StatTiles(startedAt?.let(Format::clock) ?: "--:--", stringResource(if (confirmed) R.string.tonight_started else R.string.tonight_planned_start),
                    Format.clock(running.endsAt), null, waking = confirmed, planned = !confirmed, wakeLabel = manualUntil)
                dark -> WakeHeadline(stringResource(if (confirmed) R.string.tonight_waking_at else R.string.tonight_planned_wake), Format.clock(running.wakeAt), running.wakeOrigin)
                else -> StatTiles(startedAt?.let(Format::clock) ?: "--:--", stringResource(if (confirmed) R.string.tonight_started else R.string.tonight_planned_start), Format.clock(running.wakeAt), running.wakeOrigin, waking = confirmed, planned = !confirmed)
            }
            if (confirmed) plan?.stepAt(now)?.second?.let { step ->
                Text(
                    stringResource(R.string.tonight_now, blockName(step.block), modeName(step.step.mode), (step.step.fanStep + 1) * 5),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            // Mid-night the controls come before the plan: they're what you reach for, and up here the
            // "night started" snackbar at the bottom of the screen doesn't cover them.
            if (confirmed) {
                val turnOff = stringResource(R.string.guard_turn_off)
                BigButton(stringResource(R.string.tonight_end_night), onClick = { guard.confirm(turnOff) { model.run(Command.END_NIGHT) } })
            }
            val end = manualUntil?.let { label -> runCatching { PlanEnd(label, ZonedDateTime.parse(running.endsAt)) }.getOrNull() }
            plan?.let { PlanCard(it, settings, running.wakeOrigin, now = now, end = end) }
        } else {
            val choiceId = settings.resolveTonightChoice(now)?.profileId
            val scheduleWindow = if (now.hour < 12) now.toLocalDate().minusDays(1) else now.toLocalDate()
            var chosen by remember(choiceId, scheduleWindow, profiles) { mutableStateOf(settings.resolveProfile(profiles, now)) }
            val profile = chosen
            val preview = profile?.let { model.startPreview(it, now) }
            val wake = preview?.wake
            plannedWake = wake?.time
            if (wake != null && !simple) {
                if (dark) WakeHeadline(stringResource(R.string.tonight_wakes_at), Format.clock(wake.time), wake.origin)
                else StatTiles(Format.clock(now), stringResource(R.string.tonight_starts), Format.clock(wake.time), wake.origin, waking = false)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                profiles.forEach { p ->
                    FilterChip(
                        selected = profile?.id == p.id,
                        onClick = { chosen = p; model.updateSettings { it.chooseTonight(p.id, model.now()) } },
                        label = { Text(p.name) },
                    )
                }
                profile?.let { TextButton(onClick = { onEdit(it) }) { Text(stringResource(R.string.action_edit)) } }
            }
            profile?.let {
                alarm?.let { next ->
                    if (!next.counts) Note(stringResource(R.string.tonight_alarm_not_clock, Format.clock(next.time), next.creator.toString()))
                } ?: if (it.wakeSource.useAlarm && wake?.origin != WakeOrigin.OVERRIDE) Note(stringResource(R.string.tonight_no_alarm)) else Unit
                preview?.plan?.let { plan -> PlanCard(plan, settings, wake?.origin ?: WakeOrigin.PROFILE_DEFAULT, now = null) }
                    ?: Text(preview?.refusalReason.orEmpty(), color = MaterialTheme.colorScheme.error)
                BigButton(stringResource(R.string.tonight_start_now), enabled = !busy && preview?.plan != null, onClick = { model.run(Command.START_NIGHT, it.id) })
            }
        }

        val wakeOverride = settings.resolveWakeOverride(now)
        // The command owner refuses a tonight-only wake for a night that isn't confirmed or runs manually.
        val overridable = night.let { it == null || (it.confidence == Confidence.CONFIRMED && nightStatus.confidence == Confidence.CONFIRMED && !it.trackingManual) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (overridable) OutlinedButton(onClick = {
                val current = model.now()
                val initial = settings.resolveWakeOverride(current)?.wakeAt?.let(ZonedDateTime::parse)
                    ?: plannedWake ?: current
                TimePickerDialog(context, { _, hour, minute ->
                    val wake = WakeResolver.nextOccurrence(hour * 60 + minute, model.now())
                    model.setTonightWake(wake)
                }, initial.hour, initial.minute, true).show()
            }) { Text(if (wakeOverride == null) stringResource(R.string.tonight_set_wake) else stringResource(R.string.tonight_change_wake, Format.clock(wakeOverride.wakeAt))) }
            if (wakeOverride != null && overridable) TextButton(onClick = { model.setTonightWake(null) }) { Text(stringResource(R.string.tonight_clear_wake)) }
            if (settings.resolveTonightChoice(now) != null) TextButton(onClick = { model.updateSettings { it.followSchedule() } }) {
                Text(stringResource(R.string.tonight_follow_schedule))
            }
        }
        wakeOverride?.let { override ->
            val lapses = runCatching { ZonedDateTime.parse(override.expiresAt) }.getOrNull()
            if (lapses != null) Note(stringResource(R.string.override_expiry, Format.clock(lapses), lapses.format(DateTimeFormatter.ofPattern("EEE d MMM"))))
        }
        NightRecoveryControls(model, night, guard, now)
        QuickButtons(model, settings, guard)
    }
}

/** What the command owner is doing: the running command and how many wait behind it, from any origin (U07). */
@Composable
fun BusyIndicator(model: AppViewModel) {
    val local by model.busy.collectAsState()
    val owner by model.commandBusy.collectAsState()
    if (!local && owner.runningLabel == null && owner.queuedCount == 0) return
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        val running = owner.runningLabel?.let { stringResource(R.string.busy_running, it) } ?: stringResource(R.string.busy_waiting)
        val queued = owner.queuedCount.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.busy_queued, it, it) }
        Text(listOfNotNull(running, queued).joinToString(" · "), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Where a wake label sits: Midnight Ink's headline, Daylight's tile, or simple view's final timeline row. */
enum class WakeLabelStyle { HEADLINE, TILE, TIMELINE }

/**
 * Labels where tonight's wake time comes from in all four looks, so an override is never called
 * the profile's time or the alarm's.
 */
@Composable
fun wakeOriginLabel(origin: WakeOrigin, style: WakeLabelStyle): String {
    val configuration = LocalConfiguration.current
    val compactTimeline = style == WakeLabelStyle.TIMELINE &&
        configuration.screenWidthDp <= 320 && configuration.fontScale >= 2f
    return stringResource(when (origin) {
        WakeOrigin.ALARM -> if (style == WakeLabelStyle.HEADLINE) R.string.wake_origin_alarm_headline else R.string.wake_origin_alarm
        WakeOrigin.OVERRIDE -> if (compactTimeline) R.string.wake_origin_override_compact else R.string.wake_origin_override
        WakeOrigin.PROFILE_DEFAULT -> if (style == WakeLabelStyle.TIMELINE) R.string.wake_time else R.string.wake_origin_profile
    })
}

/** Midnight Ink's lead: the wake time, large. */
@Composable
private fun WakeHeadline(label: String, time: String, origin: WakeOrigin?) {
    Column(Modifier.semantics(mergeDescendants = true) {}) {
        Text(label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(time, fontSize = 52.sp, lineHeight = 56.sp, fontWeight = FontWeight.Light, color = MaterialTheme.colorScheme.onSurface)
        if (origin != null) Text(wakeOriginLabel(origin, WakeLabelStyle.HEADLINE), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Daylight's lead: start and wake side by side. */
@Composable
private fun StatTiles(startTime: String, startLabel: String, wakeTime: String, origin: WakeOrigin?, waking: Boolean, planned: Boolean = false,
    wakeLabel: String? = null) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StatTile(startLabel, startTime, null, Modifier.weight(1f))
        StatTile(wakeLabel ?: stringResource(if (planned) R.string.tonight_planned_wake else if (waking) R.string.tonight_waking_at else R.string.tonight_wakes_at), wakeTime,
            origin?.let { wakeOriginLabel(it, WakeLabelStyle.TILE) }, Modifier.weight(1f))
    }
}

@Composable
private fun StatTile(label: String, value: String, note: String?, modifier: Modifier) {
    Card(modifier.semantics(mergeDescendants = true) {}, colors = surfaceCard(), border = cardBorder()) {
        Column(Modifier.padding(16.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/** The night itself: the bar curve, or in simple view the step list. */
@Composable
private fun PlanCard(plan: Plan, settings: Settings, origin: WakeOrigin, now: ZonedDateTime?, end: PlanEnd? = null) {
    Card(Modifier.fillMaxWidth(), colors = surfaceCard(), border = cardBorder()) {
        Column(Modifier.padding(16.dp)) {
            if (settings.simpleView) {
                NightTimeline(plan, origin, settings.fahrenheit, now = now, end = end)
            } else {
                Text(stringResource(R.string.tonight_the_night), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
                NightCurve(plan, now = now, legend = !nocturneColors.dark, end = end)
            }
        }
    }
}

/** The one primary action on the screen: full width, at least 56 dp, 16 dp corners. */
@Composable
private fun BigButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        shape = RoundedCornerShape(16.dp),
    ) { Text(text, style = MaterialTheme.typography.titleMedium) }
}

/** Four one-tap actions in a row, each marked with the colour of what it does. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickButtons(model: AppViewModel, settings: Settings, guard: NightGuard) {
    val colors = nocturneColors
    Text(stringResource(R.string.tonight_quick), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
    val startBoost = stringResource(R.string.guard_start_boost)
    val startWarming = stringResource(R.string.guard_start_warming)
    val startCooling = stringResource(R.string.guard_start_cooling)
    val turnOff = stringResource(R.string.guard_turn_off)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), maxItemsInEachRow = 2) {
        QuickButton(stringResource(R.string.feature_boost), stringResource(R.string.minutes_short, settings.boostMinutes), colors.turbo, Modifier.weight(1f)) { guard.confirm(startBoost) { model.run(Command.BOOST, replacementConfirmed = true) } }
        QuickButton(stringResource(R.string.feature_warm), null, colors.heat, Modifier.weight(1f)) { guard.confirm(startWarming) { model.run(Command.WARM, replacementConfirmed = true) } }
        QuickButton(stringResource(R.string.feature_cool), null, colors.cool, Modifier.weight(1f)) { guard.confirm(startCooling) { model.run(Command.COOL, replacementConfirmed = true) } }
        QuickButton(stringResource(R.string.feature_off), null, colors.idle, Modifier.weight(1f)) { guard.confirm(turnOff) { model.run(Command.OFF) } }
    }
}

@Composable
private fun QuickButton(label: String, detail: String?, dot: Color, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 72.dp),
        shape = RoundedCornerShape(16.dp),
        contentPadding = PaddingValues(4.dp),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainer, contentColor = MaterialTheme.colorScheme.onSurface),
        border = quickButtonBorder(MaterialTheme.colorScheme),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Dot(dot, 10)
            Text(label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
            detail?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun Note(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun surfaceCard() = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer, contentColor = MaterialTheme.colorScheme.onSurface)

@Composable
private fun cardBorder() = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)

@Composable
fun ConnectionLine(model: AppViewModel) {
    val state by model.linkState.collectAsState()
    val status by model.status.collectAsState()
    val settings by model.settings.collectAsState()
    val colors = nocturneColors
    val text = when (state) {
        LinkState.READY -> status?.let { stringResource(R.string.connection_status, modeName(it.mode), tempText(it.ambientTemperature, settings.fahrenheit),
            tempText(it.actualTemperature, settings.fahrenheit)) } ?: stringResource(R.string.connection_ready)
        LinkState.CONNECTING -> stringResource(R.string.connection_connecting)
        LinkState.SEARCHING -> stringResource(R.string.connection_searching)
        LinkState.BLUETOOTH_OFF -> stringResource(R.string.connection_bluetooth_off)
        LinkState.UNAUTHORIZED -> stringResource(R.string.connection_unauthorized)
    }
    // The dot repeats the text's meaning in colour; the label still says it in words.
    val dot = when (state) {
        LinkState.READY -> colors.connected
        LinkState.CONNECTING -> colors.heat
        else -> colors.idle
    }
    AssistChip(
        onClick = model::reconnect,
        label = { Text(if (settings.demoMode) stringResource(R.string.connection_demo, text) else text) },
        leadingIcon = { Dot(dot, 8) },
    )
}

@Composable
fun blockName(block: Block): String = stringResource(when (block) {
    Block.START -> R.string.block_start
    Block.MIDDLE -> R.string.block_middle
    Block.WAKE -> R.string.block_wake
    Block.AFTER_WAKE -> R.string.block_after_wake
})
