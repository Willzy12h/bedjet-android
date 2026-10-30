package io.github.willzy12h.nocturne.ui

import androidx.compose.ui.res.pluralStringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import kotlin.math.roundToInt
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.plan.Block
import io.github.willzy12h.nocturne.core.plan.FanShape
import io.github.willzy12h.nocturne.core.plan.Middle
import io.github.willzy12h.nocturne.core.plan.Phase
import io.github.willzy12h.nocturne.core.protocol.Mode

/** The design's editor group heading: "START" on the left, its total ("40 MIN") on the right. */
@Composable
internal fun EditorGroupHeader(label: String, total: String?) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        SectionLabel(label, Modifier.weight(1f))
        total?.let { SectionLabel(it) }
    }
}

/** "40 min", "7 h 20": the compact lengths of the editor's headings and chips. */
@Composable
internal fun compactDuration(minutes: Int): String = when {
    minutes < 60 -> stringResource(R.string.parity_min, minutes)
    minutes % 60 == 0 -> stringResource(R.string.parity_h, minutes / 60)
    else -> stringResource(R.string.parity_h_m, minutes / 60, minutes % 60)
}

/** The mode icon on a tinted square, as on the editor's step cards. */
@Composable
private fun IconBox(icon: Int, color: Color) {
    Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(color.copy(alpha = .22f)), contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), null, Modifier.size(20.dp), tint = color)
    }
}

internal fun phaseIcon(mode: Mode): Int = when (mode) {
    Mode.EXTENDED_HEAT -> R.drawable.ic_nc_heat
    Mode.WAIT -> R.drawable.ic_nc_moon
    else -> modeIcon(mode)
}

/**
 * One step: grip, icon, "Heat 36°" over "Fan 40%", and its length. Dragging the grip after a long press moves
 * it on release. The parent measures rows and resolves the drop; screen readers move one row at a time.
 */
@Composable
internal fun StepCard(phase: Phase, title: String, subtitle: String, length: String, modifier: Modifier,
    canMoveUp: Boolean, canMoveDown: Boolean, onMove: (Int) -> Unit, onDrop: (Float) -> Unit,
    onHeightChanged: (Int) -> Unit, onClick: () -> Unit) {
    val tokens = paletteTokens
    val temperature = if (phase.mode == Mode.TURBO) 86 else phase.temperature
    val stripe = tokens.stepColor(phase.mode, temperature)
    val shape = RoundedCornerShape(16.dp)
    var drag by remember { mutableFloatStateOf(0f) }
    var lifted by remember { mutableStateOf(false) }
    val largeText = LocalConfiguration.current.fontScale >= 1.5f
    val move by rememberUpdatedState(onMove)
    val drop by rememberUpdatedState(onDrop)
    val moveUp = stringResource(R.string.parity_move_up)
    val moveDown = stringResource(R.string.parity_move_down)
    Row(modifier.fillMaxWidth().onSizeChanged { onHeightChanged(it.height) }
        .zIndex(if (lifted) 1f else 0f)
        .graphicsLayer { if (lifted) { translationY = drag; rotationZ = -2f } }
        .then(if (lifted) Modifier.shadow(14.dp, shape) else Modifier)
        .clip(shape).background(if (lifted) { if (tokens.dark) Color(0xFF262A45) else tokens.surface2 } else tokens.surface)
        .sideStripe(stripe, tokens, width = 5.dp, level = TintLevel.STEP)
        .then(when {
            lifted -> Modifier.border(1.5.dp, stripe, shape)
            tokens.edge != null -> Modifier.border(1.dp, tokens.edge, shape)
            else -> Modifier
        })
        .clickable(role = Role.Button, onClick = onClick)
        .semantics {
            customActions = listOfNotNull(
                CustomAccessibilityAction(moveUp) { move(-1); true }.takeIf { canMoveUp },
                CustomAccessibilityAction(moveDown) { move(1); true }.takeIf { canMoveDown })
        }
        .heightIn(min = 64.dp).padding(start = 14.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(48.dp)
            .pointerInput(Unit) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { lifted = true; drag = 0f },
                    onDragEnd = {
                        val distance = drag
                        lifted = false; drag = 0f
                        drop(distance)
                    },
                    onDragCancel = { lifted = false; drag = 0f },
                ) { change, amount ->
                    change.consume()
                    drag += amount.y
                }
            }, contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_nc_grip), null, Modifier.size(18.dp), tint = tokens.ink2)
        }
        if (!largeText) IconBox(phaseIcon(phase.mode), stripe)
        Column(Modifier.weight(1f)) {
            Text(title, color = tokens.ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = tokens.ink2, fontSize = 14.sp)
            if (largeText) Text(length, color = tokens.ink2, fontSize = 15.sp)
        }
        if (!largeText) Text(length, color = tokens.ink2, fontSize = 15.sp)
    }
}

/** "Through the night": the ramp icon, "Heat 32° → 29°", how it changes, and ›. */
@Composable
internal fun MiddleCard(middle: Middle, title: String, modifier: Modifier, onClick: () -> Unit) {
    val tokens = paletteTokens
    val stripe = tokens.stepColor(middle.mode, middle.tempStart)
    val shape = RoundedCornerShape(16.dp)
    val down = middle.tempEnd < middle.tempStart
    val steps = when {
        !middle.mode.usesTemperature || middle.rampSteps <= 1 || middle.tempStart == middle.tempEnd -> stringResource(R.string.parity_mid_stay)
        middle.rampSteps == 2 -> stringResource(if (down) R.string.parity_mid_once_down else R.string.parity_mid_once_up)
        middle.rampSteps == 3 -> stringResource(if (down) R.string.parity_mid_twice_down else R.string.parity_mid_twice_up)
        else -> pluralStringResource(R.plurals.parity_mid_gently, middle.rampSteps, middle.rampSteps)
    }
    val fan = if (!middle.mode.usesFan) null else stringResource(when (middle.fanShape) {
        FanShape.STEADY -> R.string.parity_fan_steady_sub
        FanShape.EASE -> R.string.parity_fan_ease_sub
        FanShape.QUIET_MIDDLE -> R.string.parity_fan_quiet_sub
    })
    val fanDescription = middle.quietFan?.takeIf { middle.fanShape == FanShape.QUIET_MIDDLE }?.let {
        stringResource(R.string.profiles_quiet_middle_short, modeName(middle.mode), it.settle, it.asleep, it.rise)
    } ?: stringResource(R.string.profiles_middle_short, modeName(middle.mode), middle.fanStart, middle.fanEnd)
    Row(modifier.fillMaxWidth().semantics { contentDescription = "$title. $fanDescription" }.clip(shape).background(tokens.surface)
        .sideStripe(stripe, tokens, width = 5.dp, level = TintLevel.STEP)
        .then(tokens.edge?.let { Modifier.border(1.dp, it, shape) } ?: Modifier)
        .clickable(role = Role.Button, onClick = onClick)
        .heightIn(min = 64.dp).padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        IconBox(R.drawable.ic_nc_ramp, stripe)
        Column(Modifier.weight(1f)) {
            Text(title, color = tokens.ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(listOfNotNull(steps, fan).joinToString(" · "), color = tokens.ink2, fontSize = 14.sp)
        }
        Text("›", color = tokens.ink2, fontSize = 18.sp)
    }
}

/** The design's segmented control: a surface2 track with the selected option in a light pill. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun <T> Segmented(options: List<Pair<T, String>>, selected: T, modifier: Modifier = Modifier, tag: String = "segmented",
    description: @Composable (T) -> String? = { null }, onSelect: (T) -> Unit) {
    val tokens = paletteTokens
    val columns = if (LocalConfiguration.current.fontScale >= 1.5f) minOf(2, options.size) else options.size
    FlowRow(modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(tokens.surface2).padding(4.dp)
        .testTag(tag), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp),
        maxItemsInEachRow = columns) {
        options.forEach { (value, label) ->
            val on = value == selected
            val spoken = description(value)
            Box(Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp))
                .background(if (on) tokens.ink else Color.Transparent)
                .clickable(role = Role.Tab) { onSelect(value) }.semantics { this.selected = on; spoken?.let { contentDescription = it } }
                .testTag("$tag-$value"), contentAlignment = Alignment.Center) {
                Text(label, color = if (on) tokens.ground else tokens.ink2, fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, modifier = Modifier.padding(4.dp))
            }
        }
    }
}

/** A round − or + button. */
@Composable
internal fun RoundStep(text: String, description: String, size: Int, enabled: Boolean = true, tag: String, onClick: () -> Unit) {
    val tokens = paletteTokens
    Box(Modifier.size(maxOf(48, size).dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .semantics { contentDescription = description }.testTag(tag), contentAlignment = Alignment.Center) {
        Box(Modifier.size(size.dp).clip(CircleShape).background(tokens.surface2).alpha(if (enabled) 1f else .4f),
            contentAlignment = Alignment.Center) {
            Icon(if (text == "+") Icons.Default.Add else Icons.Default.Remove, null, Modifier.size(24.dp), tint = tokens.ink)
        }
    }
}

/** Keep a complete value between the controls when it fits, and above them at large text or narrow widths. */
@Composable
internal fun StepperLayout(modifier: Modifier = Modifier, controls: Boolean = true,
    down: @Composable () -> Unit, up: @Composable () -> Unit, value: @Composable (Modifier) -> Unit) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        if (!controls) value(Modifier.fillMaxWidth())
        else if (LocalConfiguration.current.fontScale >= 1.5f || maxWidth < 216.dp * LocalConfiguration.current.fontScale) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                value(Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) { down(); up() }
            }
        } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            down(); value(Modifier.weight(1f)); up()
        }
    }
}

/** A small rounded chip; the selected one is light on dark. */
@Composable
internal fun SmallChip(text: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    val tokens = paletteTokens
    Box(Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(50)).background(if (selected) tokens.ink else tokens.surface2)
        .clickable(role = Role.Button, onClick = onClick).semantics { this.selected = selected }
        .testTag(tag).padding(horizontal = 11.dp), contentAlignment = Alignment.Center) {
        Text(text, color = if (selected) tokens.ground else tokens.ink2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, softWrap = false)
    }
}

/** "Type a time": any hours and minutes from 1 minute to 12 hours. */
@Composable
internal fun TypeTimeDialog(minutes: Int, onDismiss: () -> Unit, onSet: (Int) -> Unit) {
    var hours by remember { mutableStateOf((minutes / 60).toString()) }
    var mins by remember { mutableStateOf((minutes % 60).toString()) }
    val total = (hours.toIntOrNull() ?: -1).let { h -> (mins.toIntOrNull() ?: -1).let { m -> if (h < 0 || m !in 0..59) -1 else h * 60 + m } }
    val valid = total in 1..720
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.parity_type_time)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(hours, { hours = it.filter(Char::isDigit).take(2) }, Modifier.weight(1f).testTag("type-time-hours"),
                        label = { Text(stringResource(R.string.parity_hours)) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), colors = nocturneTextFieldColors())
                    OutlinedTextField(mins, { mins = it.filter(Char::isDigit).take(2) }, Modifier.weight(1f).testTag("type-time-minutes"),
                        label = { Text(stringResource(R.string.parity_minutes)) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), colors = nocturneTextFieldColors())
                }
                if (!valid) Text(stringResource(R.string.parity_type_time_range), color = paletteTokens.ink2, fontSize = 13.sp)
            }
        },
        confirmButton = { NocturneTextButton(onClick = { onSet(total) }, enabled = valid) { Text(stringResource(R.string.uat_setup_done)) } },
        dismissButton = { NocturneTextButton(onClick = onDismiss) { Text(stringResource(R.string.feature_cancel)) } })
}

/** Starting points offered by "Add a step"; the exact values are set next. */
internal val ADD_STEP_CHOICES = listOf(
    Phase(Mode.TURBO, 86, 100, 10),
    Phase(Mode.HEAT, 64, 30, 30),
    Phase(Mode.EXTENDED_HEAT, 60, 40, 120),
    Phase(Mode.COOL, 44, 40, 30),
    Phase(Mode.DRY, 56, 40, 30),
    Phase(Mode.WAIT, 44, 5, 30),
)

/** The design's "Add a step" sheet body: which group, which kind, then "Next: set it up". */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AddStepContent(initial: Block, fahrenheit: Boolean, onCancel: () -> Unit, onAdd: (Block, Phase) -> Unit) {
    val tokens = paletteTokens
    var block by remember { mutableStateOf(initial) }
    var choice by remember { mutableStateOf(ADD_STEP_CHOICES[1]) }
    Column(Modifier.fillMaxWidth().heightIn(max = (LocalConfiguration.current.screenHeightDp * .85f).dp)
        .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp).testTag("editor-add-sheet"),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.parity_add_step), Modifier.weight(1f), color = tokens.ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            AccentLink(stringResource(R.string.feature_cancel), onCancel, fontSize = 15.sp)
        }
        Segmented(listOf(Block.START to stringResource(R.string.parity_group_start), Block.WAKE to stringResource(R.string.parity_group_wake),
            Block.AFTER_WAKE to stringResource(R.string.parity_group_after)), block, tag = "add-group") { block = it }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
            maxItemsInEachRow = if (LocalConfiguration.current.fontScale >= 1.5f) 2 else 3) {
            ADD_STEP_CHOICES.forEach { phase ->
                val temperature = tempText(phase.temperature, fahrenheit)
                val detail = when (phase.mode) {
                    Mode.EXTENDED_HEAT -> stringResource(R.string.parity_any_length, temperature)
                    Mode.WAIT -> compactDuration(phase.minutes)
                    else -> stringResource(R.string.quick_tile_detail, temperature, compactDuration(phase.minutes))
                }
                val stripe = tokens.stepColor(phase.mode, phase.temperature)
                val on = choice.mode == phase.mode
                StripeTile(stripe = stripe, title = modeName(phase.mode), subtitle = detail,
                    level = if (on) TintLevel.ON else TintLevel.TILE,
                    selectedBorder = if (on) tokens.readableText(stripe,
                        tokens.tint(stripe, TintLevel.ON).compositeOver(tokens.surface), minimumContrast = 3f) else null,
                    titleFontWeight = FontWeight.Bold,
                    icon = { Icon(painterResource(phaseIcon(phase.mode)), null, Modifier.size(16.dp), tint = stripe) },
                    modifier = Modifier.weight(1f).heightIn(min = 64.dp).testTag("add-choice-${phase.mode.name}")
                        .semantics { this.selected = on }.clickable(role = Role.Button) { choice = phase })
            }
        }
        Text(stringResource(R.string.parity_add_hint), color = tokens.ink2, fontSize = 13.sp, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth())
        BigButton(stringResource(R.string.parity_add_next), { onAdd(block, choice) }, modifier = Modifier.testTag("add-next"))
    }
}

/** The design's step editor body: mode, temperature, fan, how long and when it runs. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StepEditorContent(title: String, phase: Phase, fahrenheit: Boolean, runs: String?, tag: String,
    fanSlider: @Composable (Int, Mode, (Int) -> Unit) -> Unit, onDone: () -> Unit, onDelete: () -> Unit, onChange: (Phase) -> Unit) {
    val tokens = paletteTokens
    var typing by remember { mutableStateOf(false) }
    val turbo = phase.mode == Mode.TURBO
    val temperature = if (turbo) 86 else phase.temperature
    val color = tokens.stepColor(phase.mode, temperature)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp).testTag(tag),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), color = tokens.ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            AccentLink(stringResource(R.string.uat_setup_done), onDone, Modifier.testTag("step-done"), fontSize = 15.sp)
        }
        val modes = listOf(Mode.TURBO, Mode.HEAT, Mode.EXTENDED_HEAT, Mode.COOL, Mode.DRY, Mode.WAIT)
        Segmented(modes.map { it to if (it == Mode.EXTENDED_HEAT) stringResource(R.string.parity_long) else modeName(it) },
            phase.mode, tag = "step-mode", description = { mode ->
                if (mode.usesFan) "${modeName(mode)}, ${stringResource(R.string.ux_mode_max, tempText(mode.temperatureRange.last, fahrenheit))}"
                else modeName(mode)
            }) { mode ->
            val start = ADD_STEP_CHOICES.first { it.mode == mode }
            onChange(when {
                mode == phase.mode -> phase
                mode == Mode.TURBO -> phase.copy(mode = mode, temperature = 86, fanPercent = 100, minutes = 10)
                phase.mode == Mode.TURBO -> start
                else -> phase.copy(mode = mode, temperature = phase.temperature.coerceIn(mode.temperatureRange))
            })
        }
        if (phase.mode.usesTemperature || turbo) {
            val step = if (fahrenheit) 1 else 2
            val range = phase.mode.temperatureRange
            val temperatureLabel = stringResource(R.string.slider_temp)
            val temperatureState = spokenTemp(temperature, fahrenheit)
            StepperLayout(controls = !turbo,
                down = { RoundStep("−", stringResource(R.string.parity_cooler), 56, phase.temperature > range.first, "step-temp-down") {
                    onChange(phase.copy(temperature = (phase.temperature - step).coerceIn(range)))
                } },
                up = { RoundStep("+", stringResource(R.string.parity_warmer), 56, phase.temperature < range.last, "step-temp-up") {
                    onChange(phase.copy(temperature = (phase.temperature + step).coerceIn(range)))
                } }) { modifier ->
                Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(tempText(temperature, fahrenheit), Modifier.testTag("step-temp")
                        .then(if (turbo) Modifier else Modifier.sliderSemantics(temperatureLabel, temperatureState).semantics {
                            progressBarRangeInfo = ProgressBarRangeInfo(temperature.toFloat(), range.first.toFloat()..range.last.toFloat(),
                                (range.last - range.first - 1).coerceAtLeast(0))
                            setProgress { target -> onChange(phase.copy(temperature = target.roundToInt().coerceIn(range))); true }
                        }), color = tokens.readableText(color, tokens.sheet, minimumContrast = 3f), fontSize = if (LocalConfiguration.current.fontScale >= 1.5f) 40.sp else 52.sp,
                        fontWeight = FontWeight.Bold)
                    Text(if (turbo) stringResource(R.string.parity_turbo_always, tempText(86, fahrenheit))
                        else stringResource(R.string.parity_temperature), color = tokens.ink2, fontSize = 14.sp, textAlign = TextAlign.Center)
                }
            }
        }
        if (phase.mode.usesFan) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SectionLabel(stringResource(R.string.parity_fan), Modifier.weight(1f))
                Text("${if (turbo) 100 else phase.fanPercent}%", color = tokens.ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
            if (!turbo) fanSlider(phase.fanPercent, phase.mode) { onChange(phase.copy(fanPercent = it)) }
        }
        SectionLabel(stringResource(R.string.parity_how_long))
        val lengthLabel = stringResource(R.string.slider_length)
        val lengthState = spokenMinutes(phase.minutes)
        StepperLayout(controls = !turbo,
            down = { RoundStep("−", stringResource(R.string.parity_shorter), 44, phase.minutes > 1, "step-length-down") {
                onChange(phase.copy(minutes = (phase.minutes - 5).coerceAtLeast(1)))
            } },
            up = { RoundStep("+", stringResource(R.string.parity_longer), 44, phase.minutes < 720, "step-length-up") {
                onChange(phase.copy(minutes = (phase.minutes + 5).coerceAtMost(720)))
            } }) { modifier ->
            Text(if (turbo) compactDuration(phase.minutes) else stringResource(R.string.parity_h_min, phase.minutes / 60, phase.minutes % 60),
                modifier.testTag("step-length")
                    .then(if (turbo) Modifier else Modifier.sliderSemantics(lengthLabel, lengthState).semantics {
                        progressBarRangeInfo = ProgressBarRangeInfo(phase.minutes.toFloat(), 1f..720f, 718)
                        setProgress { target -> onChange(phase.copy(minutes = target.roundToInt().coerceIn(1, 720))); true }
                    }), color = tokens.ink, fontSize = 30.sp, fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center)
        }
        if (!turbo) {
            val chips = if (phase.mode == Mode.EXTENDED_HEAT || phase.minutes >= 180) listOf(60, 120, 240, 300) else listOf(30, 45, 60, 120)
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                chips.forEach { m -> SmallChip(compactDuration(m), phase.minutes == m, "step-chip-$m") { onChange(phase.copy(minutes = m)) } }
                SmallChip(stringResource(R.string.parity_type_time), false, "step-type-time") { typing = true }
            }
        } else Text(stringResource(R.string.parity_turbo_hint), color = tokens.ink2, fontSize = 13.sp)
        runs?.let { Text(it, Modifier.fillMaxWidth(), color = tokens.ink2, fontSize = 14.sp, textAlign = TextAlign.Center) }
        if (phase.mode == Mode.EXTENDED_HEAT || (phase.mode == Mode.HEAT && phase.minutes > 240))
            Text(stringResource(R.string.parity_long_heat_info), Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .background(tokens.surface2).padding(12.dp), color = tokens.ink2, fontSize = 13.sp)
        FlowRow(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.parity_delete_step), Modifier.heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onDelete)
                .testTag("step-delete").padding(vertical = 12.dp),
                color = if (tokens.dark) Color(0xFFFF9B91) else tokens.bad, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.parity_adjusts), Modifier.weight(1f), color = tokens.ink2, fontSize = 14.sp, textAlign = TextAlign.End)
        }
    }
    if (typing) TypeTimeDialog(phase.minutes, onDismiss = { typing = false }) { onChange(phase.copy(minutes = it)); typing = false }
}
