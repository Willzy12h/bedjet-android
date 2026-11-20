package io.github.willzy12h.nocturne.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.plan.Block
import io.github.willzy12h.nocturne.core.plan.NapPreset
import io.github.willzy12h.nocturne.core.plan.NapProgramme
import io.github.willzy12h.nocturne.core.plan.Phase
import io.github.willzy12h.nocturne.core.plan.Profile
import io.github.willzy12h.nocturne.core.plan.ProfileLimits
import io.github.willzy12h.nocturne.core.protocol.Mode
import io.github.willzy12h.nocturne.night.planMessage
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val napDraftSaver = Saver<NapPreset, String>(
    save = { Json.encodeToString(it) },
    restore = { runCatching { Json.decodeFromString<NapPreset>(it) }.getOrNull() },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NapPresetEditor(preset: NapPreset, selectedProfile: Profile?, fahrenheit: Boolean, now: ZonedDateTime,
    onSave: (NapPreset) -> Unit, onDismiss: () -> Unit) {
    var draft by rememberSaveable(preset, stateSaver = napDraftSaver) { mutableStateOf(preset) }
    var hours by rememberSaveable(preset) { mutableStateOf((preset.durationMinutes / 60).toString()) }
    var minutes by rememberSaveable(preset) { mutableStateOf((preset.durationMinutes % 60).toString()) }
    var adding by remember { mutableStateOf<Block?>(null) }
    var focused by remember { mutableStateOf<EditorPhaseSelection?>(null) }
    var focusRequest by remember { mutableIntStateOf(0) }
    val h = hours.toIntOrNull() ?: 0
    val m = minutes.toIntOrNull() ?: 0
    val duration = (h * 60 + m).takeIf { m < 60 && it in 1..NapProgramme.MAX_MINUTES }
    val candidate = remember(draft, duration) { duration?.let { draft.copy(durationMinutes = it) } }
    val preview = remember(candidate, now.toEpochSecond() / 60, selectedProfile) {
        candidate?.let { runCatching { NapProgramme.plan(it, now, selectedProfile = selectedProfile) } }
    }
    val context = LocalContext.current
    val plan = preview?.getOrNull()
    val problem = when {
        duration == null -> stringResource(R.string.nap_editor_duration_invalid)
        plan == null -> preview?.exceptionOrNull()?.planMessage(context) ?: stringResource(R.string.nap_editor_invalid)
        else -> null
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.nap_editor_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                EditorGroupHeader(stringResource(R.string.nap_editor_duration), null)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(hours, { hours = it.filter(Char::isDigit).take(3) },
                        Modifier.weight(1f).testTag("nap-hours"), label = { Text(stringResource(R.string.parity_hours)) },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    OutlinedTextField(minutes, { minutes = it.filter(Char::isDigit).take(2) },
                        Modifier.weight(1f).testTag("nap-minutes"), label = { Text(stringResource(R.string.parity_minutes)) },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
                if (plan != null) {
                    NightBar(plan, null, Modifier.testTag("nap-preview"), height = 28.dp)
                    fun time(at: ZonedDateTime) = if (at.toLocalDate() == now.toLocalDate()) Format.clock(at)
                        else at.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT))
                    Text(if (plan.end == plan.wake) stringResource(R.string.nap_editor_finishes, time(plan.end))
                        else stringResource(R.string.nap_editor_clock_preview, time(plan.wake), time(plan.end)))
                    val opening = if (draft.useProfileOpening) selectedProfile?.start.orEmpty() else draft.opening
                    if (duration != null && opening.sumOf { it.minutes } > duration)
                        Text(stringResource(R.string.nap_editor_duration_clipped))
                } else if (problem != null) Text(problem, color = paletteTokens.readableText(paletteTokens.bad))
                SwitchRow(stringResource(R.string.nap_editor_opening_only), stringResource(R.string.nap_editor_opening_detail),
                    draft.useProfileOpening, enabled = selectedProfile != null || draft.useProfileOpening) {
                    draft = draft.copy(useProfileOpening = it)
                }
                if (!draft.useProfileOpening) {
                    PhaseList(Block.START, stringResource(R.string.parity_group_start), draft.opening, fahrenheit, plan,
                        focused, focusRequest, ProfileLimits(), onAdd = { adding = Block.START }) {
                        draft = draft.copy(opening = it)
                    }
                    NapUntilWake(draft.untilWake, fahrenheit) { draft = draft.copy(untilWake = it) }
                    PhaseList(Block.AFTER_WAKE, stringResource(R.string.parity_group_after), draft.afterWake, fahrenheit,
                        plan, focused, focusRequest, ProfileLimits(), onAdd = { adding = Block.AFTER_WAKE }) {
                        draft = draft.copy(afterWake = it)
                    }
                }
            }
        },
        confirmButton = {
            NocturneTextButton(onClick = { candidate?.let(onSave) }, enabled = plan != null,
                modifier = Modifier.testTag("save-nap-preset")) { Text(stringResource(R.string.editor_save)) }
        },
        dismissButton = { NocturneTextButton(onClick = onDismiss) { Text(stringResource(R.string.feature_cancel)) } })
    adding?.let { block ->
        ModalBottomSheet(onDismissRequest = { adding = null }, containerColor = paletteTokens.sheet,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            AddStepContent(block, fahrenheit, onCancel = { adding = null }, groups = listOf(Block.START, Block.AFTER_WAKE)) { group, phase ->
                val phases = if (group == Block.START) draft.opening else draft.afterWake
                draft = if (group == Block.START) draft.copy(opening = phases + phase) else draft.copy(afterWake = phases + phase)
                focused = EditorPhaseSelection(group, phases.size)
                focusRequest++
                adding = null
            }
        }
    }
}

@Composable
private fun NapUntilWake(phase: Phase?, fahrenheit: Boolean, onChange: (Phase?) -> Unit) {
    SwitchRow(stringResource(R.string.nap_editor_fill), stringResource(R.string.nap_editor_fill_help), phase != null) { enabled ->
        onChange(if (enabled) Phase(Mode.COOL, 44, 40, 1) else null)
    }
    if (phase == null) return
    val modes = listOf(Mode.HEAT, Mode.EXTENDED_HEAT, Mode.COOL, Mode.DRY)
    Segmented(modes.map { it to modeName(it) }, phase.mode, tag = "nap-fill-mode") { mode ->
        onChange(phase.copy(mode = mode, temperature = phase.temperature.coerceIn(mode.temperatureRange)))
    }
    val temperature = stringResource(R.string.parity_temperature)
    TempStepper(phase.mode, phase.temperature, temperature, temperature, fahrenheit, "nap-fill-temperature",
        Modifier.fillMaxWidth()) { onChange(phase.copy(temperature = it)) }
    EditorGroupHeader(stringResource(R.string.parity_fan), spokenPercent(phase.fanPercent))
    FanSlider(phase.fanPercent, stringResource(R.string.slider_fan), mode = phase.mode) { onChange(phase.copy(fanPercent = it)) }
}
