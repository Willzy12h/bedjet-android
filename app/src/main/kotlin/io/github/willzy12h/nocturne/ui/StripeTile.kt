package io.github.willzy12h.nocturne.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun StripeTile(
    stripe: Color,
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    headerModifier: Modifier = Modifier,
    icon: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    titleFontWeight: FontWeight? = null,
    level: TintLevel = TintLevel.TILE,
    /** A selected or running tile's 1.5dp border: the stripe colour, or the accent for a profile card. */
    selectedBorder: Color? = null,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    val shape = RoundedCornerShape(12.dp)
    val tokens = paletteTokens
    Column(
        modifier.clip(shape).background(tokens.surface).sideStripe(stripe, tokens, level = level)
            .then(when {
                selectedBorder != null -> Modifier.border(1.5.dp, selectedBorder, shape)
                tokens.edge != null -> Modifier.border(1.dp, tokens.edge, shape)
                else -> Modifier
            })
            .heightIn(min = 48.dp).padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(headerModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            icon?.invoke()
            Text(title, Modifier.weight(1f), color = titleColor, fontWeight = titleFontWeight,
                style = MaterialTheme.typography.titleSmall)
            trailing?.invoke()
        }
        if (subtitle != null) Text(subtitle, Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall)
        content()
    }
}
