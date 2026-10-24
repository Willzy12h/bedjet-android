package io.github.willzy12h.nocturne.ui

import android.app.TimePickerDialog
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
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.willzy12h.nocturne.core.plan.Phase
import io.github.willzy12h.nocturne.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import io.github.willzy12h.nocturne.core.plan.Profile
import io.github.willzy12h.nocturne.core.plan.ProfileLimits
import io.github.willzy12h.nocturne.core.plan.Season
import io.github.willzy12h.nocturne.core.plan.TurboChain
import io.github.willzy12h.nocturne.core.protocol.Mode
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

private val PHASE_MODES = listOf(Mode.COOL, Mode.HEAT, Mode.EXTENDED_HEAT, Mode.TURBO, Mode.DRY, Mode.WAIT)

/** Edits every part of a profile, with a live preview of tonight's night and any reason the BedJet would refuse it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileEditor(model: AppViewModel, original: Profile, onDone: () -> Unit,
    initialDraft: Profile? = null, onDraftChange: (Profile) -> Unit = {}) {
    var profile by remember(original.id) { mutableStateOf(initialDraft ?: original) }
    LaunchedEffect(profile) { onDraftChange(profile) }
    val settings by model.settings.collectAsState()
    val f = settings.fahrenheit
    val night by model.activeNight.collectAsState()
    val runningTonight = night?.profileId == original.id
    var confirmDiscard by remember { mutableStateOf(false) }
    var askApplyTonight by remember { mutableStateOf(false) }
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
    if (askApplyTonight) {
        AlertDialog(
            onDismissRequest = { askApplyTonight = false },
            title = { Text(stringResource(R.string.editor_update_tonight_title)) },
            text = { Text(stringResource(R.string.editor_update_tonight_text, original.name)) },
            confirmButton = {
                TextButton(onClick = { model.saveProfileThenReapply(profile); askApplyTonight = false; onDone() }) { Text(stringResource(R.string.editor_update_tonight)) }
            },
            dismissButton = { TextButton(onClick = { model.saveProfileForNextNight(profile); askApplyTonight = false; onDone() }) { Text(stringResource(R.string.editor_next_night)) } },
        )
    }

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = leave) { Icon(Icons.Filled.Close, stringResource(R.string.feature_cancel)) }
                Text(stringResource(R.string.editor_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                val plan = model.previewPlan(profile)
                Button(onClick = { if (runningTonight && profile != original) askApplyTonight = true else { model.saveProfile(profile); onDone() } }, enabled = plan.isSuccess && profile.name.isNotBlank()) { Text(stringResource(R.string.editor_save)) }
            }
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (runningTonight) {
                    Text(
                        stringResource(R.string.editor_running_tonight),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                model.previewPlan(profile).fold(
                    onSuccess = { plan ->
                        Text(stringResource(R.string.editor_preview, Format.clock(plan.start), plan.steps.size), style = MaterialTheme.typography.labelLarge)
                        NightCurve(plan)
                    },
                    onFailure = { Text(it.message.orEmpty(), color = MaterialTheme.colorScheme.error) },
                )
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
                        onValueChange = { profile = profile.copy(limits = limits.copy(minFan = it.start.round5(), maxFan = it.endInclusive.round5())) },
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
                    TextButton(onClick = { profile = profile.copy(limits = ProfileLimits()) }) { Text(stringResource(R.string.editor_reset_limits)) }
                }

                PhaseList(stringResource(R.string.block_start), stringResource(R.string.editor_start_help), profile.start, f) { profile = profile.copy(start = it) }

                Section(stringResource(R.string.block_middle)) {
                    Text(stringResource(R.string.editor_middle_help), style = MaterialTheme.typography.bodySmall)
                    val m = profile.middle
                    ModeChips(m.mode) { profile = profile.copy(middle = m.copy(mode = it, tempStart = it.clampTemp(m.tempStart), tempEnd = it.clampTemp(m.tempEnd))) }
                    if (m.mode.usesFan) {
                        Labelled(stringResource(R.string.editor_fan_start, m.fanStart)) { FanSlider(m.fanStart, stringResource(R.string.slider_fan_start)) { profile = profile.copy(middle = m.copy(fanStart = it)) } }
                        Labelled(stringResource(R.string.editor_fan_end, m.fanEnd)) { FanSlider(m.fanEnd, stringResource(R.string.slider_fan_end)) { profile = profile.copy(middle = m.copy(fanEnd = it)) } }
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

                PhaseList(stringResource(R.string.block_wake), stringResource(R.string.editor_wake_help), profile.wake, f) { profile = profile.copy(wake = it) }
                PhaseList(stringResource(R.string.block_after_wake), stringResource(R.string.editor_after_wake_help), profile.afterWake, f) { profile = profile.copy(afterWake = it) }

                Section(stringResource(R.string.mode_turbo)) {
                    SwitchRow(
                        stringResource(R.string.editor_repeat_turbo),
                        stringResource(R.string.editor_repeat_turbo_help),
                        profile.turboChain == TurboChain.REPEAT_TURBO,
                    ) { profile = profile.copy(turboChain = if (it) TurboChain.REPEAT_TURBO else TurboChain.TURBO_THEN_HEAT) }
                }
            }
        }
    }
}

@Composable
private fun PhaseList(title: String, hint: String, phases: List<Phase>, f: Boolean, onChange: (List<Phase>) -> Unit) {
    Section(title) {
        Text(hint, style = MaterialTheme.typography.bodySmall)
        phases.forEachIndexed { index, phase ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${modeName(phase.mode)} · ${durationText(phase.minutes)}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = { onChange(phases.filterIndexed { i, _ -> i != index }) }) { Text(stringResource(R.string.editor_remove)) }
                    }
                    fun update(p: Phase) = onChange(phases.mapIndexed { i, old -> if (i == index) p else old })
                    ModeChips(phase.mode) { update(phase.copy(mode = it, temperature = it.clampTemp(phase.temperature))) }
                    if (phase.mode.usesTemperature) Labelled(stringResource(R.string.label_temperature, tempText(phase.temperature, f))) { TempSlider(phase.mode, phase.temperature, stringResource(R.string.slider_temp), f) { update(phase.copy(temperature = it)) } }
                    if (phase.mode.usesFan) Labelled(stringResource(R.string.label_fan, phase.fanPercent)) { FanSlider(phase.fanPercent, stringResource(R.string.slider_fan)) { update(phase.copy(fanPercent = it)) } }
                    Labelled(stringResource(R.string.editor_length, durationText(phase.minutes))) {
                        Slider(phase.minutes.toFloat(), { update(phase.copy(minutes = (it / 5).roundToInt() * 5)) }, valueRange = 5f..240f,
                            modifier = Modifier.sliderSemantics(stringResource(R.string.slider_length), spokenMinutes(phase.minutes)))
                    }
                }
            }
        }
        TextButton(onClick = { onChange(phases + Phase(Mode.COOL, 44, 40, 30)) }) { Text(stringResource(R.string.editor_add_phase)) }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        PHASE_MODES.forEach { FilterChip(selected == it, { onSelect(it) }, label = { Text(modeName(it)) }) }
    }
}

@Composable
private fun FanSlider(value: Int, label: String, onChange: (Int) -> Unit) =
    Slider(value.toFloat(), { onChange(it.round5()) }, valueRange = 5f..100f, steps = 18,
        modifier = Modifier.sliderSemantics(label, spokenPercent(value)))

@Composable
private fun TempSlider(mode: Mode, value: Int, label: String, fahrenheit: Boolean, onChange: (Int) -> Unit) {
    val range = mode.temperatureRange
    Slider(value.coerceIn(range).toFloat(), { onChange(it.roundToInt()) }, valueRange = range.first.toFloat()..range.last.toFloat(), steps = (range.last - range.first - 1).coerceAtLeast(0),
        modifier = Modifier.sliderSemantics(label, spokenTemp(value.coerceIn(range), fahrenheit)))
}

@Composable
fun SwitchRow(title: String, detail: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
        .semantics { contentDescription = title }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        Switch(checked, onCheckedChange = null)
    }
}

@Composable
fun TimeRow(label: String, minuteOfDay: Int, onChange: (Int) -> Unit) {
    val context = LocalContext.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        TextButton(onClick = {
            TimePickerDialog(context, { _, h, m -> onChange(h * 60 + m) }, minuteOfDay / 60, minuteOfDay % 60, true).show()
        }) { Text(Format.clockOfDay(minuteOfDay)) }
    }
}

private fun Float.round5() = ((this / 5).roundToInt() * 5).coerceIn(5, 100)
private fun Mode.clampTemp(value: Int) = value.coerceIn(temperatureRange)

