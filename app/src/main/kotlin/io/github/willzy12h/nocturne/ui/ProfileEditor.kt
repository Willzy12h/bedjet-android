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
            title = { Text("Discard changes?") },
            text = { Text("Your edits to ${original.name} haven't been saved.") },
            confirmButton = { TextButton(onClick = { confirmDiscard = false; onDone() }) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") } },
        )
    }
    if (askApplyTonight) {
        AlertDialog(
            onDismissRequest = { askApplyTonight = false },
            title = { Text("Update tonight too?") },
            text = { Text("${original.name} is running now. Your changes can wait for the next night, or replace the rest of tonight on the BedJet.") },
            confirmButton = {
                TextButton(onClick = { model.saveProfileThenReapply(profile); askApplyTonight = false; onDone() }) { Text("Update tonight") }
            },
            dismissButton = { TextButton(onClick = { model.saveProfileForNextNight(profile); askApplyTonight = false; onDone() }) { Text("Next night") } },
        )
    }

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = leave) { Icon(Icons.Filled.Close, "Cancel") }
                Text("Edit profile", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                val plan = model.previewPlan(profile)
                Button(onClick = { if (runningTonight && profile != original) askApplyTonight = true else { model.saveProfile(profile); onDone() } }, enabled = plan.isSuccess && profile.name.isNotBlank()) { Text("Save") }
            }
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (runningTonight) {
                    Text(
                        "This profile is running tonight. When you save, you can choose to update tonight or start with the next night.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                model.previewPlan(profile).fold(
                    onSuccess = { plan ->
                        Text("Tonight at ${Format.clock(plan.start)}: ${plan.steps.size} of 32 BedJet steps", style = MaterialTheme.typography.labelLarge)
                        NightCurve(plan)
                    },
                    onFailure = { Text(it.message.orEmpty(), color = MaterialTheme.colorScheme.error) },
                )
                OutlinedTextField(profile.name, { profile = profile.copy(name = it.take(40)) }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Season.entries.forEach { s -> FilterChip(profile.season == s, { profile = profile.copy(season = s) }, label = { Text(seasonName(s)) }) }
                }

                Section("Timing") {
                    TimeRow("Usual bedtime", profile.bedtimeMinute) { profile = profile.copy(bedtimeMinute = it) }
                    SwitchRow("Wake with my alarm", "Uses the next Samsung or Google Clock alarm when there is one.", profile.wakeSource.useAlarm) {
                        profile = profile.copy(wakeSource = profile.wakeSource.copy(useAlarm = it))
                    }
                    TimeRow(if (profile.wakeSource.useAlarm) "Wake time when there's no alarm" else "Wake time", profile.wakeSource.defaultWakeMinute) {
                        profile = profile.copy(wakeSource = profile.wakeSource.copy(defaultWakeMinute = it))
                    }
                    Text("Default for these days", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        DayOfWeek.entries.forEach { day ->
                            FilterChip(day.value in profile.days, {
                                profile = profile.copy(days = if (day.value in profile.days) profile.days - day.value else profile.days + day.value)
                            }, label = { Text(day.getDisplayName(TextStyle.SHORT, Locale.getDefault())) })
                        }
                    }
                }

                Section("Limits") {
                    Text("Nothing in this profile, or a snooze or extend while it runs, goes outside these.", style = MaterialTheme.typography.bodySmall)
                    val limits = profile.limits
                    Text("Fan ${limits.minFan}–${limits.maxFan}%")
                    RangeSlider(
                        value = limits.minFan.toFloat()..limits.maxFan.toFloat(),
                        onValueChange = { profile = profile.copy(limits = limits.copy(minFan = it.start.round5(), maxFan = it.endInclusive.round5())) },
                        valueRange = 5f..100f, steps = 18,
                    )
                    Text("Temperature ${Format.temp(limits.minTemp, f)}–${Format.temp(limits.maxTemp, f)}")
                    RangeSlider(
                        value = limits.minTemp.toFloat()..limits.maxTemp.toFloat(),
                        onValueChange = { profile = profile.copy(limits = limits.copy(minTemp = it.start.roundToInt(), maxTemp = it.endInclusive.roundToInt())) },
                        valueRange = 38f..86f, steps = 47,
                    )
                    TextButton(onClick = { profile = profile.copy(limits = ProfileLimits()) }) { Text("Reset limits") }
                }

                PhaseList("Start", "Runs as soon as the night starts.", profile.start, f) { profile = profile.copy(start = it) }

                Section("Middle") {
                    Text("Fills the time between Start and Wake-up, moving gently from the first values to the second.", style = MaterialTheme.typography.bodySmall)
                    val m = profile.middle
                    ModeChips(m.mode) { profile = profile.copy(middle = m.copy(mode = it, tempStart = it.clampTemp(m.tempStart), tempEnd = it.clampTemp(m.tempEnd))) }
                    if (m.mode.usesFan) {
                        Labelled("Fan at the start: ${m.fanStart}%") { FanSlider(m.fanStart) { profile = profile.copy(middle = m.copy(fanStart = it)) } }
                        Labelled("Fan at the end: ${m.fanEnd}%") { FanSlider(m.fanEnd) { profile = profile.copy(middle = m.copy(fanEnd = it)) } }
                    }
                    if (m.mode.usesTemperature) {
                        Labelled("Temperature at the start: ${Format.temp(m.tempStart, f)}") { TempSlider(m.mode, m.tempStart) { profile = profile.copy(middle = m.copy(tempStart = it)) } }
                        Labelled("Temperature at the end: ${Format.temp(m.tempEnd, f)}") { TempSlider(m.mode, m.tempEnd) { profile = profile.copy(middle = m.copy(tempEnd = it)) } }
                    }
                    Labelled("Steps: ${m.rampSteps}") {
                        Slider(m.rampSteps.toFloat(), { profile = profile.copy(middle = m.copy(rampSteps = it.roundToInt())) }, valueRange = 1f..8f, steps = 6)
                    }
                }

                PhaseList("Wake-up", "Ends exactly at your wake time.", profile.wake, f) { profile = profile.copy(wake = it) }
                PhaseList("After wake", "Keeps going after the wake time, e.g. stay warm while you get up. Optional.", profile.afterWake, f) { profile = profile.copy(afterWake = it) }

                Section("Turbo") {
                    SwitchRow(
                        "Repeat Turbo past 10 minutes",
                        "The BedJet runs Turbo for 10 minutes at a time. Off: carry on as Heat at your top temperature. On: start Turbo again (the BedJet runs the repeat at about 40 °C).",
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
                        Text("${Format.modeName(phase.mode)} · ${Format.duration(phase.minutes)}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = { onChange(phases.filterIndexed { i, _ -> i != index }) }) { Text("Remove") }
                    }
                    fun update(p: Phase) = onChange(phases.mapIndexed { i, old -> if (i == index) p else old })
                    ModeChips(phase.mode) { update(phase.copy(mode = it, temperature = it.clampTemp(phase.temperature))) }
                    if (phase.mode.usesTemperature) Labelled("Temperature ${Format.temp(phase.temperature, f)}") { TempSlider(phase.mode, phase.temperature) { update(phase.copy(temperature = it)) } }
                    if (phase.mode.usesFan) Labelled("Fan ${phase.fanPercent}%") { FanSlider(phase.fanPercent) { update(phase.copy(fanPercent = it)) } }
                    Labelled("Length ${Format.duration(phase.minutes)}") {
                        Slider(phase.minutes.toFloat(), { update(phase.copy(minutes = (it / 5).roundToInt() * 5)) }, valueRange = 5f..240f)
                    }
                }
            }
        }
        TextButton(onClick = { onChange(phases + Phase(Mode.COOL, 44, 40, 30)) }) { Text("Add phase") }
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
        PHASE_MODES.forEach { FilterChip(selected == it, { onSelect(it) }, label = { Text(Format.modeName(it)) }) }
    }
}

@Composable
private fun FanSlider(value: Int, onChange: (Int) -> Unit) =
    Slider(value.toFloat(), { onChange(it.round5()) }, valueRange = 5f..100f, steps = 18)

@Composable
private fun TempSlider(mode: Mode, value: Int, onChange: (Int) -> Unit) {
    val range = mode.temperatureRange
    Slider(value.coerceIn(range).toFloat(), { onChange(it.roundToInt()) }, valueRange = range.first.toFloat()..range.last.toFloat(), steps = (range.last - range.first - 1).coerceAtLeast(0))
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

