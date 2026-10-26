package io.github.willzy12h.nocturne.night

import android.content.Context
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.protocol.Limits

/** BOOST's total duration includes Heat after the unit's ten-minute Turbo limit. */
fun boostDescription(context: Context, minutes: Int): String =
    if (minutes <= Limits.TURBO_MINUTES) context.getString(R.string.boost_description_turbo, minutes)
    else context.getString(R.string.boost_description_turbo_then_heat, Limits.TURBO_MINUTES, minutes - Limits.TURBO_MINUTES)
