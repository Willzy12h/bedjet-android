package io.github.willzy12h.nocturne.ui

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.res.pluralStringResource
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import io.github.willzy12h.nocturne.ui.NocturneTextButton as TextButton
import io.github.willzy12h.nocturne.ui.NocturneOutlinedButton as OutlinedButton
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.link.LinkState
import io.github.willzy12h.nocturne.core.protocol.DeviceStatus
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.store.ActionPreset
import io.github.willzy12h.nocturne.core.store.QuickDefaults
import io.github.willzy12h.nocturne.core.store.Settings
import io.github.willzy12h.nocturne.night.NightActions.Command
import io.github.willzy12h.nocturne.core.protocol.Limits
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import kotlin.math.roundToInt

internal fun quickModeLabel(context: Context, preset: ActionPreset, fahrenheit: Boolean): String {
    val name = context.getString(Format.modeNameRes(preset.mode))
    val words = Format.Words { id, args -> context.getString(id, *args) }
    return when {
        !QuickDefaults.valid(preset) -> name // Do not advertise an unsupported duration as executable.
        preset.mode == Mode.TURBO -> context.getString(R.string.uat_quick_minutes, name, preset.minutes)
        preset.mode == Mode.DRY -> name
        else -> context.getString(R.string.uat_quick_temp, name, Format.temp(preset.temperature, fahrenheit, words))
    }
}

internal fun captureModeQuick(model: AppViewModel, preset: ActionPreset): CapturedQuickAction {
    // Keep legacy values intact; only the reviewed, explicitly bounded Temporary choice
    // can run when the original End request is unsafe. The command owner remains authoritative.
    require(preset.mode in QuickDefaults.modes)
    val command = when (preset.mode) { Mode.TURBO -> Command.TURBO; Mode.COOL -> Command.COOL; else -> Command.WARM }
    val request = model.graph.actions.capture(command, requireIdentity = true).copy(actionPreset = preset)
    return requireNotNull(captureQuickAction(model.graph, request))
}

private fun matchesQuick(status: DeviceStatus?, preset: ActionPreset): Boolean = status?.let {
    it.extra?.programStep == 0 && it.remainingSeconds in 1..(preset.minutes * 60 + 120) &&
        it.mode == preset.mode && it.fanPercent == preset.fanPercent &&
        (!preset.mode.usesTemperature || it.targetTemperature == preset.temperature)
} == true

internal fun quickMatches(status: DeviceStatus?, preset: ActionPreset): Boolean = matchesQuick(status, preset)

/** The current receipt, only while nothing else (a night, a slot write, another command) owns the BedJet. */
@Composable
internal fun quickEvidence(model: AppViewModel): DeviceStatus? {
    val status by model.status.collectAsStateWithLifecycle()
    val live by model.hasLiveStatus.collectAsStateWithLifecycle()
    val link by model.linkState.collectAsStateWithLifecycle()
    val runtime by model.runtime.collectAsStateWithLifecycle()
    val night by model.activeNight.collectAsStateWithLifecycle()
    val owner by model.commandBusy.collectAsStateWithLifecycle()
    val slotWrite = runtime.slotWrite
    // A confirmed Boost/pin journal remains for recovery; it must not hide a later fresh manual receipt.
    val slotSettled = slotWrite == null || slotWrite.confidence == io.github.willzy12h.nocturne.core.night.Confidence.CONFIRMED
    return status.takeIf { live && link == LinkState.READY && slotSettled && owner.runningLabel == null &&
        (it?.mode == Mode.STANDBY || night == null || (night?.trackingManual == true &&
            night?.confidence == io.github.willzy12h.nocturne.core.night.Confidence.CONFIRMED)) }
}

@Composable
internal fun QuickDefaultsSection(model: AppViewModel, settings: Settings) {
    val context = LocalContext.current
    var editing by remember { mutableStateOf<ActionPreset?>(null) }
    QuickDefaults.modes.forEach { mode ->
        val preset = QuickDefaults.resolve(settings, mode)
        StripeTile(stripe = paletteTokens.stepColor(preset.mode, preset.temperature),
            title = quickModeLabel(context, preset, settings.fahrenheit),
            subtitle = stringResource(R.string.quick_summary, tempText(preset.temperature, settings.fahrenheit),
                preset.fanPercent, runLengthText(preset.minutes)), modifier = Modifier.fillMaxWidth().testTag("quick-default-${mode.name}")) {
                if (!QuickDefaults.valid(preset)) Text(stringResource(R.string.uat_quick_invalid),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                TextButton(onClick = { editing = preset }) {
                    Text(stringResource(R.string.uat_quick_edit, stringResource(Format.modeNameRes(mode))))
                }
        }
    }
    var editingBoost by remember { mutableStateOf(false) }
    StripeTile(stripe = BoostColor, title = stringResource(R.string.shortcut_boost),
        subtitle = io.github.willzy12h.nocturne.night.boostDescription(context, settings.boostMinutes),
        modifier = Modifier.fillMaxWidth().testTag("quick-default-BOOST")) {
        TextButton(onClick = { editingBoost = true }) { Text(stringResource(R.string.uat_quick_edit, stringResource(R.string.shortcut_boost))) }
    }
    Text(stringResource(R.string.uat_quick_off_help), style = MaterialTheme.typography.bodySmall)
    editing?.let { QuickPresetEditor(model, settings, it) { editing = null } }
    if (editingBoost) BoostEditor(model, settings) { editingBoost = false }
}

/**
 * Boost's editor: Turbo always runs for the BedJet's ten-minute limit; the person chooses how long Heat
 * follows it (none to twenty minutes). Saving changes only the setting and never sends anything.
 */
@Composable
internal fun BoostEditor(model: AppViewModel, settings: Settings, onClose: () -> Unit) {
    val context = LocalContext.current
    val turbo = io.github.willzy12h.nocturne.core.protocol.Limits.TURBO_MINUTES
    var minutes by remember { mutableIntStateOf(settings.boostMinutes.coerceIn(turbo, 30)) }
    AlertDialog(onDismissRequest = onClose,
        title = { Text(stringResource(R.string.uat_quick_edit, stringResource(R.string.shortcut_boost))) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).testTag("boost-editor"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(pluralStringResource(R.plurals.boost_editor_help, turbo, turbo))
                Text(io.github.willzy12h.nocturne.night.boostDescription(context, minutes), fontWeight = FontWeight.Bold)
                // The whole Boost to scale: Turbo's fixed ten minutes in the deepest Ember, then the chosen Heat
                // in a lighter one, each labelled, so the two parts never read as one block.
                val tokens = paletteTokens
                val turboColour = tokens.ember[3]
                val heatColour = tokens.ember[1]
                androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().height(34.dp).testTag("boost-editor-bar")
                    .clearAndSetSemantics { }, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    androidx.compose.foundation.layout.Box(Modifier.weight(turbo.toFloat()).fillMaxHeight()
                        .background(turboColour, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
                        Text(stringResource(R.string.boost_bar_turbo, turbo), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                    if (minutes > turbo) androidx.compose.foundation.layout.Box(Modifier.weight((minutes - turbo).toFloat()).fillMaxHeight()
                        .background(heatColour, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                        // Five minutes of Heat is too narrow for its word; the colour and the line above name it.
                        val heat = minutes - turbo
                        Text(stringResource(if (heat < 10) R.string.boost_bar_heat_compact else R.string.boost_bar_heat, heat), color = Color(0xFF12152A), fontSize = 13.sp,
                            fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Clip)
                    }
                    if (minutes < 30) androidx.compose.foundation.layout.Box(Modifier.weight((30 - minutes).toFloat()).fillMaxHeight()
                        .border(1.dp, tokens.ink3.copy(alpha = .4f), RoundedCornerShape(8.dp)))
                }
                Text(stringResource(R.string.boost_editor_heat))
                ScaledSlider((minutes - turbo).toFloat(), { minutes = turbo + ((it / 5).roundToInt() * 5) },
                    range = 0f..20f, steps = 3, enabled = true,
                    modifier = Modifier.sliderSemantics(stringResource(R.string.boost_editor_heat), spokenMinutes(minutes - turbo)),
                    ticks = listOf(0, 10, 20).map { it.toFloat() to stringResource(R.string.minutes_short, it) }, tag = "boost-heat",
                    onValueChangeFinished = {}, colors = sliderColors(heatColour))
            }
        },
        confirmButton = { TextButton(onClick = { model.updateSettings { it.copy(boostMinutes = minutes) }; onClose() }) {
            Text(stringResource(R.string.uat_quick_save)) } },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.uat_quick_cancel)) } })
}

/** One quick preset's editor: from Settings, the Home Edit list, or holding its tile on Home (UX-07). */
@Composable
internal fun QuickPresetEditor(model: AppViewModel, settings: Settings, original: ActionPreset, onClose: () -> Unit) {
    val context = LocalContext.current
    run {
        var draft by remember(original) { mutableStateOf(original) }
        // The time as typed; what is saved is never longer than these settings allow.
        val shownMinutes = spokenMaximum(original.minutes.coerceAtLeast(1)).takeIf { original.minutes == Limits.MAX_STEP_MINUTES } ?: original.minutes
        var hoursText by remember(original) { mutableStateOf((shownMinutes / 60).toString()) }
        var minutesText by remember(original) { mutableStateOf((shownMinutes % 60).toString()) }
        val typed = typedMinutes(hoursText, minutesText)
        val maximum = QuickDefaults.maximumMinutes(draft).coerceAtLeast(1)
        val saved = typed?.let { draft.copy(minutes = it.coerceAtMost(maximum)) }
        val valid = saved != null && QuickDefaults.valid(saved)
        AlertDialog(onDismissRequest = onClose,
            title = { Text(stringResource(R.string.uat_quick_edit, stringResource(Format.modeNameRes(draft.mode)))) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(presetDetail(context, saved ?: draft, settings.fahrenheit))
                    if (!valid && typed != null) Text(stringResource(R.string.uat_quick_invalid), color = MaterialTheme.colorScheme.onSurface)
                    if (draft.mode.usesTemperature) {
                        val range = draft.mode.temperatureRange
                        Text(stringResource(R.string.slider_temp))
                        ScaledSlider(draft.temperature.coerceIn(range).toFloat(), { draft = draft.copy(temperature = it.roundToInt()) },
                            range = range.first.toFloat()..range.last.toFloat(), steps = range.last - range.first - 1, enabled = true,
                            modifier = Modifier.sliderSemantics(stringResource(R.string.slider_temp), spokenTemp(draft.temperature, settings.fahrenheit)),
                            ticks = temperatureTicks(range, settings.fahrenheit), tag = "quick-temperature", onValueChangeFinished = {},
                            colors = temperatureSliderColors(draft.mode, draft.temperature))
                    }
                    Text(stringResource(R.string.slider_fan))
                    ScaledSlider(draft.fanPercent.coerceIn(5, 100).toFloat(), { draft = draft.copy(fanPercent = (it / 5).roundToInt() * 5) },
                        range = 5f..100f, steps = 18, enabled = true,
                        modifier = Modifier.sliderSemantics(stringResource(R.string.slider_fan), spokenPercent(draft.fanPercent)),
                        ticks = fanTicks(), tag = "quick-fan", onValueChangeFinished = {}, colors = fanSliderColors(draft.mode))
                    Text(stringResource(R.string.slider_minutes))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(hoursText, { hoursText = it.filter(Char::isDigit).take(3) },
                            label = { Text(stringResource(R.string.remote_custom_hours)) }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), colors = nocturneTextFieldColors(),
                            modifier = Modifier.weight(1f).testTag("quick-hours"))
                        OutlinedTextField(minutesText, { minutesText = it.filter(Char::isDigit).take(2) },
                            label = { Text(stringResource(R.string.remote_custom_minutes)) }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), colors = nocturneTextFieldColors(),
                            modifier = Modifier.weight(1f).testTag("quick-minutes"))
                    }
                    durationLimitNote(draft.mode, typed, maximum)?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("quick-duration-note"))
                    }
                }
            },
            confirmButton = { TextButton(onClick = {
                if (saved != null && QuickDefaults.valid(saved)) { model.updateSettings { QuickDefaults.withPreset(it, saved) }; onClose() }
            }, enabled = valid) { Text(stringResource(R.string.uat_quick_save)) } },
            dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.uat_quick_cancel)) } })
    }
}

/** Hours and minutes as typed, or null when either is not a number or the minutes are not 0 to 59. */
internal fun typedMinutes(hours: String, minutes: String): Int? {
    val h = hours.ifEmpty { "0" }.toIntOrNull() ?: return null
    val m = minutes.ifEmpty { "0" }.toIntOrNull() ?: return null
    return (h * 60 + m).takeIf { m in 0..59 && it >= 1 }
}

/** A run length, with the BedJet's 11 h 59 min shown as its 12-hour maximum. */
@Composable
internal fun runLengthText(minutes: Int): String =
    if (minutes == Limits.MAX_STEP_MINUTES) stringResource(R.string.duration_maximum, durationText(spokenMaximum(minutes)))
    else durationText(minutes)

/**
 * Why the time saved is shorter than the time typed: past the BedJet's longest run for this mode, or a hot
 * Heat setting that the BedJet runs for less time. Null when the typed time is used as it is.
 */
@Composable
internal fun durationLimitNote(mode: Mode, typed: Int?, maximum: Int): String? = when {
    typed != null && typed > spokenMaximum(maximum) ->
        stringResource(R.string.duration_set_to_maximum, stringResource(Format.modeNameRes(mode)), durationText(spokenMaximum(maximum)))
    mode == Mode.HEAT && maximum < Limits.MAX_STEP_MINUTES ->
        stringResource(R.string.duration_heat_shorter, durationText(maximum))
    else -> null
}
