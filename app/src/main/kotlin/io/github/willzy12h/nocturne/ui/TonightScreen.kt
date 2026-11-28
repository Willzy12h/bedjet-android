package io.github.willzy12h.nocturne.ui

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.app.TimePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.rotate
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
import io.github.willzy12h.nocturne.core.store.QuickDefaults
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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
import io.github.willzy12h.nocturne.night.isStrayAlarm
import io.github.willzy12h.nocturne.night.NightActions.Command
import io.github.willzy12h.nocturne.night.NightActions.PhoneOffConfirmation
import kotlinx.coroutines.delay
import java.time.ZonedDateTime

/** Compact Tonight card, expandable plan details and the existing guarded quick controls. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TonightScreen(model: AppViewModel, modifier: Modifier, onEdit: (Profile) -> Unit, onOpenBackups: () -> Unit = {},
    onOpenAlarmSettings: () -> Unit = {}, onEditPhase: (Profile, EditorPhaseSelection) -> Unit = { p, _ -> onEdit(p) },
    onOpenRemote: () -> Unit = {}, onOpenSleep: () -> Unit = {}, onOpenSchedule: () -> Unit = {}) {
    val profiles by model.profiles.collectAsStateWithLifecycle()
    val settings by model.settings.collectAsStateWithLifecycle()
    val running by model.activeNight.collectAsStateWithLifecycle()
    val alarm by model.nextAlarm.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val nightStatus by model.nightStatus.collectAsStateWithLifecycle()
    val runtime by model.runtime.collectAsStateWithLifecycle()
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
    val tonightChoice = settings.resolveTonightChoice(now)?.takeIf { choice -> profiles.any { it.id == choice.profileId } }
    val chosen = settings.resolveProfile(profiles, now)
    val night = running
    val runningEvidence = rememberTonightRunningEvidence(model, night, nightStatus, settings, now)
    val showRunningFacts = night?.isTestRun == false && runningEvidence.confirmedPlan?.end?.isAfter(now) == true
    val reading by model.status.collectAsStateWithLifecycle()
    val live by model.hasLiveStatus.collectAsStateWithLifecycle()
    val target = settings.deviceId()
    var offConfirmation by remember { mutableStateOf<PhoneOffConfirmation?>(null) }
    LaunchedEffect(model) {
        model.phoneOffConfirmed.collect { confirmation ->
            if (model.runtime.value.generation == confirmation.generation && model.activeNight.value == null &&
                DeviceId.same(confirmation.deviceId, model.settings.value.deviceId())) {
                offConfirmation = confirmation
            }
        }
    }
    val showOffConfirmation = offConfirmation?.let {
        night == null && runtime.generation == it.generation && DeviceId.same(it.deviceId, target)
    } == true
    LaunchedEffect(night?.identity, target, runtime.generation, live, reading?.mode) {
        if (!showOffConfirmation || (live && reading?.mode?.let { it != Mode.STANDBY } == true)) offConfirmation = null
    }
    val profile = if (night == null) chosen else profiles.firstOrNull { !night.isNap && it.id == night.profileId }
    val preview = if (night == null) profile?.let { model.startPreview(it, now) } else null
    val plan = if (night == null) preview?.plan else remember(night, profiles) { model.activePlan() }
    val plannedWake = night?.wakeAt?.let { runCatching { ZonedDateTime.parse(it) }.getOrNull() } ?: preview?.wake?.time
    val wakeOverride = settings.resolveWakeOverride(now)
    val confirmed = night != null && night.confidence == Confidence.CONFIRMED && nightStatus.confidence == Confidence.CONFIRMED
    val overridable = night == null || (confirmed && !night.trackingManual && !night.isTestRun && !night.isNap)
    // [then] runs after the chosen wake is saved, so "choose a wake and start" is one step for the user.
    val pickWakeThen: (then: (() -> Unit)?) -> Unit = { then ->
        if (overridable) {
            val current = model.now()
            val initial = settings.resolveWakeOverride(current)?.wakeAt?.let(ZonedDateTime::parse) ?: plannedWake ?: current
            val expectedNight = night?.identity
            val themed = android.view.ContextThemeWrapper(context, if (pickerTokens.dark) android.R.style.Theme_Material_Dialog_Alert else android.R.style.Theme_Material_Light_Dialog_Alert)
            TimePickerDialog(themed, { _, hour, minute ->
                model.setTonightWake(WakeResolver.nextOccurrence(hour * 60 + minute, model.now()), expectedNight,
                    onFinished = then ?: {})
            }, initial.hour, initial.minute, Format.twentyFourHour).apply { show(); styleNativeDialog(this, pickerTokens) }
        }
    }
    val pickWake: () -> Unit = { pickWakeThen(null) }
    // A night that would be too long (usually a daytime start) can still start now once it has a wake time that fits.
    val needsWake = night == null && plan == null && preview?.durationProblem != null && overridable
    var nightWakeOffer by rememberSaveable { mutableStateOf(false) }
    val explanation = AlarmExplanation.resolve(settings, profile, now, alarm, night, runtime, nightStatus, previewWake = preview?.wake)
    val clockApps by model.clockApps.collectAsStateWithLifecycle()
    val calendarApps = remember { io.github.willzy12h.nocturne.night.ClockApps.calendarApps(context) }
    val showAlarmRow = night?.isNap != true && night?.isTestRun != true && !explanation.isStrayAlarm(clockApps, calendarApps)
    var choosingAlarmApps by remember { mutableStateOf(false) }
    if (choosingAlarmApps) AlarmAppsDialog(model, onDismiss = { choosingAlarmApps = false }) {
        // The other way out of an unwanted alarm: this profile's own wake time, or a time for tonight only.
        profile?.let { p -> AlarmAppsAction(stringResource(R.string.alarm_apps_fixed, p.name)) { choosingAlarmApps = false; onEdit(p) } }
        if (overridable) AlarmAppsAction(stringResource(R.string.alarm_apps_tonight)) { choosingAlarmApps = false; pickWake() }
    }
    var missingAlarm by remember { mutableStateOf(false) }
    if (missingAlarm) MissingAlarmDialog(explanation, onDismiss = { missingAlarm = false },
        onSetTonight = pickWake.takeIf { overridable }, onFixedWake = profile?.let { p -> { onEdit(p) } },
        onChooseAlarmApps = { choosingAlarmApps = true },
        onUseUnnamed = explanation.unnamedOffer?.takeIf { overridable }?.let { offer -> { model.setTonightWake(offer, night?.identity) } })
    val openExplanation = {
        when (explanation.target) {
            AlarmSettingsTarget.ALARM_SOURCES -> { choosingAlarmApps = true }
            AlarmSettingsTarget.MISSING_ALARM -> { missingAlarm = true }
            AlarmSettingsTarget.PROFILE_WAKE -> profile?.let(onEdit) ?: onOpenAlarmSettings()
            AlarmSettingsTarget.TONIGHT_WAKE -> if (overridable) pickWake() else onOpenAlarmSettings()
        }
    }
    val manualLabel = if (night?.trackingManual == true)
        stringResource(if (confirmed) R.string.tonight_manual_until else R.string.planned_end) else null
    val phoneAlarm = if (night?.isNap == true) null else acceptedPhoneAlarm(settings, now, alarm, night)
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
    var choosing by rememberSaveable { mutableStateOf(false) }
    var choiceDraft by rememberSaveable { mutableStateOf<String?>(null) }
    var editingQuick by remember { mutableStateOf(false) }
    val presetEditingRequested by model.presetEditingRequested.collectAsStateWithLifecycle()
    LaunchedEffect(presetEditingRequested) {
        if (presetEditingRequested) { editingQuick = true; model.presetEditingRequested.value = false }
    }
    var editingPreset by remember { mutableStateOf<Mode?>(null) }
    var editingBoost by remember { mutableStateOf(false) }
    val sending = rememberPresetSending()
    val tokens = paletteTokens
    val restoreOffered by model.restoreOffer.collectAsStateWithLifecycle()
    val fullSetup by model.setupChecklistOpen.collectAsStateWithLifecycle()
    val unreadable by model.unreadable.collectAsStateWithLifecycle()
    val restoreUnfinished by model.restoreUnfinished.collectAsStateWithLifecycle()
    val originalsBlocked by model.originalsBlocked.collectAsStateWithLifecycle()
    val firstRun = restoreOffered && !settings.demoMode && settings.targetDevice == null && night == null &&
        settings.rememberedBedJets.isEmpty() && !fullSetup && unreadable.isEmpty() && !restoreUnfinished && !originalsBlocked
    val profileName = night?.profile?.name ?: profile?.name ?: stringResource(R.string.tonights_night)

    Box(modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("tonight-content")
        .padding(horizontal = 16.dp).padding(bottom = 16.dp + FeedbackClearance), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (firstRun) {
            SetupChecklistCard(model, firstRun = true)
            RestoreOfferCard(model)
            return@Column
        }
        StatusLine(model, onRemote = onOpenRemote)
        BluetoothBanner(model)
        BedJetReadings(model, settings, "home", keepTemperaturesWhileDisconnected = false, sending = sending.value,
            showRunStatus = runningEvidence.stepIndex == null || sending.value != null)
        RestoreOfferCard(model)
        SetupChecklistCard(model)
        if (showOffConfirmation) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(tokens.surface).padding(12.dp)) {
                TonightOffConfirmed { offConfirmation = null }
            }
        }

        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(tokens.surface)
            .then(tokens.edge?.let { Modifier.border(1.dp, it, RoundedCornerShape(18.dp)) } ?: Modifier)
            .padding(12.dp).testTag("tonight-card"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (night == null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    SectionLabel(stringResource(R.string.parity_tonight_label, profileName), Modifier.semantics { heading() })
                    val scope = if (tonightChoice != null) tonightChoiceLabel(tonightChoice, now)
                    else if (profile == null) stringResource(R.string.tonight_no_schedule)
                    else if (settings.effectiveWeeklySchedule(profiles).profileIdFor(now.dayOfWeek) == profile.id)
                        stringResource(R.string.tonight_from_weekly_schedule, weekdayName(now.dayOfWeek))
                    else stringResource(R.string.tonight_from_fallback)
                    Text(scope, Modifier.padding(top = 3.dp).testTag("tonight-profile-scope"), color = tokens.ink2, fontSize = 13.sp)
                }
                if (profiles.isNotEmpty()) AccentLink(stringResource(R.string.parity_change),
                    { choiceDraft = chosen?.id; choosing = true }, Modifier.testTag("tonight-change"))
            }
            if (night != null) {
                TonightRunningStatus(night, nightStatus, runtime, settings, profileName, phoneAlarm, runningEvidence, now,
                    showReading = sending.value == null)
                if (night.profile != null && profile != null && profile != night.profile && night.pending?.kind != ChangeKind.REAPPLY)
                    Note(stringResource(R.string.tonight_edits_next_night, profile.name))
                night.temporary?.takeIf { confirmed && !night.trackingManual && now.isBefore(ZonedDateTime.parse(it.until)) }?.let {
                    Note(stringResource(R.string.temporary_planned, Format.clock(it.until)))
                }
            }
            val end = if (night != null) manualLabel?.let { label -> runCatching { PlanEnd(label, ZonedDateTime.parse(night.endsAt)) }.getOrNull() } else null
            plan?.takeUnless { night?.pending?.kind == ChangeKind.START }?.let { p ->
                val wakeTime = Format.clock(p.wake)
                val wakeSource = if (night?.isNap == true) R.string.nap_wake else when (night?.wakeOrigin ?: preview?.wake?.origin ?: WakeOrigin.PROFILE_DEFAULT) {
                    WakeOrigin.PROFILE_DEFAULT -> R.string.gpt12_wake_profile_fallback
                    WakeOrigin.OVERRIDE -> R.string.gpt12_wake_override
                    WakeOrigin.ALARM -> R.string.gpt12_wake_next_alarm
                    WakeOrigin.AUTO_START -> R.string.wake_origin_auto_start
                }
                val summary = planSummary(p, settings.fahrenheit)
                val detail = stringResource(R.string.parity_show_detail)
                // UX-08: a chevron always shows that the bar opens; it turns when open, instantly under reduced motion.
                val motion = rememberMotionAllowed()
                val turn by animateFloatAsState(if (showDetail) 180f else 0f,
                    if (motion) tween(200) else snap(), label = "tonight-detail-chevron")
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).testTag("tonight-detail-toggle")
                    .clickable(role = androidx.compose.ui.semantics.Role.Button, onClickLabel = detail) { showDetail = !showDetail }
                    .semantics(mergeDescendants = true) { contentDescription = summary ?: profileName; stateDescription = if (showDetail) "" else detail },
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    NightBar(p, runningEvidence.markerAt, Modifier.weight(1f), height = 40.dp, minHeight = 48.dp)
                    Icon(painterResource(R.drawable.ic_nc_chevron_down), null,
                        Modifier.size(22.dp).rotate(turn).testTag("tonight-detail-chevron"), tint = tokens.ink2)
                }
                Text(stringResource(R.string.tonight_chart_fan), color = tokens.ink2, fontSize = 12.sp)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    val tick = Modifier.weight(1f)
                    Tick(when {
                            night != null && runningEvidence.confirmedPlan != null ->
                                stringResource(R.string.tonight_running_started_at, Format.clock(night.startedAt))
                            night?.isNap == true && confirmed -> Format.clock(night.startedAt)
                            night != null -> Format.clock(p.start)
                            else -> stringResource(R.string.parity_bed, Format.clock(p.start))
                        }, tick, TextAlign.Start)
                    Tick(runningEvidence.markerAt?.let { stringResource(R.string.parity_now, Format.clock(it)) } ?: summary.orEmpty(),
                        tick, TextAlign.Center)
                    val wakeLabel = if (night != null) wakeTime else stringResource(R.string.parity_wake, wakeTime)
                    val wakeTick = when {
                        end != null -> "${end.label} ${Format.clock(end.at)}"
                        runningEvidence.confirmedPlan != null -> stringResource(R.string.run_status_planned_off, Format.clock(runningEvidence.confirmedPlan.end))
                        showAlarmRow && night == null && explanation.unnamedOffer == null -> wakeLabel
                        else -> "$wakeLabel\n${stringResource(wakeSource)}"
                    }
                    val wakeControl = if (showRunningFacts) Modifier else Modifier.testTag("tonight-fixed-wake")
                        .then(if (overridable) Modifier.clickable(onClick = pickWake) else Modifier)
                    Tick(wakeTick, tick.then(wakeControl), TextAlign.End)
                }
                if (end == null && phoneAlarm != null && !phoneAlarm.isEqual(p.wake))
                    Note(stringResource(R.string.tonight_separate_phone_alarm, Format.clock(phoneAlarm)))
                if (showDetail && night?.hasClockTimedNap == true && confirmed)
                    Note(stringResource(R.string.nap_actual_start, Format.clock(night.startedAt)))
                if (showDetail) PlanCard(p, settings, night?.wakeOrigin ?: preview?.wake?.origin ?: WakeOrigin.PROFILE_DEFAULT,
                    now = runningEvidence.markerAt, end = end, middle = night?.profile?.middle ?: profile?.middle,
                    onEditPhase = if (profile == null) null else editPhase)
            } ?: if (night == null) Text(if (needsWake) stringResource(R.string.tonight_wake_out_of_range,
                    Format.dayClock(preview!!.wake.time)) else preview?.refusalReason.orEmpty(),
                color = MaterialTheme.colorScheme.onSurface) else Unit
            if (showAlarmRow) {
                val displayedWake = explanation.wakeAt?.let { plan?.wake?.isEqual(it) } == true
                AlarmExplanationLine(explanation, displayedWake, openExplanation)
            }
            if (night != null) TonightRunningFacts(night, runningEvidence, settings, now, pickWake.takeIf { overridable })
            else plan?.let { p ->
                val modes = p.steps.filter { it.block == Block.AFTER_WAKE }.map { it.step.mode }.distinct()
                val endTime = Format.clock(p.end)
                Note(when {
                    modes.isEmpty() || modes.singleOrNull() == Mode.STANDBY -> stringResource(R.string.tonight_after_wake_off, endTime)
                    modes.size == 1 -> stringResource(R.string.tonight_after_wake_mode, modeName(modes.single()), endTime)
                    else -> stringResource(R.string.tonight_after_wake_steps, endTime)
                })
            }
            if (night == null) profile?.let { selected ->
                val later = plan?.start?.isAfter(now.plusMinutes(1)) == true
                BigButton(when {
                        needsWake -> stringResource(R.string.tonight_choose_wake_and_start)
                        later -> stringResource(R.string.tonight_start_at, Format.clock(plan!!.start))
                        else -> stringResource(R.string.tonight_start_now)
                    },
                    enabled = !busy && (plan != null || needsWake), onClick = {
                        if (needsWake) nightWakeOffer = true else model.run(Command.START_NIGHT, selected.id)
                    })
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val pill = ButtonDefaults.buttonColors(containerColor = tokens.surface2, contentColor = tokens.ink)
                    if (overridable) Button(onClick = pickWake, colors = pill, shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.parity_change_wake), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                    val turnOff = stringResource(R.string.guard_turn_off)
                    Button(onClick = { guard.confirm(turnOff) { model.run(Command.END_NIGHT) } }, colors = pill, shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("tonight-end-night")) {
                        Text(stringResource(if (night.isNap) R.string.nap_end else R.string.parity_end_night), fontSize = 14.sp,
                            color = if (tokens.dark) Color(0xFFFFB3A8) else tokens.bad, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            // The wake links sit with tonight's plan, so the presets below stay in view.
            val setWake = wakeOverride == null && night == null && overridable && !needsWake
            val clearWake = wakeOverride != null && overridable
            val followSchedule = night == null && tonightChoice != null
            if (setWake || clearWake || followSchedule) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (setWake) TextButton(onClick = pickWake) { Text(stringResource(R.string.tonight_set_wake)) }
                if (clearWake) TextButton(onClick = { model.setTonightWake(null) }) {
                    Text(stringResource(R.string.tonight_clear_wake))
                }
                if (followSchedule) TextButton(onClick = { model.updateSettings { it.followSchedule() } }) {
                    Text(stringResource(R.string.tonight_follow_schedule))
                }
            }
        }

        QuickNapControls(model, settings, chosen, alarm, guard, busy, nightWakeOffer, { nightWakeOffer = false }) {
            val selected = chosen
            if (selected != null) pickWakeThen {
                if (model.startPreview(selected).plan != null) model.run(Command.START_NIGHT, selected.id)
            }
        }
        wakeOverride?.takeUnless { night?.isNap == true }?.let { override ->
            runCatching { ZonedDateTime.parse(override.expiresAt) }.getOrNull()?.let { lapses ->
                Note(stringResource(R.string.override_expiry, Format.clock(lapses), lapses.format(DateTimeFormatter.ofPattern("EEE d MMM"))))
            }
        }
        NightRecoveryControls(model, night, guard, now)

        if (night == null) TonightScheduledStart(model, now)

        QuickPresets(model, guard, sending, onEdit = { editingQuick = true }, onEditPreset = { editingPreset = it },
            onEditBoost = { editingBoost = true })

        val banner = UnreadableBannerState(unreadable, restoreUnfinished, originalsBlocked)
        UnreadableDataBanner(banner, onDismiss = model::dismissUnreadable,
            onRestore = onOpenBackups.takeIf { banner.offersRestore }, onAcceptLoss = model::acceptLostOriginals)
        runtime.unresolvedSlotWrite?.takeIf { DeviceId.same(it.deviceId, settings.deviceId()) }?.let {
            SlotWriteNotice(it, onChecked = model::acknowledgeSlotWrite)
        }
        // Last night stays apart from tonight's controls, below them.
        LastNightCard(model, onOpenSleep)
        // UX-01: once a BedJet is chosen the picker lives in Settings; it returns here if the target is removed.
        if (settings.targetDevice == null) BedJetPicker(model, compact = true)
    }

    BusyIndicator(model)
    }

    if (choosing) AlertDialog(onDismissRequest = { choosing = false },
        title = { Text(stringResource(R.string.parity_choose_profile)) },
        text = {
            Column {
                LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 340.dp).selectableGroup()
                    .testTag("tonight-profile-list")) {
                    items(profiles, key = { it.id }) { p ->
                        val selected = choiceDraft == p.id
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .selectable(selected, role = androidx.compose.ui.semantics.Role.RadioButton, onClick = { choiceDraft = p.id })
                            .testTag("tonight-choose-${p.id}"), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = selected, onClick = null, colors = nocturneRadioColors())
                            Column(Modifier.weight(1f).padding(start = 12.dp, top = 8.dp, bottom = 8.dp)) {
                                Text(p.name)
                                val previewPlan = remember(p, alarm, now.toLocalDate()) { model.previewPlan(p).getOrNull() }
                                previewPlan?.let {
                                    val summary = profileSummary(p)
                                    NightBar(it, null, Modifier.testTag("picker-timeline-${p.id}")
                                        .semantics { contentDescription = summary }, height = 22.dp)
                                }
                            }
                        }
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    profiles.firstOrNull { it.id == choiceDraft }?.let { selected ->
                        AccentLink(stringResource(R.string.action_edit), { choosing = false; onEdit(selected) })
                    }
                    AccentLink(stringResource(R.string.weekly_edit_schedule), { choosing = false; onOpenSchedule() },
                        Modifier.testTag("tonight-picker-schedule"))
                }
            }
        },
        confirmButton = {
            TextButton(enabled = profiles.any { it.id == choiceDraft }, modifier = Modifier.testTag("tonight-picker-use"), onClick = {
                choiceDraft?.let { id -> model.updateSettings { it.chooseTonight(id, model.now()) } }
                choosing = false
            }) { Text(stringResource(R.string.tonight_use_profile)) }
        },
        dismissButton = { TextButton(onClick = { choosing = false }) { Text(stringResource(R.string.uat_quick_cancel)) } })

    if (editingQuick) AlertDialog(onDismissRequest = { editingQuick = false },
        title = { Text(stringResource(R.string.parity_quick_presets)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            QuickDefaultsSection(model, settings)
        } },
        confirmButton = { TextButton(onClick = { editingQuick = false }) { Text(stringResource(R.string.uat_setup_done)) } })
    if (editingBoost) BoostEditor(model, settings) { editingBoost = false }
    editingPreset?.let { mode -> QuickPresetEditor(model, settings, QuickDefaults.resolve(settings, mode)) { editingPreset = null } }
}

/** Compact visual ticks with a complete, readable wake source and a 48 dp tap area. */
@Composable
private fun Tick(text: String, modifier: Modifier, align: TextAlign) {
    Box(modifier.heightIn(min = 48.dp), contentAlignment = when (align) {
        TextAlign.Start -> Alignment.CenterStart
        TextAlign.End -> Alignment.CenterEnd
        else -> Alignment.Center
    }) { Text(text, color = paletteTokens.ink2, fontSize = 12.sp, textAlign = align) }
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

/** The night itself as the step list. Simple View was retired (UX-04): the list is the one supported view. */
@Composable
private fun PlanCard(plan: Plan, settings: Settings, origin: WakeOrigin, now: ZonedDateTime?, end: PlanEnd? = null,
    middle: io.github.willzy12h.nocturne.core.plan.Middle? = null, onEditPhase: ((Block, ZonedDateTime) -> Unit)? = null) {
    Card(Modifier.fillMaxWidth(), colors = surfaceCard(), border = cardBorder()) {
        Column(Modifier.padding(16.dp)) {
            NightTimeline(plan, origin, settings.fahrenheit, Modifier.testTag("night-timeline"), now = now, end = end, middle = middle,
                onEditPhase = onEditPhase?.let { edit -> { entry -> edit(entry.block, entry.startsAt) } })
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
internal fun Note(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

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
