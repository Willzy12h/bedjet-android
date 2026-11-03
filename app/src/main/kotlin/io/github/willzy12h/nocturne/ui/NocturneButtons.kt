package io.github.willzy12h.nocturne.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonElevation
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Keep small action labels legible on the palette's page, card and dialog surfaces. */
@Composable
fun NocturneTextButton(
    onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    shape: Shape = ButtonDefaults.textShape,
    colors: ButtonColors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
    elevation: ButtonElevation? = null, border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding,
    interactionSource: MutableInteractionSource? = null, content: @Composable RowScope.() -> Unit,
) = androidx.compose.material3.TextButton(onClick, modifier, enabled, shape, colors, elevation, border, contentPadding, interactionSource, content)

@Composable
fun NocturneOutlinedButton(
    onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    shape: Shape = ButtonDefaults.outlinedShape,
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
    elevation: ButtonElevation? = null, border: BorderStroke? = ButtonDefaults.outlinedButtonBorder(enabled),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null, content: @Composable RowScope.() -> Unit,
) = androidx.compose.material3.OutlinedButton(onClick, modifier, enabled, shape, colors, elevation, border, contentPadding, interactionSource, content)

/** Large, bold labels preserve the palette's specified primary/onPrimary pairing. */
@Composable
fun NocturneButton(
    onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    shape: Shape = ButtonDefaults.shape, colors: ButtonColors = ButtonDefaults.buttonColors(),
    elevation: ButtonElevation? = ButtonDefaults.buttonElevation(), border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null, content: @Composable RowScope.() -> Unit,
) = androidx.compose.material3.Button(onClick, modifier, enabled, shape, colors, elevation, border, contentPadding, interactionSource) {
    ProvideTextStyle(MaterialTheme.typography.labelLarge.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold)) { content() }
}
