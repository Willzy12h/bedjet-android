package io.github.willzy12h.nocturne.night

import android.content.Context
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.protocol.Limits

/** BOOST's total duration includes Heat after the unit's ten-minute Turbo limit. */
fun boostDescription(context: Context, minutes: Int): String =
    if (minutes <= Limits.TURBO_MINUTES) context.resources.getQuantityString(R.plurals.boost_description_turbo, minutes, minutes)
    else context.resources.getQuantityString(R.plurals.boost_description_turbo_then_heat,
        minutes - Limits.TURBO_MINUTES, Limits.TURBO_MINUTES, minutes - Limits.TURBO_MINUTES)
