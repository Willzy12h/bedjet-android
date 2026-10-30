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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.store.Palette

@StringRes
internal fun paletteName(palette: Palette): Int = when (palette) {
    Palette.MIDNIGHT_INK -> R.string.palette_midnight_ink
    Palette.FOREST -> R.string.palette_forest
    Palette.GRAPHITE -> R.string.palette_graphite
    Palette.PLUM -> R.string.palette_plum
    Palette.AUTUMN_RUST -> R.string.palette_autumn_rust
    Palette.WINTER_STORM -> R.string.palette_winter_storm
    Palette.OVERCAST -> R.string.palette_overcast
}

/** The design's palette row: the chosen palette's name, then a swatch for each palette, ringed when chosen. */
@Composable
fun PalettePicker(label: String, selected: Palette, tagPrefix: String, inUse: Boolean = false, onSelect: (Palette) -> Unit) {
    val tokens = paletteTokens
    val night = tagPrefix == "night"
    // Each row leads with the palettes made for it; every palette stays selectable in both.
    val order = Palette.entries.sortedBy { if (it.tokens.dark == night) 0 else 1 }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel(label, Modifier.padding(start = 2.dp).semantics { heading() })
            if (inUse) Text(stringResource(R.string.appearance_in_use), Modifier.testTag("$tagPrefix-palette-in-use"),
                color = tokens.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(tokens.surface)
            .then(tokens.edge?.let { Modifier.border(1.dp, it, RoundedCornerShape(18.dp)) } ?: Modifier)
            .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(paletteName(selected)), Modifier.weight(1f), color = tokens.ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Row(Modifier.weight(1.6f, fill = false).horizontalScroll(rememberScrollState()).selectableGroup()) {
                order.forEach { palette ->
                    val name = stringResource(paletteName(palette))
                    val swatch = palette.tokens
                    val isSelected = palette == selected
                    Canvas(
                        Modifier.size(44.dp)
                            .testTag("$tagPrefix-palette-${palette.name}")
                            .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelect(palette) })
                            .semantics { contentDescription = name },
                    ) {
                        drawCircle(if (swatch.dark) swatch.glow else swatch.ground, 15.dp.toPx())
                        drawCircle(tokens.hairline, 15.dp.toPx(), style = Stroke(width = 1.dp.toPx()))
                        if (isSelected) drawCircle(tokens.accent, 19.dp.toPx(), style = Stroke(width = 2.dp.toPx()))
                    }
                }
            }
        }
    }
}
