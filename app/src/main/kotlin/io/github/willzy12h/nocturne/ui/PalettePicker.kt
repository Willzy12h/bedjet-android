package io.github.willzy12h.nocturne.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.store.Palette

@StringRes
private fun paletteName(palette: Palette): Int = when (palette) {
    Palette.MIDNIGHT_INK -> R.string.palette_midnight_ink
    Palette.FOREST -> R.string.palette_forest
    Palette.GRAPHITE -> R.string.palette_graphite
    Palette.PLUM -> R.string.palette_plum
    Palette.AUTUMN_RUST -> R.string.palette_autumn_rust
    Palette.WINTER_STORM -> R.string.palette_winter_storm
    Palette.OVERCAST -> R.string.palette_overcast
}

@Composable
fun PalettePicker(label: String, selected: Palette, tagPrefix: String, onSelect: (Palette) -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Text(stringResource(paletteName(selected)), style = MaterialTheme.typography.bodySmall)
        Row(Modifier.horizontalScroll(rememberScrollState()).selectableGroup()) {
            Palette.entries.forEach { palette ->
                val name = stringResource(paletteName(palette))
                val tokens = palette.tokens
                val isSelected = palette == selected
                Canvas(
                    Modifier.size(48.dp)
                        .testTag("$tagPrefix-palette-${palette.name}")
                        .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelect(palette) })
                        .semantics { contentDescription = name },
                ) {
                    val radius = 14.dp.toPx()
                    drawCircle(tokens.ground, radius)
                    drawCircle(tokens.accent, radius, style = Stroke(width = 2.dp.toPx()))
                    if (isSelected) {
                        drawCircle(tokens.accent, 19.dp.toPx(), style = Stroke(width = 2.dp.toPx()))
                        val stroke = 2.dp.toPx()
                        drawLine(tokens.accent, Offset(center.x - 6.dp.toPx(), center.y),
                            Offset(center.x - 2.dp.toPx(), center.y + 4.dp.toPx()), strokeWidth = stroke, cap = StrokeCap.Round)
                        drawLine(tokens.accent, Offset(center.x - 2.dp.toPx(), center.y + 4.dp.toPx()),
                            Offset(center.x + 7.dp.toPx(), center.y - 5.dp.toPx()), strokeWidth = stroke, cap = StrokeCap.Round)
                    }
                }
            }
        }
    }
}
