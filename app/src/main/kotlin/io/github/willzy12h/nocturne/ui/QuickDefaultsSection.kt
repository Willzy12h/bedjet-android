package io.github.willzy12h.nocturne.ui

import androidx.compose.ui.res.pluralStringResource
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
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
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.link.LinkState
import io.github.willzy12h.nocturne.core.protocol.DeviceStatus
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.core.store.ActionPreset
import io.github.willzy12h.nocturne.core.store.QuickDefaults
import io.github.willzy12h.nocturne.core.store.Settings
import io.github.willzy12h.nocturne.night.NightActions.Command
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
    val status by model.status.collectAsState()
    val live by model.hasLiveStatus.collectAsState()
    val link by model.linkState.collectAsState()
    val runtime by model.runtime.collectAsState()
    val night by model.activeNight.collectAsState()
    val owner by model.commandBusy.collectAsState()
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
            subtitle = pluralStringResource(R.plurals.uat_quick_summary, preset.minutes, tempText(preset.temperature, settings.fahrenheit),
                preset.fanPercent, preset.minutes), modifier = Modifier.fillMaxWidth().testTag("quick-default-${mode.name}")) {
                if (!QuickDefaults.valid(preset)) Text(stringResource(R.string.uat_quick_invalid),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                TextButton(onClick = { editing = preset }) {
                    Text(stringResource(R.string.uat_quick_edit, stringResource(Format.modeNameRes(mode))))
                }
        }
    }
    Text(stringResource(R.string.uat_quick_off_help), style = MaterialTheme.typography.bodySmall)
    editing?.let { QuickPresetEditor(model, settings, it) { editing = null } }
}

/** One quick preset's editor: from Settings, the Home Edit list, or holding its tile on Home (UX-07). */
@Composable
internal fun QuickPresetEditor(model: AppViewModel, settings: Settings, original: ActionPreset, onClose: () -> Unit) {
    val context = LocalContext.current
    run {
        var draft by remember(original) { mutableStateOf(original) }
        val valid = QuickDefaults.valid(draft)
        AlertDialog(onDismissRequest = onClose,
            title = { Text(stringResource(R.string.uat_quick_edit, stringResource(Format.modeNameRes(draft.mode)))) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(presetDetail(context, draft, settings.fahrenheit))
                    if (!valid) Text(stringResource(R.string.uat_quick_invalid), color = MaterialTheme.colorScheme.onSurface)
                    if (draft.mode.usesTemperature) {
                        val range = draft.mode.temperatureRange
                        Text(stringResource(R.string.slider_temp))
                        Slider(draft.temperature.coerceIn(range).toFloat(), { draft = draft.copy(temperature = it.roundToInt()) },
                            valueRange = range.first.toFloat()..range.last.toFloat(), steps = range.last - range.first - 1,
                            modifier = Modifier.sliderSemantics(stringResource(R.string.slider_temp), spokenTemp(draft.temperature, settings.fahrenheit)), colors = nocturneSliderColors())
                    }
                    Text(stringResource(R.string.slider_fan))
                    Slider(draft.fanPercent.coerceIn(5, 100).toFloat(), { draft = draft.copy(fanPercent = (it / 5).roundToInt() * 5) },
                        valueRange = 5f..100f, steps = 18,
                        modifier = Modifier.sliderSemantics(stringResource(R.string.slider_fan), spokenPercent(draft.fanPercent)), colors = nocturneSliderColors())
                    val maximum = QuickDefaults.maximumMinutes(draft).coerceAtLeast(1)
                    Text(stringResource(R.string.slider_minutes))
                    if (maximum > 1) Slider(draft.minutes.coerceIn(1, maximum).toFloat(), { draft = draft.copy(minutes = it.roundToInt()) },
                        valueRange = 1f..maximum.toFloat(), steps = maximum - 2,
                        modifier = Modifier.sliderSemantics(stringResource(R.string.slider_minutes), spokenMinutes(draft.minutes)), colors = nocturneSliderColors())
                }
            },
            confirmButton = { TextButton(onClick = {
                if (QuickDefaults.valid(draft)) { model.updateSettings { QuickDefaults.withPreset(it, draft) }; onClose() }
            }, enabled = valid) { Text(stringResource(R.string.uat_quick_save)) } },
            dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.uat_quick_cancel)) } })
    }
}
