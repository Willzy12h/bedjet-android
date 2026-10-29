package io.github.willzy12h.nocturne.ui

import android.app.TimePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.progressSemantics
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import io.github.willzy12h.nocturne.core.protocol.Mode
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
import io.github.willzy12h.nocturne.ui.NocturneButton as Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import io.github.willzy12h.nocturne.ui.NocturneOutlinedButton as OutlinedButton
import androidx.compose.material3.Text
import io.github.willzy12h.nocturne.ui.NocturneTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.night.boostDescription
import io.github.willzy12h.nocturne.night.boostLabel
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
import io.github.willzy12h.nocturne.night.AlarmExplanation
import io.github.willzy12h.nocturne.night.AlarmSettingsTarget
import io.github.willzy12h.nocturne.night.NightActions.Command
import kotlinx.coroutines.delay
import java.time.ZonedDateTime

/** Compact Tonight card, expandable plan details and the existing guarded quick controls. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TonightScreen(model: AppViewModel, modifier: Modifier, onEdit: (Profile) -> Unit, onOpenBackups: () -> Unit = {},
    onOpenAlarmSettings: () -> Unit = {}, onEditPhase: (Profile, EditorPhaseSelection) -> Unit = { p, _ -> onEdit(p) },
    onOpenRemote: () -> Unit = {}) {
    val profiles by model.profiles.collectAsState()
    val settings by model.settings.collectAsState()
    val running by model.activeNight.collectAsState()
    val alarm by model.nextAlarm.collectAsState()
    val busy by model.busy.collectAsState()
    val nightStatus by model.nightStatus.collectAsState()
    val runtime by model.runtime.collectAsState()
    var now by remember { mutableStateOf(model.now()) }
    val foreground = rememberScreenInteractive()
    LaunchedEffect(model, foreground) {
        if (foreground) {
            now = model.now(); model.refreshAlarm()
            while (true) { delay(30_000); now = model.now(); model.refreshAlarm() }
        }
    }
    val guard = rememberNightGuard(model)
    val context = LocalContext.current
    val pickerTokens = paletteTokens
    val choiceId = settings.resolveTonightChoice(now)?.profileId
    val scheduleWindow = if (now.hour < 12) now.toLocalDate().minusDays(1) else now.toLocalDate()
    var chosen by remember(choiceId, scheduleWindow, profiles) { mutableStateOf(settings.resolveProfile(profiles, now)) }
    val night = running
    val profile = if (night == null) chosen else profiles.firstOrNull { it.id == night.profileId }
    val preview = if (night == null) profile?.let { model.startPreview(it, now) } else null
    val plan = if (night == null) preview?.plan else remember(night, profiles) { model.activePlan() }
    val plannedWake = night?.wakeAt?.let { runCatching { ZonedDateTime.parse(it) }.getOrNull() } ?: preview?.wake?.time
    val wakeOverride = settings.resolveWakeOverride(now)
    val confirmed = night != null && night.confidence == Confidence.CONFIRMED && nightStatus.confidence == Confidence.CONFIRMED
    val overridable = night == null || (confirmed && !night.trackingManual && !night.isTestRun)
    val pickWake: () -> Unit = {
        if (overridable) {
            val current = model.now()
            val initial = settings.resolveWakeOverride(current)?.wakeAt?.let(ZonedDateTime::parse) ?: plannedWake ?: current
            val expectedNight = night?.identity
            val themed = android.view.ContextThemeWrapper(context, if (pickerTokens.dark) android.R.style.Theme_Material_Dialog_Alert else android.R.style.Theme_Material_Light_Dialog_Alert)
            TimePickerDialog(themed, { _, hour, minute ->
                model.setTonightWake(WakeResolver.nextOccurrence(hour * 60 + minute, model.now()), expectedNight)
            }, initial.hour, initial.minute, true).apply { show(); styleNativeDialog(this, pickerTokens) }
        }
    }
    val explanation = AlarmExplanation.resolve(settings, profile, now, alarm, night, runtime, nightStatus, previewWake = preview?.wake)
    val openExplanation = {
        when (explanation.target) {
            AlarmSettingsTarget.ALARM_SOURCES -> onOpenAlarmSettings()
            AlarmSettingsTarget.PROFILE_WAKE -> profile?.let(onEdit) ?: onOpenAlarmSettings()
            AlarmSettingsTarget.TONIGHT_WAKE -> if (overridable) pickWake() else onOpenAlarmSettings()
        }
    }
    val manualLabel = if (night?.trackingManual == true)
        stringResource(if (confirmed) R.string.tonight_manual_until else R.string.planned_end) else null
    val headline = tonightWakeHeadline(settings, profile, now, alarm, night)
    val wakeTime = headline.at?.let(Format::clock) ?: stringResource(R.string.ux_unknown_time)
    val editPhase: (Block, ZonedDateTime) -> Unit = phaseEdit@{ block, at ->
        val selected = profile ?: return@phaseEdit
        val displayedPlan = plan ?: return@phaseEdit
        // A changed profile, manual run, or trimmed Start cannot safely inherit a source index.
        val trimmedStart = night != null && block == Block.START &&
            (night.revision != 0 || runCatching { displayedPlan.start.isAfter(ZonedDateTime.parse(night.startedAt)) }.getOrDefault(true))
        val selection = if (night?.trackingManual == true || trimmedStart ||
            (night?.profile != null && night.profile != selected)) null
            else editorPhaseSelection(selected, displayedPlan, block, at)
        if (selection == null) onEdit(selected) else onEditPhase(selected, selection)
    }

    var showDetail by rememberSaveable { mutableStateOf(false) }
    var choosing by remember { mutableStateOf(false) }
    var editingQuick by remember { mutableStateOf(false) }
    val sending = rememberPresetSending()
    val tokens = paletteTokens
    val current = if (confirmed) plan?.stepAt(now) else null
    val profileName = profile?.name ?: night?.profile?.name ?: stringResource(R.string.tonights_night)

    Box(modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("tonight-content")
        .padding(horizontal = 16.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        StatusLine(model, runningName = profileName.takeIf { confirmed && night?.trackingManual == false }, onRemote = onOpenRemote)
        BluetoothBanner(model)
        HomeReading(model, settings, current?.let { (index, step) -> Triple(step, index + 1, plan?.steps?.size ?: 0) }, sending.value)
        SetupChecklistCard(model)

        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(tokens.surface)
            .then(tokens.edge?.let { Modifier.border(1.dp, it, RoundedCornerShape(18.dp)) } ?: Modifier)
            .padding(12.dp).testTag("tonight-card"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (night == null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SectionLabel(stringResource(R.string.parity_tonight_label, profileName),
                    Modifier.weight(1f).semantics { heading() })
                if (profiles.isNotEmpty()) AccentLink(stringResource(R.string.parity_change), { choosing = true },
                    Modifier.testTag("tonight-change"))
            }
            if (night != null) {
                if (settings.demoMode || !confirmed || night.trackingManual || night.isTestRun)
                    Text(profileName, color = tokens.ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                if (!confirmed || night.trackingManual) {
                    val state = stringResource(when (nightStatus.confidence ?: night.confidence) {
                        Confidence.PENDING -> R.string.tonight_state_pending
                        Confidence.FAILED -> R.string.tonight_state_failed
                        Confidence.UNKNOWN -> R.string.tonight_state_unknown
                        Confidence.CONFIRMED -> R.string.tonight_state_manual
                    })
                    Note(state + (nightStatus.reason?.let { " $it" } ?: ""))
                }
                if (nightStatus.confidence == Confidence.PENDING) {
                    val requested = night.pending?.takeUnless { it.kind == ChangeKind.TEMPORARY }?.requestedWake
                        ?: runtime.retime?.requestedWakeAt ?: runtime.retime?.alarmAt
                    val changing = requested != null || runtime.retime != null || runtime.alarmInbox != null ||
                        night.pending?.kind == ChangeKind.RETIME || night.pending?.kind == ChangeKind.REAPPLY
                    if (changing) Note(requested?.let { stringResource(R.string.wake_requested, Format.clock(it)) }
                        ?: stringResource(R.string.wake_requested_unknown))
                }
                if (night.profile != null && profile != null && profile != night.profile && night.pending?.kind != ChangeKind.REAPPLY)
                    Note(stringResource(R.string.tonight_edits_next_night, profile.name))
                night.temporary?.takeIf { confirmed && !night.trackingManual && now.isBefore(ZonedDateTime.parse(it.until)) }?.let {
                    Note(stringResource(R.string.temporary_planned, Format.clock(it.until)))
                }
            }
            val end = if (night != null) manualLabel?.let { label -> runCatching { PlanEnd(label, ZonedDateTime.parse(night.endsAt)) }.getOrNull() } else null
            plan?.let { p ->
                val summary = planSummary(p, settings.fahrenheit)
                val detail = stringResource(R.string.parity_show_detail)
                NightBar(p, now.takeIf { night != null && confirmed }, Modifier
                    .clickable(role = androidx.compose.ui.semantics.Role.Button, onClickLabel = detail) { showDetail = !showDetail }
                    .semantics { contentDescription = summary ?: profileName; stateDescription = if (showDetail) "" else detail }, minHeight = 48.dp)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    val tick = Modifier.weight(1f)
                    Tick(if (night != null) Format.clock(p.start) else stringResource(R.string.parity_bed, Format.clock(p.start)), tick, TextAlign.Start)
                    Tick(if (night != null && confirmed) stringResource(R.string.parity_now, Format.clock(now)) else summary.orEmpty(),
                        tick, TextAlign.Center)
                    // The wake source stays visible under the time: alarm, tonight-only or the profile's fallback.
                    Tick(end?.let { "${it.label} ${Format.clock(it.at)}" } ?: ((if (night != null) wakeTime else stringResource(R.string.parity_wake, wakeTime)) +
                        "\n" + stringResource(headline.label)),
                        tick.testTag("tonight-fixed-wake").then(if (overridable) Modifier.clickable(onClick = pickWake) else Modifier), TextAlign.End)
                }
                if (showDetail) PlanCard(p, settings, night?.wakeOrigin ?: preview?.wake?.origin ?: WakeOrigin.PROFILE_DEFAULT,
                    now = now.takeIf { night != null && confirmed }, end = end, middle = night?.profile?.middle ?: profile?.middle,
                    onEditPhase = if (profile == null) null else editPhase)
            } ?: if (night == null) Text(preview?.refusalReason.orEmpty(), color = MaterialTheme.colorScheme.onSurface) else Unit
            if (night == null) profile?.let { selected ->
                val later = plan?.start?.isAfter(now.plusMinutes(1)) == true
                BigButton(if (later) stringResource(R.string.tonight_start_at, Format.clock(plan!!.start)) else stringResource(R.string.tonight_start_now),
                    enabled = !busy && plan != null, onClick = { model.run(Command.START_NIGHT, selected.id) })
            } else {
                plan?.steps?.getOrNull((current?.first ?: -1) + 1)?.takeIf { confirmed }?.let { next ->
                    val stripe = tokens.stepColor(next.step.mode, next.step.temperature)
                    StripeTile(stripe = stripe, title = stringResource(R.string.parity_next, modeName(next.step.mode),
                            if (next.step.mode.usesTemperature) tempText(next.step.temperature, settings.fahrenheit) else "",
                            Format.clock(next.startsAt)).trimEnd(), subtitle = wakeOriginLabel(night.wakeOrigin, WakeLabelStyle.HEADLINE),
                        titleFontWeight = FontWeight.Bold, level = TintLevel.ON, modifier = Modifier.fillMaxWidth().testTag("tonight-next"),
                        icon = { Box(Modifier.size(32.dp).clip(RoundedCornerShape(9.dp)).background(stripe.copy(alpha = .22f)),
                            contentAlignment = Alignment.Center) {
                            Icon(painterResource(modeIcon(next.step.mode)), null, Modifier.size(16.dp), tint = stripe)
                        } })
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val pill = ButtonDefaults.buttonColors(containerColor = tokens.surface2, contentColor = tokens.ink)
                    if (overridable) Button(onClick = pickWake, colors = pill, shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.parity_change_wake), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                    val turnOff = stringResource(R.string.guard_turn_off)
                    Button(onClick = { guard.confirm(turnOff) { model.run(Command.END_NIGHT) } }, colors = pill, shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("tonight-end-night")) {
                        Text(stringResource(R.string.parity_end_night), fontSize = 14.sp,
                            color = if (tokens.dark) Color(0xFFFFB3A8) else tokens.bad, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        // Long fallback explanations must remain readable without consuming the controls' viewport.
        headline.reason?.let { Note(stringResource(it)) }
        AlarmExplanationLine(explanation, openExplanation)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (wakeOverride == null && night == null && overridable) TextButton(onClick = pickWake) { Text(stringResource(R.string.tonight_set_wake)) }
            if (wakeOverride != null && overridable) TextButton(onClick = { model.setTonightWake(null) }) {
                Text(stringResource(R.string.tonight_clear_wake))
            }
            if (settings.resolveTonightChoice(now) != null) TextButton(onClick = { model.updateSettings { it.followSchedule() } }) {
                Text(stringResource(R.string.tonight_follow_schedule))
            }
        }
        wakeOverride?.let { override ->
            runCatching { ZonedDateTime.parse(override.expiresAt) }.getOrNull()?.let { lapses ->
                Note(stringResource(R.string.override_expiry, Format.clock(lapses), lapses.format(DateTimeFormatter.ofPattern("EEE d MMM"))))
            }
        }
        NightRecoveryControls(model, night, guard, now)

        QuickPresets(model, guard, sending) { editingQuick = true }

        val unreadable by model.unreadable.collectAsState()
        val restoreUnfinished by model.restoreUnfinished.collectAsState()
        val originalsBlocked by model.originalsBlocked.collectAsState()
        val banner = UnreadableBannerState(unreadable, restoreUnfinished, originalsBlocked)
        UnreadableDataBanner(banner, onDismiss = model::dismissUnreadable,
            onRestore = onOpenBackups.takeIf { banner.offersRestore }, onAcceptLoss = model::acceptLostOriginals)
        runtime.unresolvedSlotWrite?.takeIf { DeviceId.same(it.deviceId, settings.deviceId()) }?.let {
            SlotWriteNotice(it, onChecked = model::acknowledgeSlotWrite)
        }
        SetupChecks()
        BedJetPicker(model, compact = true)
    }

    BusyIndicator(model)
    }

    if (choosing) AlertDialog(onDismissRequest = { choosing = false },
        title = { Text(stringResource(R.string.parity_choose_profile)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                profiles.forEach { p ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable {
                        chosen = p; model.updateSettings { it.chooseTonight(p.id, model.now()) }; choosing = false
                    }.testTag("tonight-choose-${p.id}"), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = profile?.id == p.id, onClick = null, colors = nocturneRadioColors())
                        Text(p.name, Modifier.padding(start = 12.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { choosing = false }) { Text(stringResource(R.string.uat_quick_cancel)) } },
        dismissButton = { profile?.let { TextButton(onClick = { choosing = false; onEdit(it) }) { Text(stringResource(R.string.action_edit)) } } })

    if (editingQuick) AlertDialog(onDismissRequest = { editingQuick = false },
        title = { Text(stringResource(R.string.parity_quick_presets)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            QuickDefaultsSection(model, settings)
        } },
        confirmButton = { TextButton(onClick = { editingQuick = false }) { Text(stringResource(R.string.uat_setup_done)) } })
}

/** Compact visual ticks with a complete, readable wake source and a 48 dp tap area. */
@Composable
private fun Tick(text: String, modifier: Modifier, align: TextAlign) {
    Box(modifier.heightIn(min = 48.dp), contentAlignment = when (align) {
        TextAlign.Start -> Alignment.CenterStart
        TextAlign.End -> Alignment.CenterEnd
        else -> Alignment.Center
    }) { Text(text, color = paletteTokens.ink2, fontSize = 10.sp, textAlign = align) }
}

/** The large reading and its context are based only on the current BedJet receipt; a running night adds its step. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HomeReading(model: AppViewModel, settings: Settings,
    step: Triple<io.github.willzy12h.nocturne.core.plan.PlannedStep, Int, Int>? = null, sending: PresetSending? = null) {
    val status by model.status.collectAsState()
    val live by model.hasLiveStatus.collectAsState()
    val link by model.linkState.collectAsState()
    val tokens = paletteTokens
    val large = LocalConfiguration.current.fontScale >= 1.5f
    val reading = status.takeIf { live && link == LinkState.READY }
    if (reading == null) {
        Text(stringResource(R.string.home_reading_unavailable),
            modifier = Modifier.testTag("home-reading-context"), color = tokens.ink2,
            style = MaterialTheme.typography.bodyMedium)
    } else {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(tempText(reading.actualTemperature, settings.fahrenheit),
                modifier = Modifier.testTag("home-temperature"), style = MaterialTheme.typography.displayLarge,
                fontSize = if (large) 40.sp else 64.sp, lineHeight = if (large) 48.sp else 68.sp,
                fontWeight = FontWeight.Bold, color = tokens.ink)
            Column(Modifier.align(Alignment.CenterVertically)) {
                Text(stringResource(R.string.home_air_from_bedjet), modifier = Modifier.testTag("home-reading-context"),
                    color = tokens.ink2, fontSize = 11.sp)
                if (sending != null) {
                    Text("${sending.title}…", modifier = Modifier.testTag("home-sending"), color = tokens.readableText(sending.color),
                        fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.parity_waiting_bedjet), color = tokens.ink2, fontSize = 11.sp)
                } else if (reading.mode == Mode.STANDBY) Text(stringResource(R.string.home_idle), color = tokens.ink2,
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(painterResource(modeIcon(reading.mode)), null, Modifier.size(16.dp),
                        tint = paletteTokens.stepColor(reading.mode, reading.targetTemperature))
                    Text(modeName(reading.mode) + if (reading.mode.usesTemperature) " " + tempText(reading.targetTemperature, settings.fahrenheit) else "",
                    color = tokens.readableText(tokens.stepColor(reading.mode, reading.targetTemperature)), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                if (sending == null && step != null) Text(stringResource(R.string.parity_fan_step, reading.fanPercent, step.second, step.third),
                    color = tokens.ink2, fontSize = 11.sp)
                else if (sending == null && reading.mode.usesFan) Text(stringResource(R.string.timeline_fan, reading.fanPercent),
                    color = tokens.ink2, fontSize = 11.sp)
            }
        }
    }
}

/** Where a wake label sits: the dark headline, the light palette tile, or simple view's final timeline row. */
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
        WakeOrigin.AUTO_START -> R.string.wake_origin_auto_start
    })
}

/** The night itself: the bar curve, or in simple view the step list. */
@Composable
private fun PlanCard(plan: Plan, settings: Settings, origin: WakeOrigin, now: ZonedDateTime?, end: PlanEnd? = null,
    middle: io.github.willzy12h.nocturne.core.plan.Middle? = null, onEditPhase: ((Block, ZonedDateTime) -> Unit)? = null) {
    Card(Modifier.fillMaxWidth(), colors = surfaceCard(), border = cardBorder()) {
        Column(Modifier.padding(16.dp)) {
            if (settings.simpleView) {
                NightTimeline(plan, origin, settings.fahrenheit, now = now, end = end, middle = middle,
                    onEditPhase = onEditPhase?.let { edit -> { entry -> edit(entry.block, entry.startsAt) } })
            } else {
                Text(stringResource(R.string.tonight_the_night), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
                NightCurve(plan, now = now, legend = !nocturneColors.dark, end = end, phaseInteraction = true,
                    onEditPhase = onEditPhase?.let { edit -> { step -> edit(step.block, step.startsAt) } })
                if (end == null) Text(wakeOriginLabel(origin, WakeLabelStyle.TILE),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

/** The one primary action on the screen: full width, at least 56 dp, 16 dp corners. */
@Composable
internal fun BigButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = 52.dp),
        shape = RoundedCornerShape(16.dp),
    ) { Text(text) }
}

@Composable
private fun Note(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun surfaceCard() = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer, contentColor = MaterialTheme.colorScheme.onSurface)

@Composable
private fun cardBorder() = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)

@Composable
fun blockName(block: Block): String = stringResource(when (block) {
    Block.TEMPORARY -> R.string.temporary_block
    Block.START -> R.string.block_start
    Block.MIDDLE -> R.string.block_middle
    Block.WAKE -> R.string.block_wake
    Block.AFTER_WAKE -> R.string.block_after_wake
})
