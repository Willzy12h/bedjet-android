package io.github.willzy12h.nocturne.ui

import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** The Start-button blue is a fill for large white text, not a small label or control indicator. */
@Composable
private fun controlForeground() = paletteTokens.readableText(paletteTokens.primary)

/** Input boundaries identify editable areas; the low-contrast decorative card edge is not suitable here. */
@Composable
internal fun nocturneTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedLabelColor = controlForeground(), focusedBorderColor = controlForeground(),
    cursorColor = controlForeground(), unfocusedBorderColor = paletteTokens.ink3,
)

@Composable
internal fun nocturneSliderColors(hideTicks: Boolean = false) = SliderDefaults.colors(
    thumbColor = controlForeground(), activeTrackColor = controlForeground(),
    activeTickColor = if (hideTicks) Color.Transparent else paletteTokens.ground,
    inactiveTickColor = if (hideTicks) Color.Transparent else controlForeground(),
)

@Composable
internal fun nocturneRadioColors() = RadioButtonDefaults.colors(selectedColor = controlForeground())

@Composable
internal fun nocturneCheckboxColors() = CheckboxDefaults.colors(
    checkedColor = controlForeground(), checkmarkColor = paletteTokens.ground,
)

/** A selected choice must remain distinct from its surrounding card or sheet in every palette. */
@Composable
internal fun nocturneFilterChipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = paletteTokens.ink, selectedLabelColor = paletteTokens.ground,
    selectedLeadingIconColor = paletteTokens.ground, selectedTrailingIconColor = paletteTokens.ground,
)
