package io.github.willzy12h.nocturne.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import io.github.willzy12h.nocturne.core.plan.PlannedStep
import android.app.TimePickerDialog
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.testTag
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import io.github.willzy12h.nocturne.ui.NocturneTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import kotlin.math.abs

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

    var addingTo by remember(original.id) { mutableStateOf<Block?>(null) }
    val wake = model.wakePreview(profile)
    val tokens = paletteTokens
    val save = {
        if (trackingTonight && profile != original) {
            applyTonightTo = model.graph.actions.capture(NightActions.Command.REAPPLY, requireIdentity = true)
                .copy(expected = night?.identity)
        } else { model.saveProfile(profile); onDone() }
    }
    fun phasesOf(block: Block) = when (block) { Block.START -> profile.start; Block.WAKE -> profile.wake; else -> profile.afterWake }
    fun setPhases(block: Block, phases: List<Phase>) {
        profile = when (block) { Block.START -> profile.copy(start = phases); Block.WAKE -> profile.copy(wake = phases); else -> profile.copy(afterWake = phases) }
    }

    addingTo?.let { block ->
        ModalBottomSheet(onDismissRequest = { addingTo = null }, containerColor = paletteTokens.sheet,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            AddStepContent(block, f, onCancel = { addingTo = null }) { target, phase ->
                val list = phasesOf(target)
                setPhases(target, list + phase)
                addingTo = null
                focusedPhase = EditorPhaseSelection(target, list.size)
                focusRequest++
            }
        }
    }

    Surface(Modifier.fillMaxSize(), color = tokens.ground) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            DemoModePanel(model, showTryDemo = false)
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                AccentLink(stringResource(R.string.parity_back, stringResource(R.string.profiles_title)), leave,
                    Modifier.testTag("editor-back"), fontSize = 15.sp)
                Box(Modifier.weight(1f))
                AccentLink(stringResource(R.string.editor_save), save, Modifier.testTag("editor-save"),
                    enabled = preview.isSuccess && profile.name.isNotBlank() && validDurations, fontSize = 15.sp)
            }
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp).padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(profile.name, color = tokens.ink, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                if (trackingTonight) Text(stringResource(R.string.editor_running_tonight), color = tokens.ink2, fontSize = 13.sp)
                preview.fold(
                    onSuccess = { plan ->
                        NightCurve(plan, phaseInteraction = true, stepped = true, onEditPhase = { step ->
                            focusedPhase = editorPhaseSelection(profile, plan, step.block, step.startsAt)
                            focusRequest++
                        })
                        Row(Modifier.fillMaxWidth()) {
                            val tick: @Composable (String, TextAlign) -> Unit = { text, align ->
                                Text(text, Modifier.weight(1f), color = tokens.ink2, fontSize = 12.sp, textAlign = align)
                            }
                            tick(stringResource(R.string.parity_bed, Format.clock(plan.start)), TextAlign.Start)
                            tick(if (wake.fromAlarm) stringResource(R.string.parity_wake_alarm, Format.clock(plan.wake))
                                else stringResource(R.string.parity_wake, Format.clock(plan.wake)), TextAlign.Center)
                            tick(stringResource(R.string.parity_off, Format.clock(plan.end)), TextAlign.End)
                        }
                    },
                    onFailure = { Text(it.message.orEmpty(), color = tokens.ink) },
                )
                if (!validDurations) Text(stringResource(R.string.editor_invalid_phase_duration), color = tokens.ink)
                PhaseList(Block.START, stringResource(R.string.parity_group_start), profile.start, f, preview.getOrNull(), focusedPhase, focusRequest,
                    profile.limits, onAdd = { addingTo = Block.START }) { profile = profile.copy(start = it) }

                Column(Modifier.bringIntoViewRequester(middleFocus), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val middle = profile.middle
                    val middleMinutes = preview.getOrNull()?.steps?.filter { it.block == Block.MIDDLE }?.sumOf { it.minutes }
                    EditorGroupHeader(stringResource(R.string.parity_group_night), middleMinutes?.let { compactDuration(it) })
                    val title = if (middle.mode.usesTemperature && middle.tempStart == middle.tempEnd) "${modeName(middle.mode)} ${tempText(middle.tempStart, f)}"
                        else if (middle.mode.usesTemperature) stringResource(R.string.editor_middle_temperatures,
                        modeName(middle.mode), tempText(middle.tempStart, f), tempText(middle.tempEnd, f)) else modeName(middle.mode)
                    MiddleCard(middle, title, Modifier.testTag("editor-middle-summary")) { middleOpen = true }
                }
                if (middleOpen) ModalBottomSheet(onDismissRequest = { middleOpen = false }, containerColor = paletteTokens.sheet,
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
                    Column(Modifier.fillMaxWidth().heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.8f).dp).testTag("editor-middle-sheet").verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.editor_through_night), Modifier.weight(1f), color = tokens.ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        AccentLink(stringResource(R.string.uat_setup_done), { middleOpen = false }, fontSize = 15.sp)
                    }
                    Text(stringResource(R.string.editor_middle_help), style = MaterialTheme.typography.bodySmall)
                    val m = profile.middle
                    ModeChips(m.mode) { profile = profile.copy(middle = m.copy(mode = it, tempStart = it.clampTemp(m.tempStart), tempEnd = it.clampTemp(m.tempEnd))) }
                    if (m.mode.usesTemperature) {
                        val ends = preview.getOrNull()?.steps?.filter { it.block == Block.MIDDLE }
                        val temperatureControl: @Composable (Boolean, Modifier) -> Unit = { start, modifier ->
                            val time = if (start) ends?.firstOrNull()?.startsAt else ends?.lastOrNull()?.let { it.startsAt.plusMinutes(it.minutes.toLong()) }
                            TempStepper(m.mode, if (start) m.tempStart else m.tempEnd,
                                stringResource(if (start) R.string.parity_mid_at else R.string.parity_mid_by, time?.let(Format::clock).orEmpty()).trim(),
                                stringResource(if (start) R.string.slider_temp_start else R.string.slider_temp_end), f,
                                if (start) "middle-temp-start" else "middle-temp-end", modifier) {
                                profile = profile.copy(middle = if (start) m.copy(tempStart = it) else m.copy(tempEnd = it))
                            }
                        }
                        if (LocalConfiguration.current.fontScale >= 1.5f) {
                            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                temperatureControl(true, Modifier.fillMaxWidth())
                                temperatureControl(false, Modifier.fillMaxWidth())
                            }
                        } else Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            temperatureControl(true, Modifier.weight(1f))
                            Text("→", color = tokens.ink2, fontSize = 18.sp)
                            temperatureControl(false, Modifier.weight(1f))
                        }
                        SectionLabel(stringResource(R.string.parity_mid_how))
                        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            maxItemsInEachRow = if (LocalConfiguration.current.fontScale >= 1.5f) 2 else 4) {
                            listOf(1 to R.string.parity_mid_shape_stay, 2 to R.string.parity_mid_shape_once,
                                3 to R.string.parity_mid_shape_twice, 6 to R.string.parity_mid_shape_gently).forEach { (count, label) ->
                                val on = if (count == 6) m.rampSteps >= 4 else m.rampSteps == count
                                ShapeCard(count, stringResource(label), on, tokens.stepColor(m.mode, m.tempStart), Modifier.weight(1f)) {
                                    profile = profile.copy(middle = m.copy(rampSteps = count))
                                }
                            }
                        }
                        ends?.takeIf { it.isNotEmpty() }?.let { MiddleBars(it, f) }
                    }
                    Labelled(stringResource(R.string.editor_steps, m.rampSteps)) {
                        NocturneSlider(m.rampSteps.toFloat(), { profile = profile.copy(middle = m.copy(rampSteps = it.roundToInt())) }, valueRange = 1f..8f, steps = 6,
                            modifier = Modifier.sliderSemantics(stringResource(R.string.slider_steps), pluralStringResource(R.plurals.a11y_steps, m.rampSteps, m.rampSteps)), colors = amountSliderColors())
                    }
                    if (m.mode.usesFan) {
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            SectionLabel(stringResource(R.string.editor_fan_shape), Modifier.weight(1f))
                        }
                        Segmented(listOf(FanShape.STEADY to stringResource(R.string.fan_shape_steady), FanShape.EASE to stringResource(R.string.fan_shape_ease),
                            FanShape.QUIET_MIDDLE to stringResource(R.string.fan_shape_quiet)), fanShape, tag = "middle-fan-shape") { shape ->
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
                        }
                        val limits = profile.limits
                        when (fanShape) {
                            FanShape.STEADY -> Labelled(stringResource(R.string.editor_fan_start, m.fanStart)) {
                                FanSlider(m.fanStart, stringResource(R.string.slider_fan_start), limits, m.mode) { profile = profile.copy(middle = m.copy(fanStart = it, fanEnd = it, quietFan = null)) }
                            }
                            FanShape.EASE -> {
                                Labelled(stringResource(R.string.editor_fan_start, m.fanStart)) { FanSlider(m.fanStart, stringResource(R.string.slider_fan_start), limits, m.mode) { profile = profile.copy(middle = m.copy(fanStart = it, quietFan = null)) } }
                                Labelled(stringResource(R.string.editor_fan_end, m.fanEnd)) { FanSlider(m.fanEnd, stringResource(R.string.slider_fan_end), limits, m.mode) { profile = profile.copy(middle = m.copy(fanEnd = it, quietFan = null)) } }
                            }
                            FanShape.QUIET_MIDDLE -> m.quietFan?.let { q ->
                                fun updateQuiet(next: QuietFan) { profile = profile.copy(middle = m.copy(fanStart = next.settle, fanEnd = next.rise, quietFan = next)) }
                                Labelled(stringResource(R.string.editor_settle_speed, q.settle)) { FanSlider(q.settle, stringResource(R.string.slider_settle_speed), limits, m.mode) { updateQuiet(q.copy(settle = it)) } }
                                Labelled(stringResource(R.string.editor_settle_minutes, q.settleMinutes)) { MinuteSlider(q.settleMinutes, stringResource(R.string.slider_settle_minutes), 0) { updateQuiet(q.copy(settleMinutes = it)) } }
                                Labelled(stringResource(R.string.editor_asleep_speed, q.asleep)) { FanSlider(q.asleep, stringResource(R.string.slider_asleep_speed), limits, m.mode) { updateQuiet(q.copy(asleep = it)) } }
                                Labelled(stringResource(R.string.editor_rise_speed, q.rise)) { FanSlider(q.rise, stringResource(R.string.slider_rise_speed), limits, m.mode) { updateQuiet(q.copy(rise = it)) } }
                                Labelled(stringResource(R.string.editor_rise_minutes, q.riseMinutes)) { MinuteSlider(q.riseMinutes, stringResource(R.string.slider_rise_minutes), 0) { updateQuiet(q.copy(riseMinutes = it)) } }
                            }
                        }
                    }
                }

                }
                PhaseList(Block.WAKE, stringResource(if (wake.fromAlarm) R.string.parity_group_wake_alarm else R.string.parity_group_wake), profile.wake, f,
                    preview.getOrNull(), focusedPhase, focusRequest, profile.limits, onAdd = { addingTo = Block.WAKE }) { profile = profile.copy(wake = it) }
                PhaseList(Block.AFTER_WAKE, stringResource(R.string.parity_group_after), profile.afterWake, f, preview.getOrNull(), focusedPhase, focusRequest,
                    profile.limits, onAdd = { addingTo = Block.AFTER_WAKE }) { profile = profile.copy(afterWake = it) }

                OutlinedTextField(profile.name, { profile = profile.copy(name = it.take(40)) }, label = { Text(stringResource(R.string.editor_name)) }, modifier = Modifier.fillMaxWidth(), colors = nocturneTextFieldColors())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Season.entries.forEach { s -> FilterChip(profile.season == s, { profile = profile.copy(season = s) }, label = { Text(stringResource(seasonName(s))) }, colors = nocturneFilterChipColors()) }
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
                            }, label = { Text(day.getDisplayName(TextStyle.SHORT, Locale.getDefault())) }, colors = nocturneFilterChipColors())
                        }
                    }
                }

                Section(stringResource(R.string.editor_limits)) {
                    Text(stringResource(R.string.editor_limits_help), style = MaterialTheme.typography.bodySmall)
                    val limits = profile.limits
                    Text(stringResource(R.string.editor_fan_limits, limits.minFan, limits.maxFan))
                    NocturneRangeSlider(
                        modifier = Modifier.sliderSemantics(stringResource(R.string.slider_fan_limits),
                            spokenRange(spokenPercent(limits.minFan), spokenPercent(limits.maxFan))),
                        value = limits.minFan.toFloat()..limits.maxFan.toFloat(),
                        onValueChange = {
                            val next = limits.copy(minFan = it.start.round5(), maxFan = it.endInclusive.round5())
                            profile = profile.copy(limits = next, middle = profile.middle.withFanLimits(next))
                        },
                        valueRange = 5f..100f, steps = 18, startColour = paletteTokens.accent, endColour = paletteTokens.accent,
                    )
                    Text(stringResource(R.string.editor_temp_limits, tempText(limits.minTemp, f), tempText(limits.maxTemp, f)))
                    NocturneRangeSlider(
                        modifier = Modifier.sliderSemantics(stringResource(R.string.slider_temp_limits),
                            spokenRange(spokenTemp(limits.minTemp, f), spokenTemp(limits.maxTemp, f))),
                        value = limits.minTemp.toFloat()..limits.maxTemp.toFloat(),
                        onValueChange = { profile = profile.copy(limits = limits.copy(minTemp = it.start.roundToInt(), maxTemp = it.endInclusive.roundToInt())) },
                        // Coolest to hottest: the limits apply to every mode, so the fill runs from Cool blue to the deepest Ember.
                        valueRange = 38f..86f, steps = 47, startColour = paletteTokens.cool, endColour = paletteTokens.ember.last(),
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
private fun PhaseList(block: Block, label: String, phases: List<Phase>, f: Boolean, plan: Plan?,
    focus: EditorPhaseSelection?, focusRequest: Int, limits: ProfileLimits, onAdd: () -> Unit, onChange: (List<Phase>) -> Unit) {
    var open by remember(block) { mutableStateOf<Int?>(null) }
    val rowHeights = remember(block) { mutableMapOf<Int, Int>() }
    // Keep unchanged measurements across reorders: equal-height rows need not be measured again.
    SideEffect { rowHeights.keys.retainAll(phases.indices.toSet()) }
    val rowGap = 8.dp
    val gapPx = with(LocalDensity.current) { rowGap.toPx() }
    val requested = focus?.takeIf { it.block == block }?.index
    LaunchedEffect(requested, focusRequest) { requested?.let { open = it } }
    val start = when (block) {
        Block.START -> plan?.start
        Block.WAKE -> plan?.wake?.minusMinutes(phases.sumOf { it.minutes }.toLong())
        Block.AFTER_WAKE -> plan?.wake
        Block.MIDDLE, Block.TEMPORARY -> null
    }
    Column(verticalArrangement = Arrangement.spacedBy(rowGap)) {
        EditorGroupHeader(label, phases.takeIf { it.isNotEmpty() }?.let { list -> compactDuration(list.sumOf { it.minutes }) })
        phases.forEachIndexed { index, phase ->
            val requester = remember(block, index) { BringIntoViewRequester() }
            LaunchedEffect(requested, focusRequest) {
                if (requested == index) { withFrameNanos { }; requester.bringIntoView() }
            }
            val phaseStart = start?.plusMinutes(phases.take(index).sumOf { it.minutes }.toLong())
            val effectiveTemperature = if (phase.mode == Mode.TURBO) 86 else phase.temperature
            val phaseDescription = presetDetail(LocalContext.current, io.github.willzy12h.nocturne.core.store.ActionPreset(
                phase.mode, effectiveTemperature, if (phase.mode == Mode.TURBO) 100 else phase.fanPercent, phase.minutes), f) +
                if (phase.mode == Mode.TURBO) ". " + stringResource(R.string.parity_turbo_always, tempText(86, f)) else ""
            val lastOff = block == Block.AFTER_WAKE && index == phases.lastIndex
            val fan = stringResource(R.string.parity_fan_sub, phase.fanPercent)
            val title = when {
                phase.mode == Mode.TURBO -> modeName(phase.mode)
                lastOff && phase.mode.usesFan -> "${modeName(phase.mode)} · ${fan.lowercase()}"
                phase.mode.usesTemperature -> "${modeName(phase.mode)} ${tempText(phase.temperature, f)}"
                else -> modeName(phase.mode)
            }
            val subtitle = when {
                phase.mode == Mode.TURBO -> stringResource(R.string.parity_turbo_sub)
                lastOff -> stringResource(R.string.parity_then_off)
                phase.mode.usesFan -> fan
                else -> phaseStart?.let(Format::clock) ?: ""
            }
            fun move(by: Int) {
                val to = (index + by).coerceIn(phases.indices)
                if (to == index) return
                onChange(phases.toMutableList().apply { add(to, removeAt(index)) })
            }
            fun drop(distance: Float) {
                var top = 0f
                val centres = phases.indices.map { row ->
                    val height = rowHeights[row] ?: return
                    (top + height / 2f).also { top += height + gapPx }
                }
                val droppedCentre = centres[index] + distance
                // A tall row owns its full measured interval, including the area past a centre midpoint.
                val destination = phases.indices.minByOrNull {
                    (abs(centres[it] - droppedCentre) - rowHeights.getValue(it) / 2f).coerceAtLeast(0f)
                } ?: return
                move(destination - index)
            }
            StepCard(phase, title, subtitle, compactDuration(phase.minutes),
                Modifier.testTag("editor-phase-${block.name}-$index").bringIntoViewRequester(requester)
                    .semantics { contentDescription = phaseDescription; stateDescription = phaseStart?.let(Format::clock).orEmpty() },
                canMoveUp = index > 0, canMoveDown = index < phases.lastIndex,
                onMove = { by -> move(by) }, onDrop = { distance -> drop(distance) },
                onHeightChanged = { height -> rowHeights[index] = height }) { open = index }
            if (open == index) ModalBottomSheet(onDismissRequest = { open = null }, containerColor = paletteTokens.sheet,
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
                Column(Modifier.fillMaxWidth().heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.85f).dp).verticalScroll(rememberScrollState())) {
                    StepEditorContent(
                        title = stringResource(R.string.editor_step_title, blockName(block), index + 1),
                        phase = phase, fahrenheit = f,
                        runs = phaseStart?.let { at -> stringResource(R.string.parity_runs, Format.clock(at), Format.clock(at.plusMinutes(phase.minutes.toLong()))) },
                        tag = "editor-phase-controls-${block.name}-$index",
                        fanSlider = { value, mode, update -> FanSlider(value, stringResource(R.string.slider_fan), limits, mode, update) },
                        onDone = { open = null },
                        onDelete = { open = null; onChange(phases.filterIndexed { i, _ -> i != index }) },
                    ) { value -> onChange(phases.mapIndexed { i, old -> if (i == index) value else old }) }
                }
            }
        }
        AccentLink(stringResource(R.string.parity_add_link), onAdd, Modifier.testTag("editor-add-${block.name}"), fontSize = 14.sp)
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

/** "AT 23:10 − 32° +": an adjustable temperature, so screen readers can still set it directly. */
@Composable
private fun TempStepper(mode: Mode, value: Int, caption: String, label: String, fahrenheit: Boolean, tag: String,
    modifier: Modifier = Modifier, onChange: (Int) -> Unit) {
    val tokens = paletteTokens
    val range = mode.temperatureRange
    val shown = value.coerceIn(range)
    val spoken = spokenTemp(shown, fahrenheit)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(caption.uppercase(), color = tokens.ink2, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        StepperLayout(
            down = { RoundStep("−", stringResource(R.string.parity_cooler), 40, shown > range.first, "$tag-down") { onChange(shown - 1) } },
            up = { RoundStep("+", stringResource(R.string.parity_warmer), 40, shown < range.last, "$tag-up") { onChange(shown + 1) } }) { valueModifier ->
            Text(tempText(shown, fahrenheit), color = tokens.readableText(tokens.stepColor(mode, shown), tokens.sheet, minimumContrast = 3f), fontSize = 34.sp, fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center, modifier = valueModifier.testTag(tag).semantics {
                    contentDescription = label
                    stateDescription = spoken
                    progressBarRangeInfo = ProgressBarRangeInfo(shown.toFloat(), range.first.toFloat()..range.last.toFloat(),
                        (range.last - range.first - 1).coerceAtLeast(0))
                    setProgress { target -> onChange(target.roundToInt().coerceIn(range)); true }
                })
        }
    }
}

/** One of Stay / Once / Twice / Gently, drawn as falling bars. */
@Composable
private fun ShapeCard(count: Int, label: String, selected: Boolean, color: Color, modifier: Modifier, onClick: () -> Unit) {
    val tokens = paletteTokens
    val shape = RoundedCornerShape(16.dp)
    Column(modifier.clip(shape).background(if (selected) tokens.tint(color) else tokens.surface2)
        .then(if (selected) Modifier.border(2.dp,
            tokens.readableText(color, tokens.tint(color).compositeOver(tokens.sheet), minimumContrast = 3f), shape) else Modifier)
        .clickable(role = Role.RadioButton, onClick = onClick).semantics { this.selected = selected }
        .testTag("middle-shape-$count").padding(vertical = 12.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.height(30.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(count) { i ->
                val fraction = if (count == 1) 1f else 1f - i * (.6f / (count - 1))
                Box(Modifier.width(if (count == 1) 56.dp else (44 / count + 4).dp).fillMaxHeight(fraction)
                    .clip(RoundedCornerShape(3.dp)).background(color.copy(alpha = 1f - i * .08f)))
            }
        }
        Text(label, color = tokens.ink, fontSize = 14.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold, textAlign = TextAlign.Center)
    }
}

/** The middle's steps as bars sized by length and temperature, with their start times beneath. */
@Composable
private fun MiddleBars(steps: List<PlannedStep>, fahrenheit: Boolean) {
    val tokens = paletteTokens
    val temps = steps.map { it.step.temperature }
    val low = temps.min()
    val high = temps.max()
    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 6.dp).clearAndSetSemantics { }) {
        val total = steps.sumOf { it.minutes.coerceAtLeast(1) }.toFloat()
        val barWidth = maxWidth - 6.dp * (steps.size - 1)
        // Duration stays proportional. Put labels beneath the plot when a short step or large text
        // cannot fit a temperature and both boundary times in its bar's share of the width.
        val separateLabels = LocalConfiguration.current.fontScale >= 1.5f ||
            steps.any { barWidth * (it.minutes.coerceAtLeast(1) / total) < 90.dp }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth().height(120.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            steps.forEach { step ->
                val fill = tokens.stepColor(step.step.mode, step.step.temperature)
                val fraction = if (high == low) 1f else .55f + .45f * (step.step.temperature - low) / (high - low).toFloat()
                Box(Modifier.weight(step.minutes.coerceAtLeast(1).toFloat()).fillMaxHeight(fraction).clip(RoundedCornerShape(8.dp))
                    .background(fill).padding(8.dp)) {
                    if (!separateLabels) Text(tempText(step.step.temperature, fahrenheit), color = if (fill.luminance() > .179f) Color.Black else Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
            }
        }
        if (separateLabels) steps.forEach { step ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.width(4.dp).height(24.dp).background(tokens.stepColor(step.step.mode, step.step.temperature)))
                Text(stringResource(R.string.parity_plot_time_range, Format.clock(step.startsAt),
                    Format.clock(step.startsAt.plusMinutes(step.minutes.toLong()))),
                    color = tokens.ink2, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Text(tempText(step.step.temperature, fahrenheit), color = tokens.ink, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            steps.forEachIndexed { index, step ->
                Row(Modifier.weight(step.minutes.coerceAtLeast(1).toFloat())) {
                    Text(Format.clock(step.startsAt), color = tokens.ink2, fontSize = 12.sp, maxLines = 1, modifier = Modifier.weight(1f))
                    if (index == steps.lastIndex) Text(Format.clock(step.startsAt.plusMinutes(step.minutes.toLong())), color = tokens.ink2, fontSize = 12.sp, maxLines = 1)
                }
            }
        }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModeChips(selected: Mode, onSelect: (Mode) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PHASE_MODES.forEach { FilterChip(selected == it, { onSelect(it) }, label = { ModeLabel(it) }, modifier = Modifier.heightIn(min = 48.dp), colors = nocturneFilterChipColors()) }
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
private fun FanSlider(value: Int, label: String, limits: ProfileLimits = ProfileLimits(), mode: Mode, onChange: (Int) -> Unit) {
    val low = ((limits.minFan + 4) / 5) * 5
    val high = (limits.maxFan / 5) * 5
    val hasRange = low < high
    val display = if (low <= high) legalFan(value, limits) else value.coerceIn(5, 100)
    NocturneSlider(
        display.toFloat(),
        { onChange(legalFan(it.roundToInt(), limits)) },
        valueRange = if (hasRange) low.toFloat()..high.toFloat() else 5f..100f,
        enabled = hasRange,
        steps = if (hasRange) (high - low) / 5 - 1 else 18,
        colors = fanSliderColors(mode),
        modifier = Modifier.sliderSemantics(label, spokenPercent(value)),
    )
}

@Composable
private fun MinuteSlider(value: Int, label: String, minimum: Int, onChange: (Int) -> Unit) =
    NocturneSlider(value.coerceIn(minimum, 720).toFloat(), { onChange(it.roundToInt().coerceIn(minimum, 720)) },
        valueRange = minimum.toFloat()..720f, steps = 720 - minimum - 1,
        modifier = Modifier.sliderSemantics(label, spokenMinutes(value)), colors = amountSliderColors())

@Composable
fun SwitchRow(title: String, detail: String?, checked: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Column(modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
        .semantics { contentDescription = title }) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = paletteTokens.ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            Switch(checked, onCheckedChange = null, enabled = enabled, colors = nocturneSwitchColors())
        }
        detail?.let { Text(it, color = paletteTokens.ink2, fontSize = 13.5.sp) }
    }
}

/** The design's switch: the accent track when on, a quiet track when off. */
@Composable
internal fun nocturneSwitchColors() = paletteTokens.let { tokens ->
    SwitchDefaults.colors(checkedThumbColor = tokens.onPrimary, checkedTrackColor = tokens.accent, checkedBorderColor = tokens.accent,
        uncheckedThumbColor = tokens.ink2, uncheckedTrackColor = tokens.surface2, uncheckedBorderColor = tokens.surface2)
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

