package io.github.willzy12h.nocturne.ui

import android.app.TimePickerDialog
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.stateDescription
import io.github.willzy12h.nocturne.core.plan.Block
import io.github.willzy12h.nocturne.core.plan.Plan
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import io.github.willzy12h.nocturne.ui.NocturneButton as Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import io.github.willzy12h.nocturne.ui.NocturneTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import io.github.willzy12h.nocturne.core.plan.FanShape
import io.github.willzy12h.nocturne.core.plan.QuietFan
import io.github.willzy12h.nocturne.core.plan.Middle
import io.github.willzy12h.nocturne.core.plan.Phase
import io.github.willzy12h.nocturne.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import io.github.willzy12h.nocturne.core.plan.Profile
import io.github.willzy12h.nocturne.core.plan.ProfileLimits
import io.github.willzy12h.nocturne.core.plan.Season
import io.github.willzy12h.nocturne.core.plan.TurboChain
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.night.CommandRequest
import io.github.willzy12h.nocturne.night.NightActions
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

private val PHASE_MODES = listOf(Mode.COOL, Mode.HEAT, Mode.EXTENDED_HEAT, Mode.TURBO, Mode.DRY, Mode.WAIT)

/** Edits every part of a profile, with a live preview of tonight's night and any reason the BedJet would refuse it. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ProfileEditor(model: AppViewModel, original: Profile, onDone: () -> Unit,
    initialDraft: Profile? = null, onDraftChange: (Profile) -> Unit = {}, initialPhase: EditorPhaseSelection? = null) {
    var profile by remember(original.id) { mutableStateOf(initialDraft ?: original) }
    LaunchedEffect(profile) { onDraftChange(profile) }
    val settings by model.settings.collectAsState()
    val f = settings.fahrenheit
    val preview = model.previewPlan(profile)
    val validDurations = (profile.start + profile.wake + profile.afterWake).all { it.minutes in 1..720 }
    var fanShape by remember(original.id) { mutableStateOf(profile.middle.fanShape) }
    var focusedPhase by remember(original.id) { mutableStateOf(initialPhase) }
    var focusRequest by remember(original.id) { mutableIntStateOf(0) }
    var middleOpen by remember(original.id) { mutableStateOf(initialPhase?.block == Block.MIDDLE) }
    val middleFocus = remember { BringIntoViewRequester() }
    LaunchedEffect(focusedPhase, focusRequest) {
        if (focusedPhase?.block == Block.MIDDLE) { middleOpen = true }
    }
    val night by model.activeNight.collectAsState()
    val trackingTonight = night?.profileId == original.id
    var confirmDiscard by remember { mutableStateOf(false) }
    var applyTonightTo by remember { mutableStateOf<CommandRequest?>(null) }
    // Leaving with unsaved edits asks first, so a stray back swipe doesn't lose them.
    val leave = { if (profile != original) confirmDiscard = true else onDone() }
    BackHandler(onBack = leave)

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.editor_discard_title)) },
            text = { Text(stringResource(R.string.editor_discard_text, original.name)) },
            confirmButton = { TextButton(onClick = { confirmDiscard = false; onDone() }) { Text(stringResource(R.string.editor_discard)) } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.editor_keep_editing)) } },
        )
    }
    applyTonightTo?.let { request ->
        AlertDialog(
            onDismissRequest = { applyTonightTo = null },
            title = { Text(stringResource(R.string.editor_update_tonight_title)) },
            text = { Text(stringResource(R.string.editor_update_tonight_text, original.name)) },
            confirmButton = {
                TextButton(onClick = { model.saveProfileThenReapply(profile, request); applyTonightTo = null; onDone() }) { Text(stringResource(R.string.editor_update_tonight)) }
            },
            dismissButton = { TextButton(onClick = { model.saveProfileForNextNight(profile); applyTonightTo = null; onDone() }) { Text(stringResource(R.string.editor_next_night)) } },
        )
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            DemoModePanel(model, showTryDemo = false)
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = leave) { Icon(Icons.Filled.Close, stringResource(R.string.feature_cancel)) }
                Text(stringResource(R.string.editor_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                val plan = preview
                Button(onClick = {
                    if (trackingTonight && profile != original) {
                        applyTonightTo = model.graph.actions.capture(NightActions.Command.REAPPLY, requireIdentity = true)
                            .copy(expected = night?.identity)
                    } else { model.saveProfile(profile); onDone() }
                }, enabled = plan.isSuccess && profile.name.isNotBlank() && validDurations) { Text(stringResource(R.string.editor_save)) }
            }
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (trackingTonight) {
                    Text(
                        stringResource(R.string.editor_running_tonight),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Text(profile.name, style = MaterialTheme.typography.titleLarge)
                preview.fold(
                    onSuccess = { plan ->
                        Text(stringResource(R.string.editor_preview, Format.clock(plan.start), plan.steps.size), style = MaterialTheme.typography.labelLarge)
                        NightCurve(plan, phaseInteraction = true, onEditPhase = { step ->
                            focusedPhase = editorPhaseSelection(profile, plan, step.block, step.startsAt)
                            focusRequest++
                        })
                    },
                    onFailure = { Text(it.message.orEmpty(), color = MaterialTheme.colorScheme.onSurface) },
                )
                if (!validDurations) Text(stringResource(R.string.editor_invalid_phase_duration), color = MaterialTheme.colorScheme.onSurface)
                PhaseList(Block.START, stringResource(R.string.editor_start_help), profile.start, f, preview.getOrNull(), focusedPhase, focusRequest) { profile = profile.copy(start = it) }

                Section(stringResource(R.string.editor_through_night), Modifier.bringIntoViewRequester(middleFocus)) {
                    val middle = profile.middle
                    val title = if (middle.mode.usesTemperature) stringResource(R.string.editor_middle_temperatures,
                        modeName(middle.mode), tempText(middle.tempStart, f), tempText(middle.tempEnd, f)) else modeName(middle.mode)
                    val fan = middle.quietFan?.let {
                        stringResource(R.string.profiles_quiet_middle_short, stringResource(R.string.fan_shape_quiet), it.settle, it.asleep, it.rise)
                    } ?: stringResource(if (middle.fanShape == FanShape.STEADY) R.string.fan_shape_steady else R.string.fan_shape_ease)
                    StripeTile(stripe = paletteTokens.stepColor(middle.mode, middle.tempStart),
                        title = title, level = TintLevel.STEP, subtitle = listOf(fan, stringResource(R.string.editor_steps, middle.rampSteps)).joinToString(" · "),
                        modifier = Modifier.fillMaxWidth().testTag("editor-middle-summary").clickable { middleOpen = true },
                        trailing = { Icon(Icons.Default.ExpandMore, null) })
                }
                if (middleOpen) ModalBottomSheet(onDismissRequest = { middleOpen = false }, containerColor = paletteTokens.sheet,
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
                    Column(Modifier.fillMaxWidth().heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.8f).dp).testTag("editor-middle-sheet").verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.editor_through_night), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = { middleOpen = false }) { Text(stringResource(R.string.uat_setup_done)) }
                    }
                    Text(stringResource(R.string.editor_middle_help), style = MaterialTheme.typography.bodySmall)
                    val m = profile.middle
                    ModeChips(m.mode) { profile = profile.copy(middle = m.copy(mode = it, tempStart = it.clampTemp(m.tempStart), tempEnd = it.clampTemp(m.tempEnd))) }
                    if (m.mode.usesFan) {
                        Text(stringResource(R.string.editor_fan_shape))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(FanShape.STEADY to R.string.fan_shape_steady, FanShape.EASE to R.string.fan_shape_ease,
                                FanShape.QUIET_MIDDLE to R.string.fan_shape_quiet).forEach { (shape, label) ->
                                FilterChip(fanShape == shape, onClick = {
                                    val start = m.quietFan?.settle ?: m.fanStart
                                    val clamp: (Int) -> Int = { legalFan(it, profile.limits) }
                                    val next = when (shape) {
                                        FanShape.STEADY -> m.copy(fanStart = clamp(start), fanEnd = clamp(start), quietFan = null)
                                        FanShape.EASE -> m.copy(fanStart = clamp(start), fanEnd = clamp(m.fanEnd), quietFan = null)
                                        FanShape.QUIET_MIDDLE -> {
                                            val q = m.quietFan ?: QuietFan(clamp(50), 30, clamp(25), clamp(45), 40)
                                            m.copy(fanStart = q.settle, fanEnd = q.rise, quietFan = q)
                                        }
                                    }
                                    fanShape = shape
                                    profile = profile.copy(middle = next)
                                }, label = { Text(stringResource(label)) }, modifier = Modifier.heightIn(min = 48.dp))
                            }
                        }
                        val limits = profile.limits
                        when (fanShape) {
                            FanShape.STEADY -> Labelled(stringResource(R.string.editor_fan_start, m.fanStart)) {
                                FanSlider(m.fanStart, stringResource(R.string.slider_fan_start), limits) { profile = profile.copy(middle = m.copy(fanStart = it, fanEnd = it, quietFan = null)) }
                            }
                            FanShape.EASE -> {
                                Labelled(stringResource(R.string.editor_fan_start, m.fanStart)) { FanSlider(m.fanStart, stringResource(R.string.slider_fan_start), limits) { profile = profile.copy(middle = m.copy(fanStart = it, quietFan = null)) } }
                                Labelled(stringResource(R.string.editor_fan_end, m.fanEnd)) { FanSlider(m.fanEnd, stringResource(R.string.slider_fan_end), limits) { profile = profile.copy(middle = m.copy(fanEnd = it, quietFan = null)) } }
                            }
                            FanShape.QUIET_MIDDLE -> m.quietFan?.let { q ->
                                fun updateQuiet(next: QuietFan) { profile = profile.copy(middle = m.copy(fanStart = next.settle, fanEnd = next.rise, quietFan = next)) }
                                Labelled(stringResource(R.string.editor_settle_speed, q.settle)) { FanSlider(q.settle, stringResource(R.string.slider_settle_speed), limits) { updateQuiet(q.copy(settle = it)) } }
                                Labelled(stringResource(R.string.editor_settle_minutes, q.settleMinutes)) { MinuteSlider(q.settleMinutes, stringResource(R.string.slider_settle_minutes), 0) { updateQuiet(q.copy(settleMinutes = it)) } }
                                Labelled(stringResource(R.string.editor_asleep_speed, q.asleep)) { FanSlider(q.asleep, stringResource(R.string.slider_asleep_speed), limits) { updateQuiet(q.copy(asleep = it)) } }
                                Labelled(stringResource(R.string.editor_rise_speed, q.rise)) { FanSlider(q.rise, stringResource(R.string.slider_rise_speed), limits) { updateQuiet(q.copy(rise = it)) } }
                                Labelled(stringResource(R.string.editor_rise_minutes, q.riseMinutes)) { MinuteSlider(q.riseMinutes, stringResource(R.string.slider_rise_minutes), 0) { updateQuiet(q.copy(riseMinutes = it)) } }
                            }
                        }
                    }
                    if (m.mode.usesTemperature) {
                        Labelled(stringResource(R.string.editor_temp_start, tempText(m.tempStart, f))) { TempSlider(m.mode, m.tempStart, stringResource(R.string.slider_temp_start), f) { profile = profile.copy(middle = m.copy(tempStart = it)) } }
                        Labelled(stringResource(R.string.editor_temp_end, tempText(m.tempEnd, f))) { TempSlider(m.mode, m.tempEnd, stringResource(R.string.slider_temp_end), f) { profile = profile.copy(middle = m.copy(tempEnd = it)) } }
                    }
                    Labelled(stringResource(R.string.editor_steps, m.rampSteps)) {
                        Slider(m.rampSteps.toFloat(), { profile = profile.copy(middle = m.copy(rampSteps = it.roundToInt())) }, valueRange = 1f..8f, steps = 6,
                            modifier = Modifier.sliderSemantics(stringResource(R.string.slider_steps), pluralStringResource(R.plurals.a11y_steps, m.rampSteps, m.rampSteps)))
                    }
                }

                }
                PhaseList(Block.WAKE, stringResource(R.string.editor_wake_help), profile.wake, f, preview.getOrNull(), focusedPhase, focusRequest) { profile = profile.copy(wake = it) }
                PhaseList(Block.AFTER_WAKE, stringResource(R.string.editor_after_wake_help), profile.afterWake, f, preview.getOrNull(), focusedPhase, focusRequest) { profile = profile.copy(afterWake = it) }

                OutlinedTextField(profile.name, { profile = profile.copy(name = it.take(40)) }, label = { Text(stringResource(R.string.editor_name)) }, modifier = Modifier.fillMaxWidth())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Season.entries.forEach { s -> FilterChip(profile.season == s, { profile = profile.copy(season = s) }, label = { Text(stringResource(seasonName(s))) }) }
                }

                Section(stringResource(R.string.editor_timing)) {
                    TimeRow(stringResource(R.string.editor_bedtime), profile.bedtimeMinute) { profile = profile.copy(bedtimeMinute = it) }
                    SwitchRow(stringResource(R.string.editor_wake_with_alarm), stringResource(R.string.editor_wake_with_alarm_help), profile.wakeSource.useAlarm) {
                        profile = profile.copy(wakeSource = profile.wakeSource.copy(useAlarm = it))
                    }
                    TimeRow(stringResource(if (profile.wakeSource.useAlarm) R.string.editor_wake_no_alarm else R.string.wake_time), profile.wakeSource.defaultWakeMinute) {
                        profile = profile.copy(wakeSource = profile.wakeSource.copy(defaultWakeMinute = it))
                    }
                    Text(stringResource(R.string.editor_days), style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        DayOfWeek.entries.forEach { day ->
                            FilterChip(day.value in profile.days, {
                                profile = profile.copy(days = if (day.value in profile.days) profile.days - day.value else profile.days + day.value)
                            }, label = { Text(day.getDisplayName(TextStyle.SHORT, Locale.getDefault())) })
                        }
                    }
                }

                Section(stringResource(R.string.editor_limits)) {
                    Text(stringResource(R.string.editor_limits_help), style = MaterialTheme.typography.bodySmall)
                    val limits = profile.limits
                    Text(stringResource(R.string.editor_fan_limits, limits.minFan, limits.maxFan))
                    RangeSlider(
                        modifier = Modifier.sliderSemantics(stringResource(R.string.slider_fan_limits),
                            spokenRange(spokenPercent(limits.minFan), spokenPercent(limits.maxFan))),
                        value = limits.minFan.toFloat()..limits.maxFan.toFloat(),
                        onValueChange = {
                            val next = limits.copy(minFan = it.start.round5(), maxFan = it.endInclusive.round5())
                            profile = profile.copy(limits = next, middle = profile.middle.withFanLimits(next))
                        },
                        valueRange = 5f..100f, steps = 18,
                    )
                    Text(stringResource(R.string.editor_temp_limits, tempText(limits.minTemp, f), tempText(limits.maxTemp, f)))
                    RangeSlider(
                        modifier = Modifier.sliderSemantics(stringResource(R.string.slider_temp_limits),
                            spokenRange(spokenTemp(limits.minTemp, f), spokenTemp(limits.maxTemp, f))),
                        value = limits.minTemp.toFloat()..limits.maxTemp.toFloat(),
                        onValueChange = { profile = profile.copy(limits = limits.copy(minTemp = it.start.roundToInt(), maxTemp = it.endInclusive.roundToInt())) },
                        valueRange = 38f..86f, steps = 47,
                    )
                    TextButton(onClick = { profile = profile.copy(limits = ProfileLimits(), middle = profile.middle.withFanLimits(ProfileLimits())) }) { Text(stringResource(R.string.editor_reset_limits)) }
                }

                Section(stringResource(R.string.mode_turbo)) {
                    SwitchRow(
                        stringResource(R.string.editor_repeat_turbo),
                        null,
                        profile.turboChain == TurboChain.REPEAT_TURBO,
                    ) { profile = profile.copy(turboChain = if (it) TurboChain.REPEAT_TURBO else TurboChain.TURBO_THEN_HEAT) }
                    Text(stringResource(R.string.editor_repeat_turbo_help), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun PhaseList(block: Block, hint: String, phases: List<Phase>, f: Boolean, plan: Plan?,
    focus: EditorPhaseSelection?, focusRequest: Int, onChange: (List<Phase>) -> Unit) {
    var expanded by remember(block) { mutableStateOf(emptySet<Int>()) }
    var addedFocus by remember(block) { mutableStateOf<Int?>(null) }
    val requested = focus?.takeIf { it.block == block }?.index
    LaunchedEffect(requested, focusRequest) { requested?.takeIf { it in phases.indices }?.let { expanded = expanded + it } }
    val start = when (block) {
        Block.START -> plan?.start
        Block.WAKE -> plan?.wake?.minusMinutes(phases.sumOf { it.minutes }.toLong())
        Block.AFTER_WAKE -> plan?.wake
        Block.MIDDLE, Block.TEMPORARY -> null
    }
    Section(blockName(block)) {
        Text(hint, style = MaterialTheme.typography.bodySmall)
        phases.forEachIndexed { index, phase ->
            val open = index in expanded
            val requester = remember(block, index) { BringIntoViewRequester() }
            LaunchedEffect(requested, focusRequest, addedFocus) {
                if (requested == index || addedFocus == index) { withFrameNanos { }; requester.bringIntoView() }
            }
            val phaseStart = start?.plusMinutes(phases.take(index).sumOf { it.minutes }.toLong())
            val editLabel = stringResource(R.string.ux_edit_phase)
            val state = stringResource(if (open) R.string.ux_expanded else R.string.ux_collapsed)
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val effectiveTemperature = if (phase.mode == Mode.TURBO) 86 else phase.temperature
                val detail = buildList {
                    add(phaseStart?.let(Format::clock) ?: stringResource(R.string.ux_unknown_time))
                    add(durationText(phase.minutes))
                    if (phase.mode.usesTemperature || phase.mode == Mode.TURBO) add(tempText(effectiveTemperature, f))
                    if (phase.mode.usesFan) add(stringResource(R.string.timeline_fan, phase.fanPercent))
                }.joinToString(" · ")
                StripeTile(
                    stripe = paletteTokens.stepColor(phase.mode, effectiveTemperature),
                    title = modeName(phase.mode),
                    subtitle = detail,
                    level = TintLevel.STEP,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("editor-phase-${block.name}-$index")
                        .bringIntoViewRequester(requester)
                        .combinedClickable(role = Role.Button, onClickLabel = editLabel, onLongClickLabel = editLabel,
                            onClick = { expanded = if (open) expanded - index else expanded + index },
                            onLongClick = { expanded = expanded + index })
                        .semantics { stateDescription = state },
                    trailing = { Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null) },
                )
                if (open) ModalBottomSheet(onDismissRequest = { expanded = expanded - index }, containerColor = paletteTokens.sheet,
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
                  Column(Modifier.fillMaxWidth().heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.8f).dp).testTag("editor-phase-controls-${block.name}-$index")
                    .verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.editor_step_title, modeName(phase.mode), index + 1), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = { expanded = expanded - index }) { Text(stringResource(R.string.uat_setup_done)) }
                    }
                    fun update(value: Phase) = onChange(phases.mapIndexed { i, old -> if (i == index) value else old })
                    phaseStart?.let { at -> Text(stringResource(R.string.ux_phase_timeframe, Format.clock(at),
                        Format.clock(at.plusMinutes(phase.minutes.toLong()))), style = MaterialTheme.typography.labelLarge) }
                    ModeChips(phase.mode) { update(if (it == Mode.TURBO && phase.mode != Mode.TURBO) phase.copy(mode = it, temperature = 86, minutes = 10) else phase.copy(mode = it, temperature = it.clampTemp(phase.temperature))) }
                    if (phase.mode.usesTemperature) Labelled(stringResource(R.string.label_temperature, tempText(phase.temperature, f))) {
                        TempSlider(phase.mode, phase.temperature, stringResource(R.string.slider_temp), f) { update(phase.copy(temperature = it)) }
                    }
                    if (phase.mode.usesFan && phase.mode != Mode.TURBO) Labelled(stringResource(R.string.label_fan, phase.fanPercent)) {
                        FanSlider(phase.fanPercent, stringResource(R.string.slider_fan)) { update(phase.copy(fanPercent = it)) }
                    }
                    if (phase.mode != Mode.TURBO) Labelled(stringResource(R.string.editor_length, durationText(phase.minutes))) {
                        MinuteSlider(phase.minutes, stringResource(R.string.slider_length), 1) { update(phase.copy(minutes = it)) }
                    } else {
                        Text(stringResource(R.string.label_temperature, tempText(effectiveTemperature, f)))
                        Text(stringResource(R.string.editor_length, durationText(phase.minutes)))
                    }
                    TextButton(onClick = {
                        expanded = expanded.mapNotNull { if (it < index) it else if (it > index) it - 1 else null }.toSet()
                        onChange(phases.filterIndexed { i, _ -> i != index })
                    }) { Text(stringResource(R.string.editor_remove)) }
                  }
                }
            }
        }
        TextButton(onClick = {
            expanded = expanded + phases.size
            addedFocus = phases.size
            onChange(phases + Phase(Mode.COOL, 44, 40, 30))
        }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("editor-add-${block.name}")) {
            Icon(Icons.Default.Add, null)
            Text(stringResource(R.string.editor_add_phase), modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun Section(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider()
        Text(title, style = MaterialTheme.typography.titleMedium)
        content()
    }
}

@Composable
private fun Labelled(label: String, content: @Composable () -> Unit) {
    Column { Text(label, style = MaterialTheme.typography.bodyMedium); content() }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModeChips(selected: Mode, onSelect: (Mode) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PHASE_MODES.forEach { FilterChip(selected == it, { onSelect(it) }, label = { ModeLabel(it) }, modifier = Modifier.heightIn(min = 48.dp)) }
    }
}

private fun legalFan(value: Int, limits: ProfileLimits): Int {
    val low = ((limits.minFan + 4) / 5) * 5
    val high = (limits.maxFan / 5) * 5
    return if (low <= high) ((value / 5f).roundToInt() * 5).coerceIn(low, high) else value
}

private fun Middle.withFanLimits(limits: ProfileLimits): Middle {
    val quiet = quietFan?.let {
        it.copy(settle = legalFan(it.settle, limits), asleep = legalFan(it.asleep, limits), rise = legalFan(it.rise, limits))
    }
    return copy(
        fanStart = quiet?.settle ?: legalFan(fanStart, limits),
        fanEnd = quiet?.rise ?: legalFan(fanEnd, limits),
        quietFan = quiet,
    )
}

@Composable
private fun FanSlider(value: Int, label: String, limits: ProfileLimits = ProfileLimits(), onChange: (Int) -> Unit) {
    val low = ((limits.minFan + 4) / 5) * 5
    val high = (limits.maxFan / 5) * 5
    val hasRange = low < high
    val display = if (low <= high) legalFan(value, limits) else value.coerceIn(5, 100)
    Slider(
        display.toFloat(),
        { onChange(legalFan(it.roundToInt(), limits)) },
        valueRange = if (hasRange) low.toFloat()..high.toFloat() else 5f..100f,
        enabled = hasRange,
        steps = if (hasRange) (high - low) / 5 - 1 else 18,
        modifier = Modifier.sliderSemantics(label, spokenPercent(value)),
    )
}

@Composable
private fun MinuteSlider(value: Int, label: String, minimum: Int, onChange: (Int) -> Unit) =
    Slider(value.coerceIn(minimum, 720).toFloat(), { onChange(it.roundToInt().coerceIn(minimum, 720)) },
        valueRange = minimum.toFloat()..720f, steps = 720 - minimum - 1,
        modifier = Modifier.sliderSemantics(label, spokenMinutes(value)))

@Composable
private fun TempSlider(mode: Mode, value: Int, label: String, fahrenheit: Boolean, onChange: (Int) -> Unit) {
    val range = mode.temperatureRange
    Slider(value.coerceIn(range).toFloat(), { onChange(it.roundToInt()) }, valueRange = range.first.toFloat()..range.last.toFloat(), steps = (range.last - range.first - 1).coerceAtLeast(0),
        modifier = Modifier.sliderSemantics(label, spokenTemp(value.coerceIn(range), fahrenheit)))
}

@Composable
fun SwitchRow(title: String, detail: String?, checked: Boolean, enabled: Boolean = true, modifier: Modifier = Modifier, onChange: (Boolean) -> Unit) {
    Column(modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
        .semantics { contentDescription = title }) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Switch(checked, onCheckedChange = null, enabled = enabled)
        }
        detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
fun TimeRow(label: String, minuteOfDay: Int, onChange: (Int) -> Unit) {
    val context = LocalContext.current
    val tokens = paletteTokens
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        TextButton(onClick = {
            val themed = android.view.ContextThemeWrapper(context, if (tokens.dark) android.R.style.Theme_Material_Dialog_Alert else android.R.style.Theme_Material_Light_Dialog_Alert)
            TimePickerDialog(themed, { _, h, m -> onChange(h * 60 + m) }, minuteOfDay / 60, minuteOfDay % 60, true).apply {
                show(); styleNativeDialog(this, tokens)
            }
        }) { Text(Format.clockOfDay(minuteOfDay)) }
    }
}

private fun Float.round5() = ((this / 5).roundToInt() * 5).coerceIn(5, 100)
private fun Mode.clampTemp(value: Int) = value.coerceIn(temperatureRange)

