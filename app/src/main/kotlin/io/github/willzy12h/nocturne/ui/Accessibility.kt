package io.github.willzy12h.nocturne.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import io.github.willzy12h.nocturne.R

/**
 * A slider's name and its value in words with the unit (U05): TalkBack reads "Fan at the start, 40 per cent" rather
 * than a bare position. The visible label beside the slider stays as it is.
 */
fun Modifier.sliderSemantics(label: String, state: String): Modifier = semantics {
    contentDescription = label
    stateDescription = state
}

@Composable fun spokenPercent(value: Int): String = stringResource(R.string.a11y_percent, value)

/** Half-degrees Celsius, spoken in the person's unit. */
@Composable fun spokenTemp(halfCelsius: Int, fahrenheit: Boolean): String {
    val number = Format.temp(halfCelsius, fahrenheit).removeSuffix("°C").removeSuffix("°F")
    return stringResource(if (fahrenheit) R.string.a11y_fahrenheit else R.string.a11y_celsius, number)
}

@Composable fun spokenMinutes(minutes: Int): String = pluralStringResource(R.plurals.a11y_minutes, minutes, minutes)

@Composable fun spokenRange(from: String, to: String): String = stringResource(R.string.a11y_range, from, to)
