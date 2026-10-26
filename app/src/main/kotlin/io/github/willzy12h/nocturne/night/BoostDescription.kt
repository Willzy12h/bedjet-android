package io.github.willzy12h.nocturne.night

import android.content.Context
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.protocol.Limits

/** Each duration belongs to the mode that actually runs for it. */
fun boostLabel(context: Context, minutes: Int): String =
    if (minutes <= Limits.TURBO_MINUTES) context.getString(R.string.remote_boost, minutes)
    else context.getString(R.string.boost_label_turbo_then_heat, Limits.TURBO_MINUTES, minutes - Limits.TURBO_MINUTES)

/** A fixed-width widget names both modes; its content description retains both exact durations. */
fun compactBoostLabel(context: Context, minutes: Int): String =
    if (minutes <= Limits.TURBO_MINUTES) boostLabel(context, minutes)
    else context.getString(R.string.boost_label_turbo_then_heat_compact)

/** BOOST's total duration includes Heat after the unit's ten-minute Turbo limit. */
fun boostDescription(context: Context, minutes: Int): String =
    if (minutes <= Limits.TURBO_MINUTES) context.resources.getQuantityString(R.plurals.boost_description_turbo, minutes, minutes)
    else context.resources.getQuantityString(R.plurals.boost_description_turbo_then_heat,
        minutes - Limits.TURBO_MINUTES, Limits.TURBO_MINUTES, minutes - Limits.TURBO_MINUTES)
